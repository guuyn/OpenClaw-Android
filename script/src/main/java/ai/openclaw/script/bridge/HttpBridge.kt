package ai.openclaw.script.bridge

import ai.openclaw.script.CapabilityBridge
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.ObjectBindingScope
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

    /**
     * QuickJS 绑定: 注册 JS 函数到 ObjectBindingScope DSL 块。
     * 复用已有的 handle() 逻辑，将 JS 参数构造为 JSON 后分发。
     */
    override fun registerBindings(dsl: ObjectBindingScope) {
        dsl.apply {
            function("get", FunctionBinding { args ->
                val url = args.getOrNull(0) as? String ?: return@FunctionBinding """{"error":"Missing url"}"""
                handle("http.get", """{"url":${jsonEscape(url)}}""")
            })
            function("post", FunctionBinding { args ->
                val url = args.getOrNull(0) as? String ?: return@FunctionBinding """{"error":"Missing url"}"""
                val body = args.getOrNull(1) as? String ?: ""
                handle("http.post", """{"url":${jsonEscape(url)},"body":${jsonEscape(body)}}""")
            })
        }
    }

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
