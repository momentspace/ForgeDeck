package dev.forgedeck.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class UiState(
    val login: String = "",
    val route: Route = Route(),
    val tab: Page = Page.REPOS,
    val busy: Boolean = false,
    val writing: Boolean = false,
    val error: String = "",
    val notice: String = "",
    val repos: List<Repo> = emptyList(),
    val selectedRepo: Repo? = null,
    val entries: List<Entry> = emptyList(),
    val document: Document? = null,
    val items: List<Item> = emptyList(),
    val detail: Detail? = null,
    val deckDetails: Map<String, Detail> = emptyMap(),
    val notifications: List<Notification> = emptyList(),
    val branches: List<String> = emptyList(),
    val branchesMore: Boolean = false,
    val branchesPage: Int = 1,
    val page: Int = 1,
    val more: Boolean = false,
    val repoKind: String = "files",
    val itemState: String = "open",
    val repoQuery: String = "",
    val pins: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val waiting: Set<String> = emptySet(),
    val editor: Editor? = null,
    val pending: PendingPr? = null,
    val pendingPost: PendingPost? = null,
    val theme: String = "system",
    val cacheTime: Long? = null,
    val deckFetchedAt: Long = 0,
    val deviceCode: String = "",
    val deviceUrl: String = "",
    val notificationsSupported: Boolean = true,
)

class ForgeViewModel(val store: LocalStore, apiOverride: GitHubApi? = null) : ViewModel() {
    private var credential = store.token()

    private fun notificationSupport(token: String) =
        !token.startsWith("github_pat_") && !token.startsWith("ghu_")

    private val mutable =
        MutableStateFlow(
            UiState(
                login = if (credential.isNotBlank()) store.login else "",
                theme = store.theme,
                pins = store.pins(),
                recent = store.recent(),
                waiting = store.waiting(),
                pending = store.pending(),
                pendingPost = store.pendingPost(),
                notificationsSupported = notificationSupport(credential),
            )
        )
    val ui = mutable.asStateFlow()
    private val api = apiOverride ?: GitHubApi(
            { credential },
            store::cachePut,
            store::cacheGet,
            { time -> mutable.update { it.copy(cacheTime = time) } },
            validatorGet = store::validatorGet,
            validatorPut = store::validatorPut,
        )
    private val repository = GitHubRepository(api, store) { store.login }
    private var readJob: Job? = null
    private var authJob: Job? = null
    private var authGeneration = 0
    private var operationId = 0
    private val history = mutableListOf<UiState>()
    private var pendingLink: LinkTarget? = null
    private var draftJob: Job? = null
    private val draftMutex = Mutex()
    private var formDraftJob: Job? = null
    private val formDraftMutex = Mutex()
    private val formDraftValues = mutableMapOf<String, String>()

    fun formDraft(key: String): String? = synchronized(formDraftValues) { formDraftValues[key] } ?: store.formDraft(key)

    fun saveFormDraft(key: String, value: String) {
        synchronized(formDraftValues) { formDraftValues[key] = value }
        formDraftJob?.cancel()
        val snapshot = synchronized(formDraftValues) { formDraftValues.toMap() }
        formDraftJob = viewModelScope.launch(Dispatchers.IO) {
            delay(350)
            formDraftMutex.withLock { snapshot.forEach { (k, v) -> store.formDraft(k, v) } }
        }
    }

    fun clearFormDraft(key: String) {
        formDraftJob?.cancel()
        synchronized(formDraftValues) { formDraftValues.remove(key) }
        runBlocking(Dispatchers.IO) { formDraftMutex.withLock { store.formDraft(key, null) } }
    }

    private fun clearAccount() {
        draftJob?.cancel()
        formDraftJob?.cancel()
        synchronized(formDraftValues) { formDraftValues.clear() }
        runBlocking(Dispatchers.IO) { formDraftMutex.withLock { } }
        runBlocking(Dispatchers.IO) { draftMutex.withLock { store.logout() } }
    }

