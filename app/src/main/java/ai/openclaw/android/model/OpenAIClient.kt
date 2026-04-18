package ai.openclaw.android.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI Model Client Implementation
 *
 * Calls the OpenAI Chat Completions API with SSE streaming and tool calling support.
 */
class OpenAIClient : ModelClient {

    companion object {
        private const val TAG = "OpenAIClient"
        private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
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
    private var baseUrl: String = DEFAULT_BASE_URL

    override fun configure(provider: ModelProvider, apiKey: String, model: String, baseUrl: String) {
        this.apiKey = apiKey
        this.model = model
        this.baseUrl = baseUrl.ifEmpty { DEFAULT_BASE_URL }
    }

    override suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>?
    ): Result<ModelResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(messages, tools, stream = false)
                val response = httpClient.newCall(request).execute()
                handleResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "Chat failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    override fun chatStream(
        messages: List<Message>,
        tools: List<Tool>?
    ): Flow<ChatEvent> = flow {
        val request = buildRequest(messages, tools, stream = true)

        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        val fullContent = StringBuilder()
        var chunkId: String? = null

        try {
            emitStreamEvents(request, fullContent, toolCallAccumulators) { chunkId }
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed: ${e.message}", e)
            emit(ChatEvent.Error(e.message ?: "Stream error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(
        messages: List<Message>,
        tools: List<Tool>?,
        stream: Boolean
    ): Request {
        val modelClean = model.removePrefix("openai/")
        val messagesJson = json.encodeToString(messages)

        val bodyBuilder = StringBuilder()
        bodyBuilder.append("{\"model\":\"$modelClean\",")
        bodyBuilder.append("\"messages\":$messagesJson")

        if (!tools.isNullOrEmpty()) {
            bodyBuilder.append(",\"tools\":${json.encodeToString(tools)}")
        }

        bodyBuilder.append(",\"temperature\":0.7")
        bodyBuilder.append(",\"max_tokens\":16384")

        if (stream) {
            bodyBuilder.append(",\"stream\":true")
        }

        bodyBuilder.append("}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .apply { if (stream) addHeader("Accept", "text/event-stream") }
            .post(bodyBuilder.toString().toRequestBody(MEDIA_TYPE_JSON.toMediaType()))
            .build()

        Log.d(TAG, "${if (stream) "Stream" else "Chat"} request: model=$modelClean, messages=${messages.size}, tools=${tools?.size ?: 0}")
        return httpRequest
    }

    private fun handleResponse(response: okhttp3.Response): Result<ModelResponse> {
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "HTTP ${response.code}: $errorBody")
            return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
        }

        val responseBody = response.body?.string()
        if (responseBody.isNullOrEmpty()) {
            return Result.failure(Exception("Empty response from model"))
        }

        return try {
            val modelResponse = json.decodeFromString<ModelResponse>(responseBody)
            Log.d(TAG, "Response: choices=${modelResponse.choices?.size}, content=${modelResponse.content?.take(100)}")
            Result.success(modelResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun FlowCollector<ChatEvent>.emitStreamEvents(
        httpRequest: Request,
        fullContent: StringBuilder,
        toolCallAccumulators: MutableMap<Int, ToolCallAccumulator>,
        responseId: () -> String?
    ) {
        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "Stream HTTP ${response.code}: $errorBody")
            emit(ChatEvent.Error("HTTP ${response.code}: $errorBody"))
            return
        }

        val reader = response.body?.byteStream()?.bufferedReader() ?: run {
            emit(ChatEvent.Error("Empty response body"))
            return
        }

        var chunkId: String? = null

        reader.use {
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || !line.startsWith("data: ")) continue

                val data = line.removePrefix("data: ").trim()

                if (data == "[DONE]") break

                try {
                    val chunk = json.decodeFromString<StreamChunk>(data)
                    chunkId = chunk.id ?: chunkId

                    val choice = chunk.choices?.firstOrNull() ?: continue

                    // Emit text tokens
                    choice.delta.content?.let { token ->
                        fullContent.append(token)
                        emit(ChatEvent.Token(token))
                    }

                    // Accumulate tool call deltas
                    choice.delta.toolCalls?.forEach { delta ->
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
                        val toolCalls = toolCallAccumulators.values
                            .sortedBy { it.index }
                            .mapNotNull { acc ->
                                val id = acc.id ?: return@mapNotNull null
                                val name = acc.name ?: return@mapNotNull null
                                ToolCall(
                                    id = id,
                                    type = acc.type ?: "function",
                                    function = ToolCallFunction(
                                        name = name,
                                        arguments = acc.arguments.toString()
                                    )
                                )
                            }

                        val responseMessage = ResponseMessage(
                            role = "assistant",
                            content = fullContent.toString().ifEmpty { null },
                            toolCalls = toolCalls.ifEmpty { null }
                        )

                        emit(ChatEvent.Complete(
                            ModelResponse(
                                id = chunkId,
                                choices = listOf(
                                    Choice(message = responseMessage, finishReason = choice.finishReason)
                                )
                            )
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE chunk: ${e.message}")
                }
            }
        }
    }

    private class ToolCallAccumulator(
        val index: Int,
        var id: String? = null,
        var type: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )
}
