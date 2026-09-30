package dev.forgedeck.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiError(val code: Int, message: String) : IOException(message)
data class ApiReply(val code:Int,val body:String)
class GitHubApi(
    private val token: () -> String,
    private val cachePut: (String,String) -> Unit = {_,_ ->},
    private val cacheGet: (String) -> Pair<String,Long>? = {null},
    private val stale: (Long) -> Unit = {},
    private val base: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
) {
    suspend fun request(method: String, path: String, body: JSONObject? = null, query: Map<String,String> = emptyMap(), cached: Boolean = false): String = reply(method,path,body,query,cached).body
    suspend fun reply(method: String, path: String, body: JSONObject? = null, query: Map<String,String> = emptyMap(), cached: Boolean = false): ApiReply {
        val url = base.newBuilder().apply { path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) };query.forEach { (k,v)-> addQueryParameter(k,v) } }.build()
        val key = url.toString()
        val req = Request.Builder().url(url).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","ForgeDeck/0.1").apply { if(token().isNotBlank()) header("Authorization","Bearer ${token()}") }.method(method, if(method in setOf("GET","HEAD")) null else (body?.toString() ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            val response = execute(req)
            return response.use {
                val text = it.body?.string().orEmpty()
                if(!it.isSuccessful && !(it.code==304 && method=="PUT" && path=="notifications")) {
                    val reason = runCatching { JSONObject(text).str("message") }.getOrDefault("").take(180).replace(token().takeIf { t -> t.isNotBlank() } ?: "\u0000","[redacted]")
                    val guide = when(it.code) { 401 -> "認証が失効しました。再接続してください。";403 -> if(it.header("X-GitHub-SSO")!=null) "OrganizationのSSO承認が必要です。" else "権限不足、Organizationの制限、またはAPI制限です。";404 -> "見つからないか、アクセス権がありません。";409,422 -> "状態が変更されたか、入力または権限に問題があります。";429 -> "API制限です。時間を置いて更新してください。";in 300..399 -> "リポジトリが移動しています。新しい場所を開いてください。";else -> "GitHubへの処理に失敗しました。" }
                    val limit = it.header("Retry-After")?.let { sec -> " ${sec}秒後に再試行できます。" } ?: it.header("X-RateLimit-Reset")?.let { reset -> " 制限解除の目安: ${reset}（UNIX秒）。" }.takeIf { response.header("X-RateLimit-Remaining")=="0" }.orEmpty()
                    throw ApiError(it.code,"$guide$limit\n$reason")
                }
                if(cached && method=="GET")cachePut(key,text)
                ApiReply(it.code,text)
            }
        } catch(error: IOException) {
            if(cached && method=="GET" && error !is ApiError) cacheGet(key)?.let { (text,time)->stale(time);return ApiReply(200,text) }
            if(method !in setOf("GET","HEAD") && path!="graphql" && error !is ApiError)throw IOException("送信結果を確認できませんでした。操作が成功している可能性があります。更新またはGitHubで確認してから再送してください。",error)
            throw error
        }
    }
    suspend fun obj(path:String, query:Map<String,String> = emptyMap(), cached:Boolean = false) = JSONObject(request("GET",path,query=query,cached=cached))
    suspend fun arr(path:String, query:Map<String,String> = emptyMap(), cached:Boolean = false) = JSONArray(request("GET",path,query=query,cached=cached))
    suspend fun post(path:String, body:JSONObject) = JSONObject(request("POST",path,body))
    private suspend fun execute(request:Request):Response = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) { if(continuation.isActive)continuation.resume(response) else response.close() }
        })
    }
}
