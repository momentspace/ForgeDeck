package dev.forgedeck.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class PageResult<T>(val values: List<T>, val more: Boolean, val note: String = "")

class GitHubRepository(val api: GitHubApi, journal: PostJournal = MemoryPostJournal(), actor: () -> String = { "" }) {
    val posts = RecoverablePosts(api, journal, actor)
    suspend fun identity() = api.obj("user").getString("login")

    suspend fun repositories(page: Int): PageResult<Repo> {
        val list =
            api.arr(
                    "user/repos",
                    mapOf(
                        "per_page" to "50",
                        "page" to "$page",
                        "sort" to "updated",
                        "affiliation" to "owner,collaborator,organization_member",
                    ),
                    true,
                )
                .objects()
                .map(::parseRepo)
        return PageResult(list, list.size == 50)
    }

    suspend fun searchRepos(query: String, page: Int): PageResult<Repo> {
        val exact = query.trim()
        if (validRepo(exact)) {
            try {
                return PageResult(listOf(repo(exact)), false, "owner/repoで直接取得しました")
            } catch (e: ApiError) {
                if (e.code != 404) throw e
            }
        }
        val search =
            if (validRepo(exact))
                "${exact.substringAfter('/')} in:name user:${exact.substringBefore('/')}"
            else "$query in:name"
        val result =
            api.obj(
                "search/repositories",
                mapOf("q" to search, "per_page" to "50", "page" to "$page"),
                true,
            )
        val list = result.getJSONArray("items").objects().map(::parseRepo)
        return PageResult(
            list,
            page * 50 < minOf(result.optInt("total_count"), 1000),
            "GitHub検索（公開repoを含む） · 最大1000件" +
                (if (result.optBoolean("incomplete_results")) " · 部分取得" else ""),
        )
    }

    suspend fun repo(full: String, cached: Boolean = true): Repo {
        require(validRepo(full))
        return parseRepo(api.obj("repos/$full", cached = cached))
    }

    suspend fun branches(full: String, page: Int = 1): PageResult<String> {
        val list =
            api.arr("repos/$full/branches", mapOf("per_page" to "100", "page" to "$page"), true)
                .objects()
                .map { it.getString("name") }
        return PageResult(list, list.size == 100)
    }

    suspend fun entries(full: String, branch: String, path: String): PageResult<Entry> {
        val list =
            api.arr("repos/$full/contents/$path", mapOf("ref" to branch), true).objects().map {
                Entry(
                    it.str("name"),
                    it.str("path"),
                    it.str("type"),
                    it.str("sha"),
                    it.optLong("size"),
                )
            }
        return PageResult(
            list,
            list.size >= 1000,
            if (list.size >= 1000) "GitHub Contents APIの一覧上限に達しています。GitHubでも確認してください。" else "",
        )
    }

