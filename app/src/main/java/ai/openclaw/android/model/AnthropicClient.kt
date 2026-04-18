package ai.openclaw.android.model

import android.util.Log
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anthropic Model Client Implementation
 *
 * Calls the Anthropic Messages API with SSE streaming and tool use support.
 * Handles the format differences between Anthropic and OpenAI APIs:
 * - Auth via x-api-key header + anthropic-version header
 * - Content blocks format (array of typed blocks instead of flat string)
 * - Tool use with content_block_start/stop/delta SSE events
 */
class AnthropicClient : ModelClient {

    companion object {
        private const val TAG = "AnthropicClient"
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val ANTHROPIC_VERSION = "2023-06-01"
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
    private var model: String = "claude-sonnet-4-20250514"
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

        val toolUseAccumulators = mutableMapOf<Int, ToolUseAccumulator>()
        val fullContent = StringBuilder()
        var responseId: String? = null

        try {
            emitStreamEvents(request, fullContent, toolUseAccumulators) { responseId }
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed: ${e.message}", e)
            emit(ChatEvent.Error(e.message ?: "Stream error"))
        }
    }.flowOn(Dispatchers.IO)

    // ============ Request Building ============

    private fun buildRequest(
        messages: List<Message>,
        tools: List<Tool>?,
        stream: Boolean
    ): Request {
        val modelClean = model.removePrefix("anthropic/")

        // Extract system message separately (Anthropic uses top-level system field)
        val systemText = messages.filter { it.role == "system" }
            .joinToString("\n") { it.content }
        val chatMessages = messages.filter { it.role != "system" }

        // Convert messages to Anthropic format
        val anthropicMessages = buildJsonArray {
            for (msg in chatMessages) {
                add(convertMessage(msg))
            }
        }

        val bodyBuilder = buildJsonObject {
            put("model", JsonPrimitive(modelClean))
            put("messages", anthropicMessages)
            put("max_tokens", JsonPrimitive(16384))
            put("temperature", JsonPrimitive(0.7))

            if (systemText.isNotEmpty()) {
                put("system", JsonPrimitive(systemText))
            }

            if (!tools.isNullOrEmpty()) {
                put("tools", convertTools(tools))
            }

            if (stream) {
                put("stream", JsonPrimitive(true))
            }
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .apply { if (stream) addHeader("Accept", "text/event-stream") }
            .post(bodyBuilder.toString().toRequestBody(MEDIA_TYPE_JSON.toMediaType()))
            .build()

        Log.d(TAG, "${if (stream) "Stream" else "Chat"} request: model=$modelClean, messages=${chatMessages.size}, tools=${tools?.size ?: 0}")
        return httpRequest
    }

    /**
     * Convert a Message to Anthropic's content block format.
     *
     * Anthropic uses content blocks:
     * - user/assistant text: [{"type": "text", "text": "..."}]
     * - assistant tool_use: [{"type": "tool_use", "id": "...", "name": "...", "input": {...}}]
     * - tool result: [{"type": "tool_result", "tool_use_id": "...", "content": "..."}]
     */
    private fun convertMessage(msg: Message): JsonObject {
        return when (msg.role) {
            "tool" -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(msg.toolCallId ?: ""))
                        put("content", JsonPrimitive(msg.content))
                    })
                })
            }

            "assistant" -> {
                val hasToolCalls = !msg.toolCalls.isNullOrEmpty()
                buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", buildJsonArray {
                        // Add text content if present
                        if (msg.content.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(msg.content))
                            })
                        }
                        // Add tool_use blocks
                        msg.toolCalls?.forEach { tc ->
                            add(buildJsonObject {
                                put("type", JsonPrimitive("tool_use"))
                                put("id", JsonPrimitive(tc.id))
                                put("name", JsonPrimitive(tc.function.name))
                                // Parse arguments JSON string to object
                                val inputObj = try {
                                    json.parseToJsonElement(tc.function.arguments).jsonObject
                                } catch (_: Exception) {
                                    buildJsonObject {}
                                }
                                put("input", inputObj)
                            })
                        }
                    })
                }
            }

            else -> buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(msg.content))
                    })
                })
            }
        }
    }

    /**
     * Convert OpenAI-format Tool definitions to Anthropic format.
     *
     * Anthropic tool schema:
     * {
     *   "name": "...",
     *   "description": "...",
     *   "input_schema": { "type": "object", "properties": {...}, "required": [...] }
     * }
     */
    private fun convertTools(tools: List<Tool>): JsonArray {
        return buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("name", JsonPrimitive(tool.function.name))
                    put("description", JsonPrimitive(tool.function.description))
                    // Convert parameters to input_schema
                    put("input_schema", buildJsonObject {
                        put("type", JsonPrimitive(tool.function.parameters.type))
                        put("properties", buildJsonObject {
                            tool.function.parameters.properties.forEach { (name, prop) ->
                                put(name, buildJsonObject {
                                    put("type", JsonPrimitive(prop.type))
                                    put("description", JsonPrimitive(prop.description))
                                })
                            }
                        })
                        put("required", buildJsonArray {
                            tool.function.parameters.required.forEach {
                                add(JsonPrimitive(it))
                            }
                        })
                    })
                })
            }
        }
    }

    // ============ Response Handling ============

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
            val anthropicResp = json.parseToJsonElement(responseBody).jsonObject
            val modelResponse = convertResponse(anthropicResp)
            Log.d(TAG, "Response: content=${modelResponse.content?.take(100)}, toolCalls=${modelResponse.toolCalls?.size ?: 0}")
            Result.success(modelResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Convert Anthropic response to our internal ModelResponse format.
     *
     * Anthropic response:
     * {
     *   "id": "...",
     *   "content": [
     *     {"type": "text", "text": "..."},
     *     {"type": "tool_use", "id": "...", "name": "...", "input": {...}}
     *   ],
     *   "stop_reason": "end_turn" | "tool_use",
     *   "usage": {"input_tokens": ..., "output_tokens": ...}
     * }
     */
    private fun convertResponse(anthropicResp: JsonObject): ModelResponse {
        val id = anthropicResp["id"]?.jsonPrimitive?.content
        val stopReason = anthropicResp["stop_reason"]?.jsonPrimitive?.content
        val contentArray = anthropicResp["content"]?.jsonArray ?: emptyList()

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        for (block in contentArray) {
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    val text = blockObj["text"]?.jsonPrimitive?.content ?: ""
                    textParts.add(text)
                }
                "tool_use" -> {
                    val toolId = blockObj["id"]?.jsonPrimitive?.content ?: ""
                    val toolName = blockObj["name"]?.jsonPrimitive?.content ?: ""
                    val toolInput = blockObj["input"]?.jsonObject?.let {
                        json.encodeToString(JsonObject.serializer(), it)
                    } ?: "{}"
                    toolCalls.add(ToolCall(
                        id = toolId,
                        type = "function",
                        function = ToolCallFunction(
                            name = toolName,
                            arguments = toolInput
                        )
                    ))
                }
            }
        }

        val finishReason = when (stopReason) {
            "tool_use" -> "tool_calls"
            "end_turn" -> "stop"
            else -> stopReason
        }

        return ModelResponse(
            id = id,
            choices = listOf(
                Choice(
                    message = ResponseMessage(
                        role = "assistant",
                        content = textParts.joinToString("").ifEmpty { null },
                        toolCalls = toolCalls.ifEmpty { null }
                    ),
                    finishReason = finishReason
                )
            ),
            usage = anthropicResp["usage"]?.jsonObject?.let { u ->
                Usage(
                    promptTokens = u["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull(),
                    completionTokens = u["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                )
            }
        )
    }

    // ============ SSE Streaming ============

    private suspend fun FlowCollector<ChatEvent>.emitStreamEvents(
        httpRequest: Request,
        fullContent: StringBuilder,
        toolUseAccumulators: MutableMap<Int, ToolUseAccumulator>,
        getResponseId: () -> String?
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

        var responseId: String? = null
        var stopReason: String? = null

        reader.use {
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line.isBlank()) continue

                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty()) continue

                try {
                    val eventObj = json.parseToJsonElement(data).jsonObject
                    val eventType = eventObj["type"]?.jsonPrimitive?.content ?: continue

                    when (eventType) {
                        "message_start" -> {
                            val message = eventObj["message"]?.jsonObject
                            responseId = message?.get("id")?.jsonPrimitive?.content
                        }

                        "content_block_start" -> {
                            val index = eventObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                            val contentBlock = eventObj["content_block"]?.jsonObject ?: continue
                            val blockType = contentBlock["type"]?.jsonPrimitive?.content

                            if (blockType == "tool_use") {
                                val toolId = contentBlock["id"]?.jsonPrimitive?.content ?: ""
                                val toolName = contentBlock["name"]?.jsonPrimitive?.content ?: ""
                                toolUseAccumulators[index] = ToolUseAccumulator(
                                    index = index,
                                    id = toolId,
                                    name = toolName
                                )
                            }
                        }

                        "content_block_delta" -> {
                            val delta = eventObj["delta"]?.jsonObject ?: continue
                            val deltaType = delta["type"]?.jsonPrimitive?.content

                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                    fullContent.append(text)
                                    emit(ChatEvent.Token(text))
                                }
                                "input_json_delta" -> {
                                    val index = eventObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                                    val partialJson = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                    toolUseAccumulators[index]?.inputJson?.append(partialJson)
                                }
                            }
                        }

                        "message_delta" -> {
                            val delta = eventObj["delta"]?.jsonObject
                            stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                        }

                        "message_stop" -> {
                            // Stream complete — assemble final response
                            val toolCalls = toolUseAccumulators.values
                                .sortedBy { it.index }
                                .map { acc ->
                                    ToolCall(
                                        id = acc.id,
                                        type = "function",
                                        function = ToolCallFunction(
                                            name = acc.name,
                                            arguments = acc.inputJson.toString().ifEmpty { "{}" }
                                        )
                                    )
                                }

                            val finishReason = when (stopReason) {
                                "tool_use" -> "tool_calls"
                                "end_turn" -> "stop"
                                else -> stopReason
                            }

                            val responseMessage = ResponseMessage(
                                role = "assistant",
                                content = fullContent.toString().ifEmpty { null },
                                toolCalls = toolCalls.ifEmpty { null }
                            )

                            emit(ChatEvent.Complete(
                                ModelResponse(
                                    id = responseId ?: getResponseId(),
                                    choices = listOf(
                                        Choice(message = responseMessage, finishReason = finishReason)
                                    )
                                )
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE event: ${e.message}")
                }
            }
        }
    }

    private class ToolUseAccumulator(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val inputJson: StringBuilder = StringBuilder()
    )
}
