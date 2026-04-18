package ai.openclaw.android.model

import android.util.Log
import ai.openclaw.android.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Bailian (阿里百炼) Model Client Implementation
 *
 * Uses OpenAI-compatible API format with SSE streaming support.
 */
class BailianClient : ModelClient {

    companion object {
        private const val TAG = "BailianClient"
        private const val API_URL = "https://coding.dashscope.aliyuncs.com/v1/chat/completions"
        private const val MEDIA_TYPE_JSON = "application/json; charset=utf-8"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private var apiKey: String = ""
    private var model: String = "MiniMax-M2.5"

    override fun configure(provider: ModelProvider, apiKey: String, model: String, baseUrl: String) {
        this.apiKey = apiKey
        this.model = model
    }

    override suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>?
    ): Result<ModelResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== Starting chat request ===")
                Log.d(TAG, "API URL: $API_URL")
                Log.d(TAG, "Model: $model")
                Log.d(TAG, "API Key length: ${apiKey.length}")
                Log.d(TAG, "API Key prefix: ${if (apiKey.isNotEmpty()) apiKey.substring(0, minOf(8, apiKey.length)) + "..." else "EMPTY"}")
                Log.d(TAG, "Messages count: ${messages.size}")
                Log.d(TAG, "Tools count: ${tools?.size ?: 0}")

                // For Coding Plan service, build request manually to avoid serialization issues
                val modelClean = model.replace("bailian/", "")
                val messagesJson = json.encodeToString(messages)
                val toolsJson = if (tools != null && tools.isNotEmpty()) {
                    json.encodeToString(tools)
                } else {
                    ""
                }
                
                var requestBody = "{"
                requestBody += "\"model\":\"$modelClean\","
                requestBody += "\"messages\":$messagesJson"
                if (toolsJson.isNotEmpty()) {
                    requestBody += ",\"tools\":$toolsJson"
                }
                requestBody += ",\"temperature\":0.7"
                requestBody += ",\"max_tokens\":8192"
                requestBody += "}"

                Log.d(TAG, "=== FULL REQUEST DETAILS ===")
                Log.d(TAG, "URL: $API_URL")
                Log.d(TAG, "Method: POST")
                Log.d(TAG, "Headers:")
                Log.d(TAG, "  Authorization: Bearer ${if (apiKey.isNotEmpty()) apiKey.substring(0, minOf(8, apiKey.length)) + "..." else "EMPTY"}")
                Log.d(TAG, "  Content-Type: application/json")
                Log.d(TAG, "  User-Agent: OpenClaw-Android/1.0")
                Log.d(TAG, "  Accept: Not set for non-streaming")
                Log.d(TAG, "Full request body:")
                Log.d(TAG, requestBody)
                Log.d(TAG, "=== END REQUEST DETAILS ===")
                
                // Also log to app's LogManager for visibility in settings
                LogManager.shared.log("DEBUG", "BailianClient", "=== FULL REQUEST DETAILS ===")
                LogManager.shared.log("DEBUG", "BailianClient", "URL: $API_URL")
                LogManager.shared.log("DEBUG", "BailianClient", "Full request body: $requestBody")

                val body = requestBody.toRequestBody(MEDIA_TYPE_JSON.toMediaType())

