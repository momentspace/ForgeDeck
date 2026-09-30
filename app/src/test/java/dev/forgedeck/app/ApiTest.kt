package dev.forgedeck.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiTest {
    @Test fun authorizationNeverFollowsRedirects()=runBlocking {
        val server=MockWebServer();server.start()
        try{server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.test/"));val api=GitHubApi({"test-token"},base=server.url("/"));try{api.obj("user");fail("Redirect should fail")}catch(e:ApiError){assertEquals(302,e.code)};assertEquals(1,server.requestCount);assertEquals("Bearer test-token",server.takeRequest().getHeader("Authorization"))}finally{server.shutdown()}
    }
    @Test fun permissionErrorsDoNotReturnStalePrivateData()=runBlocking {
        val server=MockWebServer();server.start()
        try{server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Forbidden\"}"));val api=GitHubApi({"test"},cacheGet={"{\"private\":true}" to 1L},base=server.url("/"));try{api.obj("user/repos",cached=true);fail("403 must be visible")}catch(e:ApiError){assertEquals(403,e.code)}}finally{server.shutdown()}
    }
    @Test fun offlineGetCanReturnTimestampedCache()=runBlocking {
        val server=MockWebServer();server.start();val base=server.url("/");server.shutdown();var stale=false
        val api=GitHubApi({"test"},cacheGet={"{\"name\":\"cached\"}" to 10L},stale={stale=true},base=base)
        assertEquals("cached",api.obj("repos/owner/repo",cached=true).getString("name"));assertTrue(stale)
    }
    @Test fun conflictingEditStopsBeforeAnyWrite()=runBlocking {
        val server=MockWebServer();server.start()
        try{
            listOf("{\"full_name\":\"owner/repo\",\"permissions\":{\"push\":true}}","{\"object\":{\"sha\":\"base-head\"}}","{\"tree\":{\"sha\":\"base-tree\"}}","{\"sha\":\"newer-file-sha\"}").forEach{server.enqueue(MockResponse().setBody(it))}
            val repo=GitHubRepository(GitHubApi({"test"},base=server.url("/")))
            val editor=Editor("owner/repo","main","README.md","README.md","original-sha","old","new",target="forgedeck/test",title="Update")
            try{repo.propose(editor);fail("Conflict must stop write")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("更新"))}
            assertEquals(4,server.requestCount);repeat(4){assertEquals("GET",server.takeRequest().method)}
        }finally{server.shutdown()}
    }
    @Test fun ownPrCannotBeApproved()=runBlocking {
        val repo=GitHubRepository(GitHubApi({"test"}))
        val detail=Detail(Item("owner/repo",2,"Title","","me",true,"open",""))
        try{repo.review(detail,"APPROVE","","me");fail("Own approval must stop before network")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("自分"))}
    }

    @Test fun moveCreatesOneCommitAndNeverChangesBaseRef()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            listOf(
                "{\"full_name\":\"owner/repo\",\"permissions\":{\"push\":true}}",
                "{\"object\":{\"sha\":\"base-head\"}}",
                "{\"tree\":{\"sha\":\"base-tree\"}}",
                "{\"sha\":\"file-sha\"}",
                "{\"tree\":[{\"path\":\"run.sh\",\"mode\":\"100755\",\"sha\":\"file-sha\"}]}"
            ).forEach{server.enqueue(MockResponse().setBody(it))}
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("{\"sha\":\"new-tree\"}"))
            server.enqueue(MockResponse().setBody("{\"sha\":\"new-commit\"}"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("{\"ref\":\"refs/heads/forgedeck/test\"}"))
            val repository=GitHubRepository(GitHubApi({"test"},base=server.url("/")))
            val e=Editor("owner/repo","main","run.sh","tools/run.sh","file-sha","old","new",operation="rename",target="forgedeck/test",title="Move")
            var prepared:PendingPr?=null
            val pending=repository.propose(e){prepared=it}
            assertEquals("new-commit",prepared!!.commitSha);assertEquals(pending,prepared)
            val requests=(1..10).map{server.takeRequest()}
            assertTrue(requests.none{it.method=="PATCH"||it.method=="PUT"})
            val tree=JSONObject(requests[6].body.readUtf8());assertEquals("base-tree",tree.getString("base_tree"))
            val entries=tree.getJSONArray("tree");assertEquals(2,entries.length());assertTrue(entries.getJSONObject(0).isNull("sha"));assertEquals("100755",entries.getJSONObject(1).getString("mode"))
            val ref=JSONObject(requests.last().body.readUtf8());assertEquals("refs/heads/forgedeck/test",ref.getString("ref"));assertEquals("new-commit",ref.getString("sha"))
        } finally {server.shutdown()}
    }
    @Test fun pendingPrFindsExistingPrBeforePostingAgain()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"object\":{\"sha\":\"saved-commit\"}}"))
            server.enqueue(MockResponse().setBody("[{\"number\":9,\"title\":\"Saved\",\"state\":\"open\",\"head\":{}}]"))
            val repository=GitHubRepository(GitHubApi({"test"},base=server.url("/")))
            assertEquals(9,repository.createPr(PendingPr("owner/repo","main","forgedeck/test","Saved","saved-commit")).number)
            assertEquals(2,server.requestCount);repeat(2){assertEquals("GET",server.takeRequest().method)}
        } finally {server.shutdown()}
    }
}