    suspend fun document(
        full: String,
        branch: String,
        path: String,
        cached: Boolean = true,
    ): Document {
        val obj = api.obj("repos/$full/contents/$path", mapOf("ref" to branch), cached)
        if (obj.str("type") != "file")
            return Document(
                path,
                obj.str("sha"),
                null,
                obj.optLong("size"),
                "${obj.str("type")} はアプリでは編集できません。GitHubで確認してください。",
            )
        if (obj.optLong("size") > 1_000_000 || obj.str("encoding") != "base64")
            return Document(
                path,
                obj.str("sha"),
                null,
                obj.optLong("size"),
                "大きなファイル、または未対応形式です。GitHubからダウンロードできます。",
            )
        val bytes = Base64.getMimeDecoder().decode(obj.str("content"))
        val text =
            runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }
                .getOrNull()
                ?.takeUnless { it.contains('\u0000') }
        return Document(
            path,
            obj.str("sha"),
            text,
            obj.optLong("size"),
            if (text == null) "バイナリまたはUTF-8以外のファイルです。GitHubで確認してください。" else "",
        )
    }

    suspend fun items(
        full: String,
        kind: String,
        page: Int = 1,
        state: String = "open",
    ): PageResult<Item> {
        val list =
            api.arr(
                    "repos/$full/${if(kind=="pr")"pulls" else "issues"}",
                    mapOf("per_page" to "50", "page" to "$page", "state" to state),
                    true,
                )
                .objects()
        return PageResult(
            list.filter { kind == "pr" || !it.has("pull_request") }.map { parseItem(it, full) },
            list.size == 50,
        )
    }

    suspend fun searchItems(query: String, page: Int = 1): PageResult<Item> {
        val o =
            api.obj(
                "search/issues",
                mapOf("q" to query, "per_page" to "50", "page" to "$page"),
                true,
            )
        return PageResult(
            o.getJSONArray("items").objects().map {
                parseItem(it, it.getString("repository_url").substringAfter("/repos/"))
            },
            page * 50 < minOf(o.optInt("total_count"), 1000),
            if (o.optBoolean("incomplete_results")) "検索結果は部分取得です" else "",
        )
    }

    suspend fun detail(item: Item): Detail {
        val root =
            api.obj("repos/${item.repo}/${if(item.isPr)"pulls" else "issues"}/${item.number}")
        val fresh = parseItem(root, item.repo)
        val partial = mutableListOf<String>()
        suspend fun safeArray(path: String): List<JSONObject> =
            try {
                val list = api.arr(path, mapOf("per_page" to "100")).objects()
                if (list.size == 100) partial.add("${path.substringAfterLast('/')} は最初の100件です")
                list
            } catch (e: ApiError) {
                if (e.code == 401) throw e
                partial.add("${path.substringAfterLast('/')} の取得権限または状態が未確認")
                emptyList()
            }
        val comments =
            safeArray("repos/${item.repo}/issues/${item.number}/comments")
                .map { Comment(cleanPostMarkers(it.str("body")), it.optJSONObject("user")?.str("login") ?: "") }
                .toMutableList()
        val related = mutableListOf<Item>()
        if (!item.isPr) {
            safeArray("repos/${item.repo}/issues/${item.number}/timeline")
                .filter { it.str("event") == "cross-referenced" }
                .mapNotNull { it.optJSONObject("source")?.optJSONObject("issue") }
                .forEach { obj ->
                    val targetRepo =
                        obj.optJSONObject("repository")?.str("full_name")
                            ?: obj.str("repository_url").substringAfter("/repos/", "")
                    if (validRepo(targetRepo)) related.add(parseItem(obj, targetRepo))
                }
            return Detail(
                fresh,
                comments,
                related.distinctBy { it.key },
                fetchedAt = System.currentTimeMillis(),
                partial = partial,
            )
        }
        comments.addAll(
            safeArray("repos/${item.repo}/pulls/${item.number}/comments").map {
                Comment(
                    cleanPostMarkers(it.str("body")),
                    it.optJSONObject("user")?.str("login") ?: "",
                    it.str("path"),
                    it.optInt("line"),
                )
            }
        )
        comments.addAll(
            safeArray("repos/${item.repo}/pulls/${item.number}/reviews").map {
                Comment(
                    "レビュー: ${it.str("state")}" +
                        (if (it.str("commit_id") != root.getJSONObject("head").str("sha"))
                            " · 別のコミットへのレビュー"
                        else "") +
                        "\n${cleanPostMarkers(it.str("body"))}",
                    it.optJSONObject("user")?.str("login") ?: "",
                )
            }
        )
        val changes =
            safeArray("repos/${item.repo}/pulls/${item.number}/files").map {
                ChangedFile(
                    it.str("filename"),
                    it.str("status"),
                    it.optInt("additions"),
                    it.optInt("deletions"),
                    it.str("patch").takeIf { p -> p.isNotEmpty() },
                )
            }
        val sha = root.getJSONObject("head").getString("sha")
        val checks = mutableListOf<Check>()
        var checksKnown = true
        try {
            val runs =
                api.obj("repos/${item.repo}/commits/$sha/check-runs", mapOf("per_page" to "100"))
            if (runs.optInt("total_count") > 100) {
                partial.add("チェックが100件を超えています")
                checksKnown = false
            }
            checks.addAll(
                runs.getJSONArray("check_runs").objects().map {
                    Check(
                        it.str("name"),
                        if (it.str("status") == "completed")
                            it.str("conclusion").ifEmpty { "unknown" }
                        else "pending",
                        it.str("html_url"),
                    )
                }
            )
            val status =
                api.obj("repos/${item.repo}/commits/$sha/status", mapOf("per_page" to "100"))
            if (status.optInt("total_count") > 100) {
                partial.add("commit statusが100件を超えています")
                checksKnown = false
            }
            checks.addAll(
                status.getJSONArray("statuses").objects().map {
                    Check(it.str("context"), it.str("state"), it.str("target_url"))
                }
            )
        } catch (e: ApiError) {
            if (e.code == 401) throw e
            checksKnown = false
            partial.add("CIチェックの一部が未確認です")
        }
        var mergeState = "UNKNOWN"
        var decision = "UNKNOWN"
        try {
            val query =
                "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number){headRefOid mergeStateStatus reviewDecision commits(last:1){nodes{commit{oid statusCheckRollup{contexts(first:100){nodes{__typename ... on CheckRun{name status conclusion detailsUrl isRequired(pullRequestNumber:\$number)} ... on StatusContext{context state targetUrl isRequired(pullRequestNumber:\$number)}} pageInfo{hasNextPage}}}}}} closingIssuesReferences(first:50){nodes{number title body state updatedAt url author{login} repository{nameWithOwner}} pageInfo{hasNextPage}}}}}"
            val result =
                api.post(
                    "graphql",
                    JSONObject()
                        .put("query", query)
                        .put(
                            "variables",
                            JSONObject()
                                .put("owner", item.repo.substringBefore('/'))
                                .put("name", item.repo.substringAfter('/'))
                                .put("number", item.number),
                        ),
                )
            if (result.has("errors")) throw ApiError(422, "PRルールを確認できませんでした")
            val pr =
                result
                    .getJSONObject("data")
                    .getJSONObject("repository")
                    .getJSONObject("pullRequest")
            if (pr.getString("headRefOid") != sha) throw ApiError(409, "PRのコミットが更新されました")
            mergeState = pr.str("mergeStateStatus")
            decision = pr.str("reviewDecision").ifEmpty { "UNKNOWN" }
            val commit =
                pr.getJSONObject("commits")
                    .getJSONArray("nodes")
                    .getJSONObject(0)
                    .getJSONObject("commit")
            if (commit.str("oid") != sha) throw ApiError(409, "チェック対象のコミットが更新されています")
            val contexts = commit.optJSONObject("statusCheckRollup")?.optJSONObject("contexts")
            if (contexts?.getJSONObject("pageInfo")?.optBoolean("hasNextPage") == true) {
                checksKnown = false
                partial.add("GraphQLチェックが100件を超えています")
            } else {
                checks.clear()
                contexts?.getJSONArray("nodes")?.objects()?.forEach { c ->
                    val run = c.str("__typename") == "CheckRun"
                    checks.add(
                        Check(
                            if (run) c.str("name") else c.str("context"),
                            if (run) {
                                if (c.str("status") == "COMPLETED")
                                    c.str("conclusion").lowercase().ifBlank { "unknown" }
                                else "pending"
                            } else c.str("state").lowercase(),
                            if (run) c.str("detailsUrl") else c.str("targetUrl"),
                            if (c.has("isRequired") && !c.isNull("isRequired"))
                                c.getBoolean("isRequired")
                            else null,
                        )
                    )
                }
                if (checks.any { it.required == null }) partial.add("必須/任意チェックの一部が未確認です")
            }
            val refs = pr.getJSONObject("closingIssuesReferences")
            if (refs.getJSONObject("pageInfo").optBoolean("hasNextPage"))
                partial.add("関連Issueは最初の50件です")
            refs.getJSONArray("nodes").objects().forEach { o ->
                related.add(
                    Item(
                        o.getJSONObject("repository").getString("nameWithOwner"),
                        o.getInt("number"),
                        o.str("title"),
                        o.str("body"),
                        o.optJSONObject("author")?.str("login") ?: "",
                        false,
                        o.str("state").lowercase(),
                        o.str("updatedAt"),
                        url = o.str("url"),
                    )
                )
            }
        } catch (e: ApiError) {
            if (e.code == 401) throw e
            partial.add("ブランチルール／レビュー条件は未確認です")
        }
        val push =
            try {
                repo(item.repo, false).canPush
            } catch (e: ApiError) {
                if (e.code == 401) throw e
                false
            }
        return Detail(
            fresh,
            comments,
            related.distinctBy { it.key },
            checks,
            changes,
            sha,
            root.getJSONObject("head").str("ref"),
            root.getJSONObject("base").str("ref"),
            root.optBoolean("draft"),
            root.optJSONArray("requested_reviewers")?.objects()?.map { it.str("login") }
                ?: emptyList(),
            if (root.isNull("mergeable")) null else root.optBoolean("mergeable"),
            mergeState,
            decision,
            push,
            checksKnown,
            System.currentTimeMillis(),
            partial,
        )
    }

    suspend fun issue(
        repo: String,
        title: String,
        body: String,
        number: Int = 0,
        labels: List<String> = emptyList(),
        assignees: List<String> = emptyList(),
    ): Item {
        require(title.isNotBlank())
        val payload =
            JSONObject()
                .put("title", title)
                .put("body", body)
                .put("labels", JSONArray(labels))
                .put("assignees", JSONArray(assignees))
        if (number == 0) return parseItem(posts.send("new-issue", repo, 0, false, "repos/$repo/issues", payload), repo)
        val path = "repos/$repo/issues/$number"
        val response = try { JSONObject(api.request("PATCH", path, payload)) } catch (e: IOException) {
            if (!uncertainWrite(e)) throw e
            val actual = api.obj(path)
            if (actual.str("title") != title || cleanPostMarkers(actual.str("body")) != body ||
                actual.optJSONArray("labels")?.objects()?.map { it.str("name") }?.toSet().orEmpty() != labels.toSet() ||
                actual.optJSONArray("assignees")?.objects()?.map { it.str("login") }?.toSet().orEmpty() != assignees.toSet()) throw e
            actual
        }
        return parseItem(response, repo)
    }

    suspend fun itemState(item: Item, newState: String) {
        val path = "repos/${item.repo}/${if(item.isPr)"pulls" else "issues"}/${item.number}"
        try { api.request(
            "PATCH",
            path,
            JSONObject().put("state", newState),
        ) } catch (e: IOException) {
            if (!uncertainWrite(e) || api.obj(path).str("state") != newState) throw e
        }
    }

    suspend fun comment(item: Item, body: String) {
        require(body.isNotBlank())
        posts.send("comment", item.repo, item.number, item.isPr,
            "repos/${item.repo}/issues/${item.number}/comments",
            JSONObject().put("body", body),
        )
    }

    suspend fun review(detail: Detail, event: String, body: String, login: String) {
        require(detail.item.author != login || event == "COMMENT") { "自分のPRには承認・修正依頼を送れません" }
        val fresh = api.obj("repos/${detail.item.repo}/pulls/${detail.item.number}")
        check(fresh.getJSONObject("head").getString("sha") == detail.headSha) {
            "PRが更新されています。差分を読み直してください"
        }
        posts.send("review", detail.item.repo, detail.item.number, true,
            "repos/${detail.item.repo}/pulls/${detail.item.number}/reviews",
            JSONObject().put("event", event).put("body", body).put("commit_id", detail.headSha),
        )
    }

    suspend fun inlineComment(detail: Detail, path: String, line: Int, side: String, body: String) {
        require(line > 0 && side in setOf("LEFT", "RIGHT") && body.isNotBlank())
        val fresh = api.obj("repos/${detail.item.repo}/pulls/${detail.item.number}")
        check(fresh.getJSONObject("head").str("sha") == detail.headSha) {
            "PRが更新されています。差分を読み直してください"
        }
        posts.send("inline", detail.item.repo, detail.item.number, true,
            "repos/${detail.item.repo}/pulls/${detail.item.number}/comments",
            JSONObject()
                .put("body", body)
                .put("commit_id", detail.headSha)
                .put("path", path)
                .put("line", line)
                .put("side", side),
        )
    }

    suspend fun merge(detail: Detail, method: String) {
        require(method in setOf("squash", "merge", "rebase"))
        val fresh = this.detail(detail.item)
        check(fresh.headSha == detail.headSha && fresh.safeCandidate) {
            "マージ条件やコミットが変わりました。最新状態を確認してください"
        }
        val response = try { JSONObject(
                api.request(
                    "PUT",
                    "repos/${detail.item.repo}/pulls/${detail.item.number}/merge",
                    JSONObject().put("sha", detail.headSha).put("merge_method", method),
                )
            ) } catch (e: IOException) {
                if (!uncertainWrite(e)) throw e
                val actual = api.obj("repos/${detail.item.repo}/pulls/${detail.item.number}")
                if (!actual.optBoolean("merged") || actual.getJSONObject("head").str("sha") != detail.headSha) throw e
                JSONObject().put("merged", true)
            }
        check(response.optBoolean("merged")) { "マージは完了していません。最新状態を確認してください" }
    }

    suspend fun propose(editor: Editor, onPrepared: (PendingPr) -> Unit = {}): PendingPr {
        require(
            validRepo(editor.repo) &&
                validBranch(editor.base) &&
                validBranch(editor.target) &&
                editor.target != editor.base &&
                editor.title.isNotBlank()
        ) {
            "保存先とタイトルを確認してください"
        }
        require(editor.operation == "delete" || validPath(editor.newPath)) { "相対パスを入力してください" }
        check(repo(editor.repo, false).canPush) { "このrepoへの書込み権限がありません" }
        val head =
            api.obj("repos/${editor.repo}/git/ref/heads/${editor.base}")
                .getJSONObject("object")
                .getString("sha")
        val baseTree =
            api.obj("repos/${editor.repo}/git/commits/$head").getJSONObject("tree").getString("sha")
        var mode = "100644"
        if (editor.oldPath.isNotEmpty()) {
            val original =
                api.obj("repos/${editor.repo}/contents/${editor.oldPath}", mapOf("ref" to head))
            check(original.str("sha") == editor.originalSha) {
                "元ファイルが更新されています。下書きは残しています。最新との差分を確認してください"
            }
            var currentTree = baseTree
            for ((index, part) in editor.oldPath.split('/').withIndex()) {
                val tree = api.obj("repos/${editor.repo}/git/trees/$currentTree")
                check(!tree.optBoolean("truncated")) { "ツリーが部分取得です。保存を中断しました" }
                val entry = tree.getJSONArray("tree").objects().first { it.str("path") == part }
                if (index == editor.oldPath.split('/').lastIndex) {
                    mode = entry.str("mode")
                    check(mode in setOf("100644", "100755")) { "この種類のファイルは編集できません" }
                } else currentTree = entry.str("sha")
            }
        }
        if (editor.operation != "delete" && editor.newPath != editor.oldPath) {
            try {
                api.obj("repos/${editor.repo}/contents/${editor.newPath}", mapOf("ref" to head))
                error("保存先に既存ファイルがあります")
            } catch (e: ApiError) {
                if (e.code != 404) throw e
            }
        }
        val entries = JSONArray()
        if (
            editor.operation == "delete" ||
                (editor.oldPath.isNotBlank() && editor.newPath != editor.oldPath)
        )
            entries.put(
                JSONObject()
                    .put("path", editor.oldPath)
                    .put("mode", mode)
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        if (editor.operation != "delete")
            entries.put(
                JSONObject()
                    .put("path", editor.newPath)
                    .put("mode", mode)
                    .put("type", "blob")
                    .put("content", editor.text)
            )
        val tree =
            api.post(
                    "repos/${editor.repo}/git/trees",
                    JSONObject().put("base_tree", baseTree).put("tree", entries),
                )
                .getString("sha")
        val commit =
            api.post(
                    "repos/${editor.repo}/git/commits",
                    JSONObject()
                        .put("message", editor.title)
                        .put("tree", tree)
                        .put("parents", JSONArray().put(head)),
                )
                .getString("sha")
        val pending = PendingPr(editor.repo, editor.base, editor.target, editor.title, commit)
        onPrepared(pending)
        ensureBranch(pending)
        return pending
    }

    private suspend fun ensureBranch(p: PendingPr) {
        if (p.commitSha.isBlank()) return
        val current =
            try {
                api.obj("repos/${p.repo}/git/ref/heads/${p.head}")
                    .getJSONObject("object")
                    .getString("sha")
            } catch (e: ApiError) {
                if (e.code != 404) throw e
                null
            }
        if (current == null)
            api.post(
                "repos/${p.repo}/git/refs",
                JSONObject().put("ref", "refs/heads/${p.head}").put("sha", p.commitSha),
            )
        else check(current == p.commitSha) { "保存したブランチが更新されています。GitHubで状態を確認してください" }
    }

    suspend fun createPr(p: PendingPr): Item {
        require(
            validRepo(p.repo) &&
                validBranch(p.base) &&
                validBranch(p.head) &&
                p.base != p.head &&
                p.title.isNotBlank()
        )
        ensureBranch(p)
        val existing =
            api.arr(
                "repos/${p.repo}/pulls",
                mapOf(
                    "head" to "${p.repo.substringBefore('/')}:${p.head}",
                    "base" to p.base,
                    "state" to "all",
                ),
            )
        if (existing.length() > 0) return parseItem(existing.getJSONObject(0), p.repo)
        return parseItem(
            api.post(
                "repos/${p.repo}/pulls",
                JSONObject()
                    .put("title", p.title)
                    .put("head", p.head)
                    .put("base", p.base)
                    .put("body", p.body),
            ),
            p.repo,
        )
    }

    suspend fun notifications(page: Int = 1): PageResult<Notification> {
        val list =
            api.arr(
                    "notifications",
                    mapOf("all" to "true", "per_page" to "50", "page" to "$page"),
                    true,
                )
                .objects()
                .map { o ->
                    val subject = o.getJSONObject("subject")
                    val repo = o.getJSONObject("repository").getString("full_name")
                    Notification(
                        o.getString("id"),
                        subject.str("title"),
                        repo,
                        subject.str("type"),
                        subject.str("url").substringAfterLast('/').toIntOrNull() ?: 0,
                        o.str("reason"),
                        o.optBoolean("unread"),
                    )
                }
        return PageResult(list, list.size == 50)
    }

    suspend fun markRead(id: String) {
        require(id.all { it.isDigit() })
        val path = "notifications/threads/$id"
        try { api.request("PATCH", path) } catch (e: IOException) {
            if (!uncertainWrite(e)) throw e
            val actual = api.obj(path)
            if (!actual.has("unread") || actual.optBoolean("unread", true)) throw e
        }
    }

    suspend fun markAllRead(): Boolean {
        return try { api.reply(
                "PUT",
                "notifications",
                JSONObject().put("last_read_at", java.time.Instant.now().toString()),
            )
            .code != 202 } catch (e: IOException) {
                if (!uncertainWrite(e)) throw e
                api.arr("notifications", mapOf("per_page" to "1", "all" to "false")).length() == 0
            }
    }

    fun newBranch() = "forgedeck/change-${UUID.randomUUID().toString().take(12)}"
}
