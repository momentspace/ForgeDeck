package dev.forgedeck.app

import java.io.IOException
import java.util.UUID
import org.json.JSONObject

data class PendingPost(
    val id: String,
    val kind: String,
    val repo: String,
    val number: Int,
    val isPr: Boolean,
    val path: String,
    val payload: String,
    val actor: String,
) {
    val marker get() = "<!-- forgedeck-op:$id -->"
    val url get() = "https://github.com/$repo" + if (number > 0) "/${if (isPr) "pull" else "issues"}/$number" else "/issues"
    val draftKey get() = formKey(kind, repo, number) + if (kind == "inline") {
        val p = JSONObject(payload)
        "|${p.str("path")}|${p.optInt("line")}|${p.str("side")}"
    } else ""
    fun json() = JSONObject().put("id", id).put("kind", kind).put("repo", repo)
        .put("number", number).put("isPr", isPr).put("path", path).put("payload", payload).put("actor", actor)
}

fun formKey(action: String, repo: String, number: Int) = "$repo|$action|$number"

fun cleanPostMarkers(body: String) = body.replace(Regex("(?:\\r?\\n){0,2}<!-- forgedeck-op:[0-9a-fA-F-]{36} -->"), "")

fun uncertainWrite(error: IOException) = error !is ApiError || error.code >= 500 || error.code == 408

class UnconfirmedPost : IOException("投稿の送信結果が未確定です。上の「送信結果を確認」で照会してください。自動再送はしません。")

interface PostJournal {
    fun pendingPost(): PendingPost?
    fun pendingPost(value: PendingPost?)
}

class MemoryPostJournal : PostJournal {
    private var value: PendingPost? = null
    override fun pendingPost() = value
    override fun pendingPost(value: PendingPost?) { this.value = value }
}

/** Only a new operation may POST. An uncertain operation is resolved by GET, never resent. */
class RecoverablePosts(private val api: GitHubApi, val journal: PostJournal, private val actor: () -> String) {
    suspend fun send(kind: String, repo: String, number: Int, isPr: Boolean, path: String, payload: JSONObject): JSONObject {
        val previous = journal.pendingPost()
        if (previous != null) {
            check(previous.path == path && previous.payload == payload.toString() && previous.kind == kind) {
                "前の投稿の送信結果を確認してください。未確定の間は別の投稿を送信できません。"
            }
            return resolve() ?: throw UnconfirmedPost()
        }
        val pending = PendingPost(UUID.randomUUID().toString(), kind, repo, number, isPr, path, payload.toString(), actor())
        journal.pendingPost(pending)
        val marked = JSONObject(pending.payload).put("body", payload.str("body") + "\n\n" + pending.marker)
        try {
            val result = api.post(path, marked)
            check(result.optLong("id") > 0) { "投稿の識別子を確認できませんでした" }
            journal.pendingPost(null)
            return result
        } catch (e: IOException) {
            if (!uncertainWrite(e)) {
                journal.pendingPost(null)
                throw e
            }
            // A failed lookup is also uncertain. Do not convert it to permission to retry.
            try { resolve()?.let { return it } } catch (lookup: IOException) {
                if (lookup is ApiError && lookup.code == 401) throw lookup
            }
            throw UnconfirmedPost()
        }
    }

    suspend fun resolve(): JSONObject? {
        val p = journal.pendingPost() ?: return null
        for (page in 1..20) {
            val query = mutableMapOf("per_page" to "100", "page" to page.toString())
            if (p.kind == "new-issue") {
                query["state"] = "all"
                query["sort"] = "created"
                query["direction"] = "desc"
                if (p.actor.isNotBlank()) query["creator"] = p.actor
            }
            val objects = api.arr(p.path, query).objects()
            val found = objects.firstOrNull { o ->
                o.str("body").contains(p.marker) &&
                    (p.actor.isBlank() || o.optJSONObject("user")?.str("login") == p.actor) &&
                    (p.kind != "new-issue" || !o.has("pull_request"))
            }
            if (found != null) {
                check(found.optLong("id") > 0) { "投稿の識別子を確認できませんでした" }
                journal.pendingPost(null)
                return found
            }
            if (objects.size < 100) return null
        }
        return null
    }
}