                val httpRequest = Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "OpenClaw-Android/1.0")
                    .post(body)
                    .build()

                Log.d(TAG, "Sending request to model: $model")
                val startTime = System.currentTimeMillis()
                val response = httpClient.newCall(httpRequest).execute()
                val endTime = System.currentTimeMillis()
                Log.d(TAG, "HTTP request completed in ${endTime - startTime}ms")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "HTTP ${response.code} - Response body length: ${responseBody?.length ?: 0}")
                    Log.d(TAG, "Response body: ${responseBody?.take(500)}...")

                    if (responseBody.isNullOrEmpty()) {
                        Log.e(TAG, "Empty response body received!")
                        return@withContext Result.failure(Exception("Empty response from model"))
                    }

                    val modelResponse = json.decodeFromString<ModelResponse>(responseBody)
                    Log.d(TAG, "Successfully parsed model response")
                    Log.d(TAG, "Response choices: ${modelResponse.choices?.size ?: 0}")
                    Log.d(TAG, "Response content: ${modelResponse.choices?.firstOrNull()?.message?.content?.take(100) ?: "null"}")
                    
                    Result.success(modelResponse)
                } else {
                    val errorBody = response.body?.string() ?: "No response body"
                    Log.e(TAG, "=== HTTP ERROR DETAILS ===")
                    Log.e(TAG, "HTTP Status: ${response.code}")
                    Log.e(TAG, "Error Response Body: $errorBody")
                    Log.e(TAG, "Response Headers:")
                    for (i in 0 until response.headers.size) {
                        val name = response.headers.name(i)
                        val value = response.headers.value(i)
                        Log.e(TAG, "  $name: $value")
                    }
                    Log.e(TAG, "=== END ERROR DETAILS ===")
                    
                    // Also log to app's LogManager for visibility in settings
                    LogManager.shared.log("ERROR", "BailianClient", "HTTP ${response.code}: $errorBody")
                    LogManager.shared.log("DEBUG", "BailianClient", "=== HTTP ERROR DETAILS ===")
                    LogManager.shared.log("DEBUG", "BailianClient", "Full error response: $errorBody")
                    LogManager.shared.log("DEBUG", "BailianClient", "=== END ERROR DETAILS ===")
                    
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat failed: ${e.message}", e)
                Log.e(TAG, "Exception class: ${e.javaClass.name}")
                Log.e(TAG, "Exception stack trace: ${e.stackTraceToString()}")
                Result.failure(e)
            } finally {
                Log.d(TAG, "=== Chat request completed ===")
            }
        }
    }

    override fun chatStream(
        messages: List<Message>,
        tools: List<Tool>?
    ): Flow<ChatEvent> = flow {
        Log.d(TAG, "=== Starting chatStream request ===")
        Log.d(TAG, "API URL: $API_URL")
        Log.d(TAG, "Model: $model")
        Log.d(TAG, "API Key length: ${apiKey.length}")
        Log.d(TAG, "API Key prefix: ${if (apiKey.isNotEmpty()) apiKey.substring(0, minOf(8, apiKey.length)) + "..." else "EMPTY"}")
        Log.d(TAG, "Messages count: ${messages.size}")
        Log.d(TAG, "Tools count: ${tools?.size ?: 0}")
        Log.d(TAG, "Stream mode: enabled")

        // For Coding Plan service, build request manually to avoid serialization issues
        val modelClean = model.replace("bailian/", "")
        val messagesJson = json.encodeToString(messages)
        val toolsJson = if (tools != null && tools.isNotEmpty()) {
            json.encodeToString(tools)
        } else {
            ""
        }
        
        var requestBody = "{"
        requestBody += "\"model\":\"$modelClean\","
        requestBody += "\"messages\":$messagesJson"
        if (toolsJson.isNotEmpty()) {
            requestBody += ",\"tools\":$toolsJson"
        }
        requestBody += ",\"temperature\":0.7"
        requestBody += ",\"max_tokens\":8192"
        requestBody += ",\"stream\":true"
        requestBody += "}"

        Log.d(TAG, "=== FULL STREAM REQUEST DETAILS ===")
        Log.d(TAG, "URL: $API_URL")
        Log.d(TAG, "Method: POST")
        Log.d(TAG, "Headers:")
        Log.d(TAG, "  Authorization: Bearer ${if (apiKey.isNotEmpty()) apiKey.substring(0, minOf(8, apiKey.length)) + "..." else "EMPTY"}")
        Log.d(TAG, "  Content-Type: application/json")
        Log.d(TAG, "  Accept: text/event-stream")
        Log.d(TAG, "  User-Agent: OpenClaw-Android/1.0")
        Log.d(TAG, "Full request body:")
        Log.d(TAG, requestBody)
        Log.d(TAG, "=== END STREAM REQUEST DETAILS ===")

        // Also log to app's LogManager for visibility in settings
        LogManager.shared.log("DEBUG", "BailianClient", "=== FULL STREAM REQUEST DETAILS ===")
        LogManager.shared.log("DEBUG", "BailianClient", "URL: $API_URL")
        LogManager.shared.log("DEBUG", "BailianClient", "Full stream request body: $requestBody")

        val body = requestBody.toRequestBody(MEDIA_TYPE_JSON.toMediaType())

        val httpRequest = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("User-Agent", "OpenClaw-Android/1.0")
            .post(body)
            .build()

        // Accumulate streaming state
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        var fullContent = StringBuilder()
        var responseId: String? = null
        var finishReason: String? = null

        Log.d(TAG, "Starting stream event emission...")
        try {
            // Use direct streaming for coroutine-friendly SSE parsing
            emitStreamEvents(
                httpRequest = httpRequest,
                fullContent = fullContent,
                toolCallAccumulators = toolCallAccumulators,
                responseId = { responseId },
                finishReason = { finishReason }
            )
            Log.d(TAG, "Stream completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed: ${e.message}", e)
            emit(ChatEvent.Error(e.message ?: "Stream error"))
        } finally {
            Log.d(TAG, "=== ChatStream request completed ===")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Direct SSE stream reading with proper coroutine cancellation support
     */
    private suspend fun FlowCollector<ChatEvent>.emitStreamEvents(
        httpRequest: Request,
        fullContent: StringBuilder,
        toolCallAccumulators: MutableMap<Int, ToolCallAccumulator>,
        responseId: () -> String?,
        finishReason: () -> String?
    ) {
        Log.d(TAG, "Executing HTTP request for streaming...")
        val startTime = System.currentTimeMillis()
        val response = httpClient.newCall(httpRequest).execute()
        val endTime = System.currentTimeMillis()
        Log.d(TAG, "HTTP request completed in ${endTime - startTime}ms")

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "HTTP ${response.code}: $errorBody")
            Log.e(TAG, "Response headers: ${response.headers}")
            emit(ChatEvent.Error("HTTP ${response.code}: $errorBody"))
            return
        }

        Log.d(TAG, "HTTP ${response.code} - Stream connection established")
        Log.d(TAG, "Response headers: ${response.headers}")

        val reader = response.body?.byteStream()?.bufferedReader() ?: run {
            Log.e(TAG, "Empty response body from stream")
            emit(ChatEvent.Error("Empty response body"))
            return
        }

        var chunkId: String? = null
        var lineCount = 0
        var dataLineCount = 0

        reader.use {
            try {
                Log.d(TAG, "Starting to read stream lines...")
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    lineCount++
                    
                    if (line.isEmpty()) {
                        continue
                    }
                    
                    if (!line.startsWith("data: ")) {
                        Log.d(TAG, "Skipping non-data line [$lineCount]: $line")
                        continue
                    }

                    dataLineCount++
                    val data = line.removePrefix("data: ").trim()
                    Log.d(TAG, "Processing data line [$dataLineCount]: ${data.take(100)}...")
                    
                    if (data == "[DONE]") {
                        Log.d(TAG, "Received [DONE] signal, ending stream")
                        break
                    }

                    try {
                        val chunk = json.decodeFromString<StreamChunk>(data)
                        chunkId = chunk.id ?: chunkId
                        Log.d(TAG, "Parsed chunk ID: $chunkId")

                        val choice = chunk.choices?.firstOrNull() ?: continue
                        Log.d(TAG, "Processing choice with finishReason: ${choice.finishReason}")

                        // Emit text tokens
                        choice.delta.content?.let { token ->
                            fullContent.append(token)
                            Log.d(TAG, "Emitting token: ${token.take(50)}...")
                            emit(ChatEvent.Token(token))
                        }

                        // Accumulate tool call deltas
                        choice.delta.toolCalls?.forEach { delta ->
                            Log.d(TAG, "Processing tool call delta: index=${delta.index}, id=${delta.id}, name=${delta.function?.name}")
                            val acc = toolCallAccumulators.getOrPut(delta.index) {
                                ToolCallAccumulator(index = delta.index)
                            }
                            delta.id?.let { acc.id = it }
                            delta.type?.let { acc.type = it }
                            delta.function?.name?.let { acc.name = it }
                            delta.function?.arguments?.let { acc.arguments.append(it) }
                        }

                        // Stream complete
                        if (choice.finishReason != null) {
                            Log.d(TAG, "Stream finished with reason: ${choice.finishReason}")
                            val toolCalls = toolCallAccumulators.values
                                .sortedBy { it.index }
                                .mapNotNull { acc ->
                                    val accId = acc.id
                                    val accName = acc.name
                                    if (accId != null && accName != null) {
                                        ToolCall(
                                            id = accId,
                                            type = acc.type ?: "function",
                                            function = ToolCallFunction(
                                                name = accName,
                                                arguments = acc.arguments.toString()
                                            )
                                        )
                                    } else null
                                }

                            val responseMessage = ResponseMessage(
                                role = "assistant",
                                content = fullContent.toString().ifEmpty { null },
                                toolCalls = toolCalls.ifEmpty { null }
                            )

                            Log.d(TAG, "Emitting complete event with content length: ${responseMessage.content?.length ?: 0}")
                            Log.d(TAG, "Tool calls count: ${toolCalls.size}")

                            emit(ChatEvent.Complete(
                                ModelResponse(
                                    id = chunkId,
                                    choices = listOf(Choice(message = responseMessage, finishReason = choice.finishReason))
                                )
                            ))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE chunk: ${e.message}", e)
                        Log.w(TAG, "Problematic data: $data")
                    }
                }
                Log.d(TAG, "Stream reading completed. Total lines: $lineCount, Data lines: $dataLineCount")
            } catch (e: Exception) {
                Log.e(TAG, "Stream reading error: ${e.message}", e)
                emit(ChatEvent.Error(e.message ?: "Stream error"))
            }
        }
    }

    /**
     * Accumulator for streaming tool call deltas
     */
    private class ToolCallAccumulator(
        val index: Int,
        var id: String? = null,
        var type: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )
}
