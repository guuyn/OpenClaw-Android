package ai.openclaw.script.bridge

import ai.openclaw.script.CapabilityBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP 请求 Bridge
 *
 * JS API:
 *   http.get(url)     → {status, body}
 *   http.post(url, body) → {status, body}
 */
class HttpBridge : CapabilityBridge {

    override val name: String = "http"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun getJsPrototype(): String = """
        var http = {
            get: function(url) { return JSON.parse(__nativeCall('http.get', JSON.stringify({url: url}))); },
            post: function(url, body) { return JSON.parse(__nativeCall('http.post', JSON.stringify({url: url, body: body}))); }
        };
    """.trimIndent()

    fun handle(method: String, argsJson: String): String {
        return try {
            when (method) {
                "http.get" -> httpGet(argsJson)
                "http.post" -> httpPost(argsJson)
                else -> """{"error":"Unknown method: $method"}"""
            }
        } catch (e: Exception) {
            """{"error":"${jsonEscape(e.message ?: "Unknown error")}"}"""
        }
    }

    private fun httpGet(argsJson: String): String {
        val url = extractField(argsJson, "url")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            return """{"status":${response.code},"body":${jsonEscape(body)}}"""
        }
    }

    private fun httpPost(argsJson: String): String {
        val url = extractField(argsJson, "url")
        val body = extractField(argsJson, "body")
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            return """{"status":${response.code},"body":${jsonEscape(responseBody)}}"""
        }
    }
}
