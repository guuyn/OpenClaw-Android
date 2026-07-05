package ai.openclaw.android.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AnthropicClient unit tests using OkHttp MockWebServer.
 *
 * Strategy: replace the private `httpClient` field via reflection with a
 * client whose baseUrl points at MockWebServer. AnthropicClient uses different
 * headers (x-api-key, anthropic-version) and a different request URL
 * (POST /v1/messages), so these are validated explicitly.
 */
class AnthropicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AnthropicClient()
        client.configure(
            provider = ModelProvider.ANTHROPIC,
            apiKey = "sk-ant-test-key",
            model = "claude-sonnet-4-20250514",
            baseUrl = server.url("").toString().removeSuffix("/")
        )

        val httpClientField = AnthropicClient::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
        }
        val testHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
        httpClientField.set(client, testHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setBody(body)
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
        )
    }

    private fun enqueueSse(vararg chunks: String) {
        val sseBody = buildString {
            for (chunk in chunks) {
                append("data: $chunk\n\n")
            }
        }
        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
        )
    }

    private fun collectEvents(): List<ChatEvent>? {
        val msgs = listOf<Message>(Message(role = "user", content = "Hi"))
        val flow: Flow<ChatEvent> = client.chatStream(msgs)
        return runBlocking {
            withTimeoutOrNull(10_000) { flow.toList() }
        }
    }

    private fun firstComplete(events: List<ChatEvent>): ChatEvent.Complete? {
        for (e in events) {
            if (e is ChatEvent.Complete) return e
        }
        return null
    }

    private fun firstError(events: List<ChatEvent>): String? {
        for (e in events) {
            if (e is ChatEvent.Error) return e.message
        }
        return null
    }

    // ==================== Headers & URL ====================

    @Test
    fun `chat uses x-api-key header instead of Authorization`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val request = server.takeRequest()
        assertEquals("sk-ant-test-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        // Should NOT have Authorization header
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `chat posts to v1 messages endpoint`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(
            "Path should end with /v1/messages, got: ${request.path}",
            request.path?.endsWith("/v1/messages") == true
        )
    }

    @Test
    fun `chat removes anthropic prefix from model name`() = runBlocking {
        client.configure(
            provider = ModelProvider.ANTHROPIC,
            apiKey = "sk-ant-test-key",
            model = "anthropic/claude-sonnet-4-20250514",
            baseUrl = server.url("").toString().removeSuffix("/")
        )
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-4-20250514", bodyJson["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat extracts system message to top-level system field`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        )
        val sysMsg = Message(role = "system", content = "You are a helpful assistant.")
        val userMsg = Message(role = "user", content = "Hi")
        client.chat(listOf(sysMsg, userMsg))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("You are a helpful assistant.", bodyJson["system"]?.jsonPrimitive?.content)

        val messages = bodyJson["messages"]?.jsonArray
        assertEquals(1, messages!!.size) // system excluded
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    // ==================== Non-streaming response parsing ====================

    @Test
    fun `chat parses text response correctly`() = runBlocking {
        enqueueJson(
            """{"id":"msg_abc","content":[{"type":"text","text":"Hello from Claude"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
        )
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("Hello from Claude", response.content)
        assertEquals("stop", response.choices?.firstOrNull()?.finishReason)
        assertEquals(10, response.usage?.promptTokens)
        assertEquals(5, response.usage?.completionTokens)
    }

    @Test
    fun `chat parses tool_use response into toolCalls`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":""},{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"location":"Beijing"}}],"stop_reason":"tool_use"}"""
        )
        val result = client.chat(listOf(Message(role = "user", content = "weather?")))
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("tool_calls", response.choices?.firstOrNull()?.finishReason)
        val toolCalls = response.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("toolu_1", toolCalls[0].id)
        assertEquals("get_weather", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("Beijing"))
    }

    @Test
    fun `chat returns failure on HTTP 401`() = runBlocking {
        enqueueJson("Unauthorized", code = 401)
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
    }

    @Test
    fun `chat returns failure on HTTP 429`() = runBlocking {
        enqueueJson("Rate limited", code = 429)
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("429") == true)
    }

    @Test
    fun `chat returns failure on HTTP 500`() = runBlocking {
        enqueueJson("Internal error", code = 500)
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `chat returns failure on empty response body`() = runBlocking {
        server.enqueue(MockResponse().setBody("").setResponseCode(200))
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
    }

    @Test
    fun `chat returns failure on malformed JSON`() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json").setResponseCode(200))
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
    }

    // ==================== Multimodal (Anthropic Vision) ====================

    @Test
    fun `chat with image serializes Anthropic Vision format`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"I see a cat"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        )
        val image = ImageContent(base64 = "imgBase64", mediaType = "image/png")
        val msg = Message(
            role = "user",
            content = "What's in this?",
            images = listOf(image)
        )
        client.chat(listOf(msg))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = bodyJson["messages"]?.jsonArray!!
        val firstMsg = messages[0].jsonObject
        val content = firstMsg["content"]?.jsonArray
        assertNotNull("Content should be array for multimodal", content)
        val imageBlock = content!!.first().jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]?.jsonObject
        assertEquals("base64", source?.get("type")?.jsonPrimitive?.content)
        assertEquals("image/png", source?.get("media_type")?.jsonPrimitive?.content)
        assertEquals("imgBase64", source?.get("data")?.jsonPrimitive?.content)
    }

    // ==================== Tool conversion ====================

    @Test
    fun `chat converts tools to Anthropic input_schema format`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        )
        val tools = listOf(
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "get_weather",
                    description = "Get weather for a location",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "location" to ToolProperty("string", "City name")
                        ),
                        required = listOf("location")
                    )
                )
            )
        )
        client.chat(listOf(Message(role = "user", content = "weather?")), tools)

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolsArr = bodyJson["tools"]?.jsonArray
        assertNotNull(toolsArr)
        val toolObj = toolsArr!![0].jsonObject
        assertEquals("get_weather", toolObj["name"]?.jsonPrimitive?.content)
        assertEquals("Get weather for a location", toolObj["description"]?.jsonPrimitive?.content)
        val inputSchema = toolObj["input_schema"]?.jsonObject
        assertEquals("object", inputSchema?.get("type")?.jsonPrimitive?.content)
        val required = inputSchema?.get("required")?.jsonArray
        assertEquals(1, required!!.size)
        assertEquals("location", required[0].jsonPrimitive?.content)
    }

    @Test
    fun `chat serializes assistant tool_calls as tool_use blocks`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        )
        val assistantMsg = Message(
            role = "assistant",
            content = "calling tool",
            toolCalls = listOf(
                ToolCall(
                    id = "tc_1",
                    function = ToolCallFunction(name = "search", arguments = "{\"q\":\"test\"}")
                )
            )
        )
        client.chat(listOf(
            Message(role = "user", content = "search"),
            assistantMsg
        ))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = bodyJson["messages"]?.jsonArray!!
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        val content = assistant["content"]?.jsonArray!!
        // Should have text block + tool_use block
        val hasToolUse = content.any {
            val obj = it.jsonObject
            obj["type"]?.jsonPrimitive?.content == "tool_use"
        }
        assertTrue("Assistant should have tool_use block", hasToolUse)
    }

    @Test
    fun `chat serializes tool result as tool_result block`() = runBlocking {
        enqueueJson(
            """{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        )
        val toolMsg = Message(role = "tool", content = "tool output", toolCallId = "tc_1")
        client.chat(listOf(
            Message(role = "user", content = "search"),
            toolMsg
        ))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = bodyJson["messages"]?.jsonArray!!
        // tool message should become user role with tool_result content
        val toolResultMsg = messages[1].jsonObject
        assertEquals("user", toolResultMsg["role"]?.jsonPrimitive?.content)
        val content = toolResultMsg["content"]?.jsonArray!!
        val firstBlock = content[0].jsonObject
        assertEquals("tool_result", firstBlock["type"]?.jsonPrimitive?.content)
        assertEquals("tc_1", firstBlock["tool_use_id"]?.jsonPrimitive?.content)
    }

    // ==================== Streaming SSE ====================

    @Test
    fun `chatStream parses text deltas and emits Complete`() = runBlocking {
        enqueueSse(
            """{"type":"message_start","message":{"id":"msg_x","role":"assistant"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"He"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"llo"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}"""
        )

        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        val complete = firstComplete(events)
        assertNotNull("Complete event expected", complete)
        assertEquals("Hello", complete!!.response.content)
        assertEquals("stop", complete.response.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun `chatStream accumulates input_json_delta for tool_use`() = runBlocking {
        enqueueSse(
            """{"type":"message_start","message":{"id":"msg_x"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"loc"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ation\":"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"Beijing\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            """{"type":"message_stop"}"""
        )

        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        val complete = firstComplete(events)
        assertNotNull(complete)
        assertEquals("tool_calls", complete!!.response.choices?.firstOrNull()?.finishReason)

        val toolCalls = complete.response.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("toolu_1", toolCalls[0].id)
        assertEquals("get_weather", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("Beijing"))
    }

    @Test
    fun `chatStream emits Error on HTTP 401`() = runBlocking {
        enqueueJson("Unauthorized", code = 401)
        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        val error = firstError(events)
        assertNotNull(error)
        assertTrue(error!!.contains("401"))
    }

    @Test
    fun `chatStream emits Error on HTTP 500`() = runBlocking {
        enqueueJson("Internal error", code = 500)
        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        val error = firstError(events)
        assertNotNull(error)
        assertTrue(error!!.contains("500"))
    }
}