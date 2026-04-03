package ai.openclaw.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message in a conversation
 */
@Serializable
data class Message(
    val role: String,  // "user" | "assistant" | "system" | "tool"
    val content: String,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,  // Required when role = "tool"
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null  // Required when assistant calls tools
)

/**
 * Tool definition for model function calling
 */
@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String
)

/**
 * Tool call from model
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String  // JSON string
)

/**
 * Model response
 */
@Serializable
data class ModelResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null
) {
    val content: String?
        get() = choices?.firstOrNull()?.message?.content
    
    val toolCalls: List<ToolCall>?
        get() = choices?.firstOrNull()?.message?.toolCalls
}

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

/**
 * Chat request to model API
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

// ============ SSE Streaming Models ============

/**
 * SSE chunk response (OpenAI-compatible streaming format)
 */
@Serializable
data class StreamChunk(
    val id: String? = null,
    @SerialName("object")
    val obj: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<StreamChoice>? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<StreamToolCallDelta>? = null
)

/**
 * Tool call delta in streaming - arguments arrive incrementally
 */
@Serializable
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolCallFunctionDelta? = null
)

@Serializable
data class StreamToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)