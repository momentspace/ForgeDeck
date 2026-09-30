package dev.forgedeck.app

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Real ViewModel + Keystore/draft storage, with intercepted HTTP and no GitHub writes. */
class WriteFailureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun saveFailure(code: Int) {
        val store = LocalStore(instrumentation.targetContext)
        store.logout()
        store.saveToken("local-test", "sample")
        val writes = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            if (req.method != "GET") writes.add(path)
            val (status, body) = when {
                path == "/user/repos" -> 200 to "[]"
                path == "/repos/sample/ForgeDeck" -> 200 to """{"full_name":"sample/ForgeDeck","default_branch":"master","permissions":{"push":true}}"""
                path == "/repos/sample/ForgeDeck/contents" -> 200 to "[]"
                path.endsWith("/git/ref/heads/master") -> 200 to """{"object":{"sha":"base-commit"}}"""
                path.endsWith("/git/commits/base-commit") -> 200 to """{"tree":{"sha":"base-tree"}}"""
                path.endsWith("/contents/test.txt") -> 404 to """{"message":"Not Found"}"""
                path.endsWith("/git/trees") && req.method == "POST" -> if (code == 500) 500 to """{"message":"Disconnected result"}""" else 201 to """{"sha":"new-tree"}"""
                path.endsWith("/git/commits") -> 201 to """{"sha":"new-commit"}"""
                path.contains("/git/ref/heads/forgedeck/") -> 404 to """{"message":"Not Found"}"""
                path.endsWith("/git/refs") -> 403 to """{"message":"Branch creation blocked"}"""
                else -> 404 to """{"message":"Unexpected endpoint"}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        lateinit var vm: ForgeViewModel
        instrumentation.runOnMainSync { vm = ForgeViewModel(store, GitHubApi({ "local-test" }, client = client)) }
        waitUntil { !vm.ui.value.busy }
        instrumentation.runOnMainSync { vm.openRepo("sample/ForgeDeck") }
        waitUntil { vm.ui.value.route.page == Page.FILES && !vm.ui.value.busy }
        instrumentation.runOnMainSync {
            vm.beginEdit("new", "test.txt", "Original")
            vm.editor { it.copy(text = "Unsaved work survives") }
            vm.saveEditor()
        }
        waitUntil { vm.ui.value.error.isNotBlank() && !vm.ui.value.busy }
        val editor = vm.ui.value.editor!!
        assertEquals("Unsaved work survives", editor.text)
        assertEquals("Unsaved work survives", LocalStore(instrumentation.targetContext).restore(editor.copy(text = "Original")).text)
        assertFalse(writes.any { it.contains("pulls") })
        assertEquals(1, writes.count { it.endsWith("/git/trees") })
        if (code == 403) {
            assertEquals("new-commit", vm.ui.value.pending?.commitSha)
            assertEquals(1, writes.count { it.endsWith("/git/refs") })
        }
        instrumentation.runOnMainSync { vm.logout() }
        client.dispatcher.executorService.shutdown()
    }

    @Test fun branchPermissionFailureKeepsEditorAndPreparedCommit() = saveFailure(403)
    @Test fun serverFailureDoesNotRetryOrLoseEditor() = saveFailure(500)

    @Test fun unauthorizedRefreshReturnsToLoginWithoutErasingFormDraft() {
        val store = LocalStore(instrumentation.targetContext)
        store.logout()
        store.saveToken("local-test", "sample")
        val key = formKey("new-issue", "sample/ForgeDeck", 0)
        store.formDraft(key, "Issue draft")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("fixture")
                .body("""{"message":"Bad credentials"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        lateinit var vm: ForgeViewModel
        instrumentation.runOnMainSync { vm = ForgeViewModel(store, GitHubApi({ "local-test" }, client = client)) }
        waitUntil { vm.ui.value.error.isNotBlank() && !vm.ui.value.busy }
        assertEquals("", vm.ui.value.login)
        assertEquals("", store.token())
        assertEquals("Issue draft", LocalStore(instrumentation.targetContext).formDraft(key))
        instrumentation.runOnMainSync { vm.logout() }
        client.dispatcher.executorService.shutdown()
    }

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(20) }
    }
}