    fun flushDraft(): Boolean {
        draftJob?.cancel()
        formDraftJob?.cancel()
        val editor = mutable.value.editor
        val forms = synchronized(formDraftValues) { formDraftValues.toMap() }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                draftMutex.withLock { editor?.let(store::draft) }
                formDraftMutex.withLock { forms.forEach { (k, v) -> store.formDraft(k, v) } }
            }
            true
        }.getOrElse {
            mutable.update { state -> state.copy(error = "下書きを保存できませんでした。入力をコピーしてから再試行してください。") }
            false
        }
    }

    init {
        if (credential.isNotEmpty()) refresh()
    }

    private fun run(write: Boolean = false, recovery: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.writing) return
        if (write && !recovery && store.pendingPost() != null) {
            mutable.update { it.copy(error = "前の投稿の送信結果を先に確認してください。自動再送はしません。", pendingPost = store.pendingPost()) }
            return
        }
        if (write && !flushDraft()) return
        readJob?.cancel()
        val currentId = ++operationId
        val job =
            viewModelScope.launch {
                mutable.update {
                    it.copy(busy = true, writing = write, error = "", notice = "", cacheTime = null)
                }
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiError && e.code == 401) {
                        flushDraft()
                        credential = ""
                        store.expire()
                        history.clear()
                        mutable.value =
                            UiState(
                                theme = store.theme,
                                error = e.message.orEmpty(),
                                notice = "下書きは残しています。同じアカウントで再接続してください。",
                            )
                    } else
                        mutable.update {
                            it.copy(error = e.message?.take(600) ?: "処理に失敗しました。下書きは端末に保持しています。")
                        }
                } finally {
                    if (currentId == operationId)
                        mutable.update { it.copy(busy = false, writing = false, pendingPost = store.pendingPost()) }
                }
            }
        if (!write) readJob = job
    }

    fun login(token: String) {
        if (token.isBlank()) {
            mutable.update { it.copy(error = "トークンを入力してください") }
            return
        }
        authGeneration++
        authJob?.cancel()
        run(true, recovery = true) {
            val candidate = GitHubRepository(GitHubApi({ token.trim() }))
            val user = candidate.identity()
            if (store.login.isNotBlank() && store.login != user) clearAccount()
            store.saveToken(token.trim(), user)
            credential = token.trim()
            history.clear()
            mutable.value =
                UiState(
                    login = user,
                    theme = store.theme,
                    pins = store.pins(),
                    recent = store.recent(),
                    waiting = store.waiting(),
                    pending = store.pending(),
                    pendingPost = store.pendingPost(),
                    busy = true,
                    writing = true,
                    notificationsSupported = notificationSupport(credential),
                )
            val target = pendingLink
            pendingLink = null
            if (target == null) loadRepos(1)
            else if (target.number == 0) {
                val repo = repository.repo(target.repo)
                go(Route(Page.FILES, target.repo, repo.defaultBranch))
                mutable.update { it.copy(selectedRepo = repo, repoKind = "files") }
                loadFiles()
            } else
                showSaved(
                    Item(target.repo, target.number, "読込中", "", "", target.isPr, "open", ""),
                    "接続しました",
                )
        }
    }

    fun startDeviceFlow(clientId: String) {
        if (clientId.isBlank()) return
        authJob?.cancel()
        val generation = ++authGeneration
        authJob =
            viewModelScope.launch {
                mutable.update { it.copy(busy = true, error = "") }
                try {
                    val client =
                        OkHttpClient.Builder()
                            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(false)
                            .build()
                    suspend fun post(path: String, data: JSONObject): JSONObject =
                        withContext(Dispatchers.IO) {
                            val request =
                                Request.Builder()
                                    .url("https://github.com/$path")
                                    .header("Accept", "application/json")
                                    .post(
                                        data
                                            .toString()
                                            .toRequestBody("application/json".toMediaType())
                                    )
                                    .build()
                            client.newCall(request).execute().use { response ->
                                check(response.isSuccessful) { "GitHubの認証処理に失敗しました" }
                                JSONObject(response.body!!.string())
                            }
                        }
                    val codes =
                        post("login/device/code", JSONObject().put("client_id", clientId.trim()))
                    check(codes.str("verification_uri") == "https://github.com/login/device") {
                        "認証先が未確認です"
                    }
                    mutable.update {
                        it.copy(
                            deviceCode = codes.getString("user_code"),
                            deviceUrl = codes.getString("verification_uri"),
                            busy = false,
                        )
                    }
                    var interval = maxOf(codes.optLong("interval", 5), 5)
                    val until = System.currentTimeMillis() + codes.getLong("expires_in") * 1000
                    while (System.currentTimeMillis() < until) {
                        delay(interval * 1000)
                        val result =
                            post(
                                "login/oauth/access_token",
                                JSONObject()
                                    .put("client_id", clientId.trim())
                                    .put("device_code", codes.getString("device_code"))
                                    .put(
                                        "grant_type",
                                        "urn:ietf:params:oauth:grant-type:device_code",
                                    ),
                            )
                        if (result.has("access_token")) {
                            mutable.update { it.copy(deviceCode = "", deviceUrl = "") }
                            authJob = null
                            login(result.getString("access_token"))
                            return@launch
                        }
                        when (result.str("error")) {
                            "authorization_pending" -> Unit
                            "slow_down" -> interval += 5
                            else -> error("認証が終了しました。${result.str("error")}")
                        }
                    }
                    error("認証コードの有効期限が切れました")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(error = e.message.orEmpty(), deviceCode = "", deviceUrl = "")
                    }
                } finally {
                    if (generation == authGeneration && authJob != null)
                        mutable.update { it.copy(busy = false) }
                }
            }
    }

    fun cancelDeviceFlow() {
        authGeneration++
        authJob?.cancel()
        mutable.update { it.copy(deviceCode = "", deviceUrl = "", busy = false) }
    }

    fun logout() {
        if (mutable.value.writing) return
        readJob?.cancel()
        authGeneration++
        authJob?.cancel()
        credential = ""
        clearAccount()
        history.clear()
        mutable.value = UiState(theme = store.theme)
    }

    fun theme(value: String) {
        store.theme = value
        mutable.update { it.copy(theme = value) }
    }

    fun report(message: String) {
        mutable.update { it.copy(error = message) }
    }

    fun clearError() {
        mutable.update { it.copy(error = "", notice = "") }
    }

    fun pin(full: String) {
        store.toggle("pins", full)
        mutable.update { it.copy(pins = store.pins()) }
    }

    fun wait(item: Item) {
        store.toggle("waiting", item.key)
        mutable.update { it.copy(waiting = store.waiting()) }
    }

    fun home(page: Page) {
        if (mutable.value.writing) return
        flushDraft()
        readJob?.cancel()
        operationId++
        history.clear()
        mutable.update {
            it.copy(
                route = Route(page),
                tab = page,
                page = 1,
                items = emptyList(),
                detail = null,
                editor = null,
                notice = "",
                error = "",
            )
        }
        refresh()
    }

    fun back() {
        if (mutable.value.writing) return
        flushDraft()
        readJob?.cancel()
        operationId++
        mutable.value =
            if (history.isNotEmpty())
                history
                    .removeAt(history.lastIndex)
                    .copy(busy = false, writing = false, editor = null)
            else mutable.value.copy(route = Route(mutable.value.tab), editor = null, busy = false)
    }

    private fun go(route: Route) {
        flushDraft()
        history.add(mutable.value.copy(busy = false, writing = false))
        mutable.update {
            it.copy(
                route = route,
                page = 1,
                more = false,
                detail = null,
                document = null,
                entries = emptyList(),
                editor = null,
                notice = "",
                error = "",
            )
        }
    }

    fun openRepo(full: String) {
        if (!validRepo(full)) return
        run {
            val repo = repository.repo(full)
            store.visit(full)
            go(Route(Page.FILES, full, repo.defaultBranch))
            mutable.update {
                it.copy(selectedRepo = repo, repoKind = "files", recent = store.recent())
            }
            loadFiles()
        }
    }

    fun directory(path: String) {
        go(mutable.value.route.copy(page = Page.FILES, path = path))
        mutable.update { it.copy(repoKind = "files") }
        refresh()
    }

    fun openFile(path: String) {
        go(mutable.value.route.copy(page = Page.FILE, path = path))
        refresh()
    }

    fun chooseBranch(branch: String) {
        go(mutable.value.route.copy(page = Page.FILES, branch = branch, path = ""))
        mutable.update { it.copy(repoKind = "files") }
        refresh()
    }

    fun loadBranches(more: Boolean = false) {
        run {
            val s = mutable.value
            val page = if (more) s.branchesPage + 1 else 1
            val result = repository.branches(s.route.repo, page)
            mutable.update {
                it.copy(
                    branches = (if (more) it.branches else emptyList()) + result.values,
                    branchesMore = result.more,
                    branchesPage = page,
                )
            }
        }
    }

    fun repoKind(kind: String) {
        mutable.update { it.copy(repoKind = kind, page = 1, items = emptyList()) }
        refresh()
    }

    fun itemState(value: String) {
        mutable.update { it.copy(itemState = value, page = 1) }
        refresh()
    }

    fun searchRepo(query: String) {
        mutable.update { it.copy(repoQuery = query, page = 1) }
        run { loadRepos(1) }
    }

    private suspend fun loadRepos(page: Int) {
        val query = mutable.value.repoQuery
        val result =
            if (query.isBlank()) repository.repositories(page)
            else repository.searchRepos(query, page)
        mutable.update {
            it.copy(
                repos =
                    ((if (page > 1) it.repos else emptyList()) + result.values).distinctBy { r ->
                        r.fullName
                    },
                page = page,
                more = result.more,
                notice = result.note,
            )
        }
    }

    private suspend fun loadFiles() {
        val s = mutable.value
        val r = s.route
        if (s.repoKind == "files") {
            val result = repository.entries(r.repo, r.branch, r.path)
            mutable.update { it.copy(entries = result.values, notice = result.note, more = false) }
        } else {
            val result = repository.items(r.repo, s.repoKind, s.page, s.itemState)
            mutable.update {
                it.copy(
                    items =
                        ((if (s.page > 1) it.items else emptyList()) + result.values).distinctBy {
                            item ->
                            item.key
                        },
                    more = result.more,
                    notice = result.note,
                )
            }
        }
    }

    fun openItem(item: Item) {
        if (mutable.value.writing) return
        go(Route(Page.ITEM, item.repo, number = item.number))
        mutable.update { it.copy(detail = Detail(item)) }
        run {
            val detail = repository.detail(item)
            mutable.update { it.copy(detail = detail) }
        }
    }

    fun deepLink(value: String) {
        val target = githubTarget(value)
        if (target == null) {
            mutable.update { it.copy(error = "このリンクはアプリ内では開けません。repo、Issue、PRのリンクに対応しています。") }
            return
        }
        if (mutable.value.login.isBlank()) {
            pendingLink = target
            mutable.update { it.copy(notice = "接続後にリンクを開きます") }
            return
        }
        if (target.number == 0) openRepo(target.repo)
        else openItem(Item(target.repo, target.number, "読込中", "", "", target.isPr, "open", ""))
    }

    private suspend fun loadDeck(page: Int) {
        val user = mutable.value.login
        val queries =
            listOf(
                "is:issue is:open assignee:$user",
                "is:pr is:open author:$user",
                "is:pr is:open review-requested:$user",
            )
        val results = queries.map { repository.searchItems(it, page) }
        val values =
            ((if (page > 1) mutable.value.items else emptyList()) + results.flatMap { it.values })
                .distinctBy { it.key }
        mutable.update {
            it.copy(
                items = values,
                deckFetchedAt = it.cacheTime ?: System.currentTimeMillis(),
                page = page,
                more = results.any { r -> r.more },
                notice =
                    "取得済み ${values.size}件 · 詳細の自動確認は先頭12PR。未確認は候補にしません。" +
                        results.map { r -> r.note }.filter { t -> t.isNotEmpty() }.joinToString(),
            )
        }
        for (item in values.filter { it.isPr }.take(12)) {
            try {
                val detail = repository.detail(item)
                mutable.update { it.copy(deckDetails = it.deckDetails + (item.key to detail)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.code == 401) throw e
            }
        }
    }

    fun refresh() {
        if (mutable.value.login.isBlank() || mutable.value.route.page == Page.EDITOR) return
        run {
            val s = mutable.value
            when (s.route.page) {
                Page.REPOS -> loadRepos(s.page)
                Page.FILES -> loadFiles()
                Page.FILE -> {
                    val d = repository.document(s.route.repo, s.route.branch, s.route.path)
                    mutable.update { it.copy(document = d) }
                }
                Page.ITEM -> {
                    s.detail?.item?.let { item ->
                        val detail = repository.detail(item)
                        mutable.update {
                            it.copy(
                                detail = detail,
                                deckDetails = it.deckDetails + (item.key to detail),
                            )
                        }
                    }
                }
                Page.DECK -> loadDeck(s.page)
                Page.INBOX -> {
                    check(s.notificationsSupported) {
                        "この認証方式はGitHubの通知APIに対応していません。GitHubの通知をブラウザで開けます。"
                    }
                    val result = repository.notifications(s.page)
                    mutable.update {
                        it.copy(
                            notifications =
                                ((if (s.page > 1) it.notifications else emptyList()) +
                                        result.values)
                                    .distinctBy { n -> n.id },
                            more = result.more,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    fun more() {
        mutable.update { it.copy(page = it.page + 1) }
        refresh()
    }

    fun beginEdit(
        operation: String = "edit",
        uploadedName: String = "",
        uploadedText: String = "",
    ) {
        val s = mutable.value
        val d = s.document
        if (operation != "new" && (d?.text == null)) return
        val path =
            if (operation == "new")
                (s.route.path.takeIf { s.route.page == Page.FILES && it.isNotEmpty() }?.plus('/')
                    ?: "") + uploadedName.ifEmpty { "new-file.md" }
            else d!!.path
        val fresh =
            Editor(
                s.route.repo,
                s.route.branch,
                if (operation == "new") "" else path,
                path,
                d?.sha.takeIf { operation != "new" }.orEmpty(),
                d?.text.takeIf { operation != "new" }.orEmpty(),
                if (operation == "delete") ""
                else if (operation == "new") uploadedText else d!!.text!!,
                operation,
                repository.newBranch(),
                "${mapOf("edit" to "更新","new" to "追加","rename" to "移動","delete" to "削除")[operation]}: $path",
            )
        go(s.route.copy(page = Page.EDITOR))
        val restored = store.restore(fresh)
        mutable.update {
            it.copy(
                editor = restored,
                notice =
                    if (restored.originalSha != fresh.originalSha)
                        "下書きを復元しました。元ファイルが更新されているため、最新との差分を確認してください。"
                    else "",
            )
        }
    }

    fun editor(change: (Editor) -> Editor) {
        val old = mutable.value.editor ?: return
        val new = change(old)
        mutable.update { it.copy(editor = new) }
        draftJob?.cancel()
        draftJob =
            viewModelScope.launch(Dispatchers.IO) {
                delay(350)
                draftMutex.withLock { store.draft(new) }
            }
    }

    fun discardEditor() {
        flushDraft()
        mutable.value.editor?.let(store::discard)
        mutable.update { it.copy(editor = null) }
        back()
    }

    fun rebaseEditor() {
        run {
            val e = mutable.value.editor ?: return@run
            val latest = repository.document(e.repo, e.base, e.oldPath, cached = false)
            check(latest.text != null) { "最新ファイルを編集できません" }
            val next = e.copy(original = latest.text!!, originalSha = latest.sha)
            store.draft(next)
            mutable.update { it.copy(editor = next, notice = "最新の内容と下書きの差分を確認してください。まだ保存していません。") }
        }
    }

    private suspend fun showSaved(item: Item, message: String) {
        mutable.update {
            it.copy(
                route = Route(Page.ITEM, item.repo, number = item.number),
                detail = Detail(item),
                notice = message,
                editor = null,
            )
        }
        try {
            val detail = repository.detail(item)
            mutable.update {
                it.copy(detail = detail, deckDetails = it.deckDetails + (item.key to detail))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiError && e.code == 401) throw e
            mutable.update { it.copy(notice = "$message。詳細の再取得に失敗しました。更新で確認できます。") }
        }
    }

    fun saveEditor() {
        val e = mutable.value.editor ?: return
        flushDraft()
        run(true) {
            check(mutable.value.pending == null) { "前の保存結果を確認するか、続行待ちを解除してください" }
            val p =
                repository.propose(e) { prepared ->
                    store.pending(prepared)
                    mutable.update { it.copy(pending = prepared) }
                }
            store.discard(e)
            mutable.update {
                it.copy(
                    editor = null,
                    route = Route(Page.FILES, p.repo, p.head, ""),
                    repoKind = "files",
                    notice = "ブランチを作成しました。PR作成を確認しています。",
                )
            }
            val item = repository.createPr(p)
            store.pending(null)
            mutable.update { it.copy(pending = null) }
            showSaved(item, "PRを作成しました")
        }
    }

    fun abandonPending() {
        if (mutable.value.busy) return
        store.pending(null)
        mutable.update { it.copy(pending = null, notice = "続行待ちを解除しました。作成済みのブランチやPRはGitHubに残ります。") }
    }

    fun retryPending() {
        val p = mutable.value.pending ?: return
        run(true) {
            val item = repository.createPr(p)
            mutable.value.editor
                ?.takeIf { it.repo == p.repo && it.target == p.head }
                ?.let(store::discard)
            store.pending(null)
            mutable.update { it.copy(pending = null) }
            showSaved(item, "PRを確認しました")
        }
    }

    fun issue(
        title: String,
        body: String,
        labels: List<String>,
        assignees: List<String>,
        number: Int = 0,
    ) {
        val repo = mutable.value.route.repo
        run(true) {
            val item = repository.issue(repo, title, body, number, labels, assignees)
            clearFormDraft(formKey(if (number == 0) "new-issue" else "edit-issue", repo, number))
            showSaved(item, "Issueを保存しました")
        }
    }

    fun comment(body: String) {
        val item = mutable.value.detail?.item ?: return
        run(true) {
            repository.comment(item, body)
            clearFormDraft(formKey("comment", item.repo, item.number))
            showSaved(item, "コメントを追加しました")
        }
    }

    fun state(value: String) {
        val item = mutable.value.detail?.item ?: return
        run(true) {
            repository.itemState(item, value)
            showSaved(item.copy(state = value), "状態を更新しました")
        }
    }

    fun review(event: String, body: String) {
        val detail = mutable.value.detail ?: return
        run(true) {
            repository.review(detail, event, body, mutable.value.login)
            clearFormDraft(formKey("review", detail.item.repo, detail.item.number))
            showSaved(detail.item, "レビューを送信しました")
        }
    }

    fun inline(path: String, line: Int, side: String, body: String) {
        val detail = mutable.value.detail ?: return
        run(true) {
            repository.inlineComment(detail, path, line, side, body)
            clearFormDraft(formKey("inline", detail.item.repo, detail.item.number) + "|$path|$line|$side")
            showSaved(detail.item, "差分にコメントしました")
        }
    }

    fun merge(method: String) {
        val detail = mutable.value.detail ?: return
        run(true) {
            repository.merge(detail, method)
            showSaved(detail.item.copy(state = "merged"), "マージが完了しました")
        }
    }

    fun read(id: String) {
        run(true) {
            repository.markRead(id)
            mutable.update {
                it.copy(
                    notifications =
                        it.notifications.map { n -> if (n.id == id) n.copy(unread = false) else n }
                )
            }
        }
    }

    fun newPr(base: String, head: String, title: String, body: String) {
        val repo = mutable.value.route.repo
        run(true) {
            require(validBranch(base) && validBranch(head) && base != head && title.isNotBlank())
            check(mutable.value.pending == null) { "前の保存結果を確認するか、続行待ちを解除してください" }
            val p = PendingPr(repo, base, head, title, body = body)
            store.pending(p)
            mutable.update { it.copy(pending = p) }
            val item = repository.createPr(p)
            store.pending(null)
            mutable.update { it.copy(pending = null) }
            clearFormDraft(formKey("new-pr", repo, 0))
            showSaved(item, "PRを作成しました")
        }
    }

    fun resolvePost() {
        val pending = store.pendingPost() ?: return
        run(true, recovery = true) {
            val result = repository.posts.resolve()
            if (result == null) {
                mutable.update { it.copy(notice = "投稿済みか確認できていません。GitHubで対象を確認してください。再送はしていません。") }
            } else {
                clearFormDraft(pending.draftKey)
                val item = if (pending.number == 0) parseItem(result, pending.repo) else {
                    Item(pending.repo, pending.number, "投稿確認済み", "", pending.actor, pending.isPr, "open", "")
                }
                showSaved(item, "GitHubに投稿済みであることを確認しました。再送はしていません。")
            }
        }
    }

    fun abandonPost() {
        if (mutable.value.busy) return
        store.pendingPost(null)
        mutable.update { it.copy(pendingPost = null, notice = "照会待ちを解除しました。GitHubへの投稿は削除していません。") }
    }

    fun notification(n: Notification) {
        run(n.unread) {
            if (n.unread) repository.markRead(n.id)
            val item = Item(n.repo, n.number, "読込中", "", "", n.kind == "PullRequest", "open", "")
            val detail = repository.detail(item)
            go(Route(Page.ITEM, n.repo, number = n.number))
            mutable.update {
                it.copy(
                    detail = detail,
                    notifications =
                        it.notifications.map { old ->
                            if (old.id == n.id) old.copy(unread = false) else old
                        },
                )
            }
        }
    }

    fun allRead() {
        run(true) {
            val complete = repository.markAllRead()
            mutable.update {
                it.copy(
                    notifications =
                        if (complete) it.notifications.map { n -> n.copy(unread = false) }
                        else it.notifications,
                    notice =
                        if (complete) "既読にしました" else "GitHubが既読処理を受け付けました。処理中のため、更新で結果を確認してください。",
                )
            }
        }
    }
}
