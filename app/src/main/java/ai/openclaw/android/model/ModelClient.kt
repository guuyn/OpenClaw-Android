package ai.openclaw.android.model

import kotlinx.coroutines.flow.Flow

/**
 * ModelClient Interface
 *
 * Defines the contract for LLM API calls.
 */
interface ModelClient {

    /**
     * Send a chat request to the model (non-streaming)
     */
    suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>? = null
    ): Result<ModelResponse>

    /**
     * Send a streaming chat request, returning tokens incrementally
     */
    fun chatStream(
        messages: List<Message>,
        tools: List<Tool>? = null
    ): Flow<ChatEvent>

    /**
     * Set the model provider and API key
     */
    fun configure(provider: ModelProvider, apiKey: String, model: String, baseUrl: String = "")
}

/**
 * Streaming events from model
 */
sealed class ChatEvent {
    /** A text token has been generated */
    data class Token(val text: String) : ChatEvent()

    /** Model is requesting tool execution */
    data class ToolCallRequested(val toolCall: ToolCall) : ChatEvent()

    /** Streaming complete */
    data class Complete(val response: ModelResponse) : ChatEvent()

    /** An error occurred */
    data class Error(val message: String) : ChatEvent()
}

/**
 * Supported model providers
 */
enum class ModelProvider {
    OPENAI,     // OpenAI-compatible API (also covers Bailian via base URL)
    ANTHROPIC,  // Anthropic
    LOCAL       // 本地端侧推理 (Gemma 4 E4B)
}
