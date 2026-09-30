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
}
