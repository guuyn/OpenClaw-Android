package ai.openclaw.android.feishu

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.internal.http.HttpMethod.permitsRequestBody
import okio.ByteString
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpFeishuClient(private val httpClient: OkHttpClient) : FeishuClient {
    private var webSocket: WebSocket? = null
    private var eventListener: ((FeishuEvent) -> Unit)? = null
    private var isConnected = false
    private var appId: String = ""
    private var appSecret: String = ""
    private var accessToken: String = ""
    private var tokenExpireAt: Long = 0L // Token 过期时间戳

    // 断线重连
    private var autoReconnect = true
    private var reconnectDelayMs = 3000L
    private val maxReconnectDelayMs = 30000L
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun connect(appId: String, appSecret: String) {
        this.appId = appId
        this.appSecret = appSecret
        
        // 获取访问令牌
        refreshToken()
        
        val request = Request.Builder()
            .url("wss://open.feishu.cn/open-apis/bot/v2/ws/")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.i("FeishuClient", "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = json.decodeFromString<FeishuEvent>(text)
                    eventListener?.invoke(event)
                } catch (e: Exception) {
                    Log.e("FeishuClient", "Failed to parse event: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 处理二进制消息（如果需要）
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.i("FeishuClient", "WebSocket closing: $code - $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                android.util.Log.w("FeishuClient", "WebSocket failed: ${t.message}")
                scheduleReconnect()
            }
        })
    }
    
    override fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        isConnected = false
        webSocket = null
    }
    
    override fun isConnected(): Boolean {
        return isConnected && webSocket != null
    }
    
    override fun setEventListener(listener: (FeishuEvent) -> Unit) {
        this.eventListener = listener
    }
    
    override suspend fun sendMessage(chatId: String, content: String): Result<String> {
        // 确保 Token 有效
        if (!ensureTokenValid()) {
            return Result.failure(Exception("Token refresh failed"))
        }

        return try {
            val messageBody = """
                {
                    "msg_type": "text",
                    "content": {
                        "text": "$content"
                    },
                    "chat_id": "$chatId"
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id")
                .post(RequestBody.create("application/json".toMediaType(), messageBody))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .build()
            
            val response = httpClient.newCall(request).await()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Result.success(responseBody ?: "")
            } else {
                Result.failure(Exception("Failed to send message: ${response.code} - ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun uploadFile(chatId: String, filePath: String): Result<String> {
        // 确保 Token 有效
        if (!ensureTokenValid()) {
            return Result.failure(Exception("Token refresh failed"))
        }

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(Exception("File does not exist: $filePath"))
            }
            
            val fileBody = RequestBody.create("application/octet-stream".toMediaType(), file)
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_type", "stream") // 可根据实际文件类型调整
                .addFormDataPart("file_name", file.name)
                .addFormDataPart("file", file.name, fileBody)
                .build()
            
            val request = Request.Builder()
                .url("https://open.feishu.cn/open-apis/im/v1/files")
                .post(multipartBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "multipart/form-data")
                .build()
            
            val response = httpClient.newCall(request).await()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful) {
                // 解析响应获取file_key
                val fileKey = extractFileKey(responseBody)
                if (fileKey != null) {
                    Result.success(fileKey)
                } else {
                    Result.failure(Exception("Could not extract file_key from response: $responseBody"))
                }
            } else {
                Result.failure(Exception("Failed to upload file: ${response.code} - ${response.message}, body: $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 刷新 Token（带过期检测）
     * @return 是否刷新成功
     */
    private fun refreshToken(): Boolean {
        return try {
            val requestBody = RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                """{
                    "app_id": "$appId",
                    "app_secret": "$appSecret"
                }"""
            )

            val request = Request.Builder()
                .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .post(requestBody)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                // 解析 tenant_access_token 和 expire
                val tokenRegex = Regex(""""tenant_access_token"\s*:\s*"([^"]+)"""")
                val expireRegex = Regex(""""expire"\s*:\s*(\d+)""")

                val tokenMatch = tokenRegex.find(responseBody ?: "")
                val expireMatch = expireRegex.find(responseBody ?: "")

                if (tokenMatch != null) {
                    accessToken = tokenMatch.groupValues[1]
                    // 飞书 Token 默认 2 小时有效，提前 5 分钟刷新
                    val expireSeconds = expireMatch?.groupValues?.get(1)?.toLongOrNull() ?: 7200
                    tokenExpireAt = System.currentTimeMillis() + (expireSeconds - 300) * 1000
                    android.util.Log.d("FeishuClient", "Token refreshed, expires in ${expireSeconds}s")
                    true
                } else {
                    android.util.Log.e("FeishuClient", "Failed to parse token")
                    false
                }
            } else {
                android.util.Log.e("FeishuClient", "Token refresh failed: ${response.code}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("FeishuClient", "Token refresh exception: ${e.message}")
            false
        }
    }

    /**
     * 确保 Token 有效，过期则自动刷新
     */
    private fun ensureTokenValid(): Boolean {
        if (accessToken.isEmpty() || System.currentTimeMillis() >= tokenExpireAt) {
            return refreshToken()
        }
        return true
    }

    /**
     * 断线重连（指数退避）
     */
    private fun scheduleReconnect() {
        if (!autoReconnect) return

        val delay = reconnectDelayMs
        reconnectDelayMs = minOf(reconnectDelayMs * 2, maxReconnectDelayMs)

        android.util.Log.d("FeishuClient", "Scheduling reconnect in ${delay}ms")
        reconnectHandler.postDelayed({
            if (appId.isNotEmpty() && appSecret.isNotEmpty()) {
                android.util.Log.d("FeishuClient", "Attempting reconnect...")
                refreshToken()
                connect(appId, appSecret)
            }
        }, delay)
    }
    
    private fun extractFileKey(responseBody: String?): String? {
        // 简化的JSON解析来提取file_key
        // 在实际实现中应该使用JSON库进行正确解析
        if (responseBody != null) {
            val fileKeyRegex = Regex("\"file_key\"\\s*:\\s*\"([^\"]+)\"")
            val matchResult = fileKeyRegex.find(responseBody)
            return matchResult?.groupValues?.get(1)
        }
        return null
    }
}

// 扩展函数：让OkHttpClient支持suspend调用
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}