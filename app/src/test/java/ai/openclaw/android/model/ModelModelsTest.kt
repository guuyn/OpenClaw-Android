package ai.openclaw.android.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SSE JSON deserialization — validates the @SerialName mappings
 * that were previously missing (finish_reason, prompt_tokens, etc.)
 */
class ModelModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ===== StreamChunk: finish_reason parsing (the core bug) =====

    @Test
    fun streamChunk_parsesFinishReason_stop() {
        val data = """{"choices":[{"delta":{"content":"hello"},"finish_reason":"stop","index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        assertEquals("stop", chunk.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun streamChunk_parsesFinishReason_toolCalls() {
        // This is the exact case from the HONOR logs that caused "No response from model"
        val data = """{"choices":[{"finish_reason":"tool_calls","delta":{"content":"","reasoning_content":null},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        assertEquals("tool_calls", chunk.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun streamChunk_parsesFinishReason_null_whenAbsent() {
        val data = """{"choices":[{"delta":{"content":"hi"},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        assertNull(chunk.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun streamChunk_parsesDeltaContent() {
        val data = """{"choices":[{"delta":{"content":"world"},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        assertEquals("world", chunk.choices?.firstOrNull()?.delta?.content)
    }

    @Test
    fun streamChunk_parsesDeltaRole() {
        val data = """{"choices":[{"delta":{"role":"assistant","content":null},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        assertEquals("assistant", chunk.choices?.firstOrNull()?.delta?.role)
    }

    // ===== Streaming tool_calls accumulation =====

    @Test
    fun streamChunk_parsesToolCallDelta() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"weather_get","arguments":""}}]},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        val toolCall = chunk.choices?.firstOrNull()?.delta?.toolCalls?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals(0, toolCall!!.index)
        assertEquals("call_abc", toolCall.id)
        assertEquals("weather_get", toolCall.function?.name)
    }

    @Test
    fun streamChunk_parsesToolCallArgumentsDelta() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"lo"},"index":0}]},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        val args = chunk.choices?.firstOrNull()?.delta?.toolCalls?.firstOrNull()?.function?.arguments
        assertEquals("{\"lo", args)
    }

    // ===== Full ModelResponse (non-streaming) =====

    @Test
    fun modelResponse_parsesChoiceFinishReason() {
        val data = """{"id":"chatcmpl-123","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}]}"""
        val response = json.decodeFromString<ModelResponse>(data)
        assertEquals("stop", response.choices?.firstOrNull()?.finishReason)
        assertEquals("Hello!", response.content)
    }

    @Test
    fun modelResponse_parsesUsage() {
        val data = """{"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
        val response = json.decodeFromString<ModelResponse>(data)
        assertEquals(10, response.usage?.promptTokens)
        assertEquals(20, response.usage?.completionTokens)
        assertEquals(30, response.usage?.totalTokens)
    }

    @Test
    fun modelResponse_parsesToolCallsResponse() {
        val data = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather_get","arguments":"{\"location\":\"Beijing\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val response = json.decodeFromString<ModelResponse>(data)
        assertEquals("tool_calls", response.choices?.firstOrNull()?.finishReason)
        val toolCall = response.toolCalls?.firstOrNull()
        assertNotNull(toolCall)
        assertEquals("call_1", toolCall!!.id)
        assertEquals("weather_get", toolCall.function.name)
        assertEquals("{\"location\":\"Beijing\"}", toolCall.function.arguments)
    }

    // ===== Reasoning content (qwen3.5-plus) =====

    @Test
    fun streamChunk_ignoresReasoningContent() {
        val data = """{"choices":[{"delta":{"content":null,"reasoning_content":"thinking..."},"index":0}]}"""
        val chunk = json.decodeFromString<StreamChunk>(data)
        // reasoning_content is unknown key, should be ignored gracefully
        assertNull(chunk.choices?.firstOrNull()?.delta?.content)
    }

    // ===== Full SSE sequence simulation =====

    @Test
    fun fullSseSequence_withToolCall_parsesCorrectly() {
        // Simulate the exact sequence from HONOR logs
        val lines = listOf(
            """{"choices":[{"delta":{"content":null,"reasoning_content":"用户","role":"assistant"},"finish_reason":null,"index":0}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_content":"天气"},"finish_reason":null,"index":0}]}""",
            """{"choices":[{"delta":{"content":null,"reasoning_content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"location_get","arguments":""}}]},"finish_reason":null,"index":0}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{}"},"index":0}]},"finish_reason":null,"index":0}]}""",
            """{"choices":[{"finish_reason":"tool_calls","delta":{"content":"","reasoning_content":null},"index":0}]}"""
        )

        var toolCallAccId: String? = null
        var toolCallAccName: String? = null
        var toolCallAccArgs = StringBuilder()
        var gotFinishReason: String? = null

        for (line in lines) {
            val chunk = json.decodeFromString<StreamChunk>(line)
            val choice = chunk.choices?.firstOrNull() ?: continue

            // Check finish reason
            choice.finishReason?.let { gotFinishReason = it }

            // Accumulate tool calls
            choice.delta.toolCalls?.forEach { delta ->
                delta.id?.let { toolCallAccId = it }
                delta.function?.name?.let { toolCallAccName = it }
                delta.function?.arguments?.let { toolCallAccArgs.append(it) }
            }
        }

        assertEquals("tool_calls", gotFinishReason)
        assertEquals("call_abc", toolCallAccId)
        assertEquals("location_get", toolCallAccName)
        assertEquals("{}", toolCallAccArgs.toString())
    }

    // ===== Message serialization =====

    @Test
    fun message_serializesToolCallId() {
        val msg = Message(role = "tool", content = "result", toolCallId = "call_1")
        val encoded = json.encodeToString(Message.serializer(), msg)
        assertTrue(encoded.contains("\"tool_call_id\":\"call_1\""))
    }

    @Test
    fun message_serializesToolCalls() {
        val msg = Message(
            role = "assistant",
            content = "",
            toolCalls = listOf(ToolCall(id = "c1", function = ToolCallFunction(name = "test", arguments = "{}")))
        )
        val encoded = json.encodeToString(Message.serializer(), msg)
        assertTrue(encoded.contains("\"tool_calls\""))
        assertTrue(encoded.contains("\"test\""))
    }

    // ===== ChatRequest =====

    @Test
    fun chatRequest_serializesMaxTokens() {
        val req = ChatRequest(model = "qwen", messages = emptyList(), maxTokens = 8192, stream = true)
        val encoded = json.encodeToString(ChatRequest.serializer(), req)
        assertTrue(encoded.contains("\"max_tokens\":8192"))
        assertTrue(encoded.contains("\"stream\":true"))
    }
}
