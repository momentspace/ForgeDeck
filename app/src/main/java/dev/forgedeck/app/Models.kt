package dev.forgedeck.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class Repo(val fullName: String, val description: String = "", val language: String = "", val private: Boolean = false, val defaultBranch: String = "main", val canPush: Boolean = false) {
    val owner get() = fullName.substringBefore('/')
    val name get() = fullName.substringAfter('/')
}
data class Entry(val name: String, val path: String, val type: String, val sha: String, val size: Long)
data class Document(val path: String, val sha: String, val text: String?, val size: Long, val note: String = "")
data class Item(val repo: String, val number: Int, val title: String, val body: String, val author: String, val isPr: Boolean, val state: String, val updated: String, val assignees: List<String> = emptyList(), val labels: List<String> = emptyList(), val url: String = "") {
    val key get() = "$repo#$number"
}
data class Comment(val body: String, val author: String, val path: String = "", val line: Int = 0)
data class Check(val name: String, val state: String, val url: String = "")
data class ChangedFile(val path: String, val status: String, val additions: Int, val deletions: Int, val patch: String?)
data class Notification(val id: String, val title: String, val repo: String, val kind: String, val number: Int, val reason: String, val unread: Boolean)
data class Detail(val item: Item, val comments: List<Comment> = emptyList(), val related: List<Item> = emptyList(), val checks: List<Check> = emptyList(), val files: List<ChangedFile> = emptyList(), val headSha: String = "", val headBranch: String = "", val baseBranch: String = "", val draft: Boolean = false, val requested: List<String> = emptyList(), val mergeable: Boolean? = null, val mergeState: String = "UNKNOWN", val reviewDecision: String = "UNKNOWN", val canPush: Boolean = false, val checksKnown: Boolean = false, val fetchedAt: Long = 0, val partial: List<String> = emptyList()) {
    val safeCandidate get() = item.isPr && item.state == "open" && !draft && canPush && mergeable == true && mergeState == "CLEAN" && reviewDecision == "APPROVED" && checksKnown && checks.isNotEmpty() && checks.all { it.state in setOf("success", "neutral", "skipped") } && partial.isEmpty()
}
enum class Lane(val label: String) { MINE("自分の番"), WAITING("待ち"), READY("マージ候補"), ALL("すべて") }
data class Assessment(val lane: Lane, val reason: String)
fun assess(item: Item, login: String, detail: Detail?, manualWait: Boolean): Assessment = when {
    item.state != "open" -> Assessment(Lane.ALL, "完了した作業")
    detail?.requested?.contains(login) == true -> Assessment(Lane.MINE, "あなたにレビューが依頼されています")
    item.isPr && item.author == login && detail?.checks?.any { it.state in setOf("failure", "error", "cancelled", "timed_out", "action_required") } == true -> Assessment(Lane.MINE, "あなたのPRでチェックが失敗しています")
    !item.isPr && login in item.assignees && !manualWait -> Assessment(Lane.MINE, "あなたが担当者です")
    manualWait -> Assessment(Lane.WAITING, "あなたが待ちに設定しました")
    detail?.safeCandidate == true -> Assessment(Lane.READY, "必須条件を取得済み · チェック成功 · 承認あり")
    item.isPr && item.author == login && detail?.requested?.isNotEmpty() == true -> Assessment(Lane.WAITING, "依頼したレビューを待っています")
    item.isPr && item.author == login -> Assessment(Lane.MINE, "あなたのPRの状態を確認してください")
    else -> Assessment(Lane.ALL, "分類に必要な情報が未確認です")
}
fun validRepo(value: String) = value.split('/').let { it.size == 2 && it.all { p -> p.isNotBlank() && Regex("[A-Za-z0-9_.-]+").matches(p) && p !in setOf(".", "..") } }
fun validPath(value: String) = value.isNotBlank() && !value.startsWith('/') && value.split('/').none { it.isBlank() || it == "." || it == ".." } && value.none { it.code < 32 || it == '\\' }
fun validBranch(value: String) = value.isNotBlank() && !value.startsWith('/') && !value.endsWith('/') && !value.endsWith('.') && !value.contains("..") && !value.contains("@{") && value != "@" && value.split('/').none { it.isBlank() || it.startsWith('.') || it.endsWith(".lock") } && value.none { it.code < 33 || it.code == 127 || it in "~^:?*[\\" }
data class LinkTarget(val repo: String, val number: Int = 0, val isPr: Boolean = false)
fun githubTarget(value: String): LinkTarget? = runCatching {
    val u = URI(value)
    if (u.scheme != "https" || u.host != "github.com" || u.userInfo != null || u.port != -1) return@runCatching null
    val p = u.path.trim('/').split('/')
    if (p.size < 2 || !validRepo(p.take(2).joinToString("/"))) return@runCatching null
    if (p.size == 2) return@runCatching LinkTarget(p.take(2).joinToString("/"))
    if (p.size == 4 && p[2] in setOf("issues", "pull") && (p[3].toIntOrNull() ?: 0) > 0) LinkTarget(p.take(2).joinToString("/"), p[3].toInt(), p[2] == "pull") else null
}.getOrNull()
data class DiffLine(val text: String, val kind: Char, val left: Int? = null, val right: Int? = null)
fun patchLines(patch: String): List<DiffLine> {
    var left = 0; var right = 0
    return patch.lineSequence().map { line ->
        val header = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matchEntire(line)
        when {
            header != null -> { left = header.groupValues[1].toInt(); right = header.groupValues[2].toInt(); DiffLine(line, '@') }
            line.startsWith('+') -> DiffLine(line, '+', right = right++)
            line.startsWith('-') -> DiffLine(line, '-', left = left++)
            line.startsWith(' ') -> DiffLine(line, ' ', left++, right++)
            else -> DiffLine(line, '@')
        }
    }.toList()
}
fun textDiff(old: String, new: String): List<DiffLine> {
    val a = old.lines(); val b = new.lines(); var first = 0; var tail = 0
    while (first < minOf(a.size, b.size) && a[first] == b[first]) first++
    while (tail < minOf(a.size, b.size) - first && a[a.lastIndex - tail] == b[b.lastIndex - tail]) tail++
    return a.take(first).map { DiffLine(it, ' ') } + a.subList(first, a.size - tail).map { DiffLine(it, '-') } + b.subList(first, b.size - tail).map { DiffLine(it, '+') } + b.takeLast(tail).map { DiffLine(it, ' ') }
}
fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.str(name: String) = if (isNull(name)) "" else optString(name, "")
fun parseRepo(o: JSONObject) = Repo(o.getString("full_name"), o.str("description"), o.str("language"), o.optBoolean("private"), o.optString("default_branch", "main"), o.optJSONObject("permissions")?.optBoolean("push") == true)
fun parseItem(o: JSONObject, repo: String) = Item(repo, o.getInt("number"), o.str("title"), o.str("body"), o.optJSONObject("user")?.str("login") ?: "", o.has("pull_request") || o.has("head"), o.str("state"), o.str("updated_at"), o.optJSONArray("assignees")?.objects()?.map { it.str("login") } ?: emptyList(), o.optJSONArray("labels")?.objects()?.map { it.str("name") } ?: emptyList(), o.str("html_url"))
enum class Page { REPOS, FILES, FILE, EDITOR, ITEM, DECK, INBOX }
data class Route(val page: Page = Page.REPOS, val repo: String = "", val branch: String = "", val path: String = "", val number: Int = 0)
data class Editor(val repo: String, val base: String, val oldPath: String, val newPath: String, val originalSha: String, val original: String, val text: String, val operation: String = "edit", val target: String, val title: String) {
    val key get() = "$repo|$base|$oldPath|$operation"
    val dirty get() = operation != "edit" || text != original || newPath != oldPath
}
data class PendingPr(val repo: String, val base: String, val head: String, val title: String)
