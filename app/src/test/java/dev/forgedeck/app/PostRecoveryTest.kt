package dev.forgedeck.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostRecoveryTest {
    private fun pending(id: String = "b32476c2-7b05-49b1-8b52-cf9d65ba50fb") = PendingPost(id, "comment", "owner/repo", 1, false, "repos/owner/repo/issues/1/comments", "{\"body\":\"Hello\"}", "me")
    private fun found(p: PendingPost, actor: String = "me") = JSONObject().put("id", 42).put("body", "Hello\n\n${p.marker}").put("user", JSONObject().put("login", actor))

    @Test fun failureAfterServerSaveIsResolvedWithoutAnotherPost() = runBlocking {
        val server = MockWebServer()
        var saved = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    saved = JSONObject(request.body.readUtf8()).getString("body")
                    return MockResponse().setResponseCode(500)
                }
                return MockResponse().setBody(JSONArray().put(JSONObject().put("id", 42).put("body", saved).put("user", JSONObject().put("login", "me"))).toString())
            }
        }
        server.start()
        try {
            val journal = MemoryPostJournal()
            val posts = RecoverablePosts(GitHubApi({ "test" }, base = server.url("/")), journal) { "me" }
            assertEquals(42, posts.send("comment", "owner/repo", 1, false, pending().path, JSONObject().put("body", "Hello")).getInt("id"))
            assertEquals(2, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertNull(journal.pendingPost())
        } finally { server.shutdown() }
    }

    @Test fun missingResultIsNotEvidenceThatSendingAgainIsSafe() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            val journal = MemoryPostJournal()
            val api = GitHubApi({ "test" }, base = server.url("/"))
            val payload = JSONObject().put("body", "Hello")
            val posts = RecoverablePosts(api, journal) { "me" }
            repeat(2) {
                try { posts.send("comment", "owner/repo", 1, false, pending().path, payload); fail("Must remain unconfirmed") }
                catch (_: UnconfirmedPost) { }
            }
            assertNotNull(journal.pendingPost())
            assertEquals(3, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            repeat(2) { assertEquals("GET", server.takeRequest().method) }
        } finally { server.shutdown() }
    }

    @Test fun sameTextAndAnotherAuthorDoNotConfirmOurOperation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val p = pending()
            val journal = MemoryPostJournal().apply { pendingPost(p) }
            server.enqueue(MockResponse().setBody(JSONArray().put(found(p, "someone-else")).put(JSONObject().put("id", 3).put("body", "Hello").put("user", JSONObject().put("login", "me"))).toString()))
            assertNull(RecoverablePosts(GitHubApi({ "test" }, base = server.url("/")), journal) { "me" }.resolve())
            assertNotNull(journal.pendingPost())
        } finally { server.shutdown() }
    }

    @Test fun recoveryChecksTheNextPage() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val p = pending()
            val journal = MemoryPostJournal().apply { pendingPost(p) }
            server.enqueue(MockResponse().setBody(JSONArray().apply { repeat(100) { put(JSONObject().put("id", it + 1).put("body", "other")) } }.toString()))
            server.enqueue(MockResponse().setBody(JSONArray().put(found(p)).toString()))
            assertNotNull(RecoverablePosts(GitHubApi({ "test" }, base = server.url("/")), journal) { "me" }.resolve())
            server.takeRequest()
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("page"))
            assertNull(journal.pendingPost())
        } finally { server.shutdown() }
    }

    @Test fun permissionFailureIsDefinitiveAndDoesNotTriggerResultLookup() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Forbidden\"}"))
            val journal = MemoryPostJournal()
            try { RecoverablePosts(GitHubApi({ "test" }, base = server.url("/")), journal) { "me" }.send("comment", "owner/repo", 1, false, pending().path, JSONObject().put("body", "Hello")); fail() }
            catch (e: ApiError) { assertEquals(403, e.code) }
            assertNull(journal.pendingPost())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun stateWriteIsConfirmedByGetAfterAnAmbiguousResponse() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("{\"state\":\"closed\"}"))
            GitHubRepository(GitHubApi({ "test" }, base = server.url("/"))).itemState(Item("owner/repo", 1, "", "", "me", false, "open", ""), "closed")
            assertEquals("PATCH", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun ourMarkerIsHiddenButOtherHtmlIsPreserved() {
        assertEquals("Hello", cleanPostMarkers("Hello\n\n${pending().marker}"))
        assertEquals("<!-- normal comment -->", cleanPostMarkers("<!-- normal comment -->"))
    }
}
