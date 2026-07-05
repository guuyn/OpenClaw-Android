package ai.openclaw.android.model

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
 * OpenAIClient unit tests using OkHttp MockWebServer.
 *
 * Strategy: replace the private `httpClient` field via reflection with a
 * client that has shorter timeouts. The OkHttpClient makes requests to
 * whichever URL the OpenAIClient builds — we point that baseUrl at the
 * MockWebServer, so all requests hit the mock.
 *
 * Note on helper methods: Kotlin 2.3.0 type inference is stricter around
 * `withTimeoutOrNull { ... } ?: fail(...)`. We extract event collection into
 * a private helper that returns the unwrapped list with explicit typing.
 */
class OpenAIClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAIClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAIClient()
        client.configure(
            provider = ModelProvider.OPENAI,
            apiKey = "sk-test-key",
            model = "gpt-4",
            baseUrl = server.url("/v1").toString().removeSuffix("/")
        )

        val httpClientField = OpenAIClient::class.java.getDeclaredField("httpClient").apply {
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
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
        )
    }

    /** Collect chat-stream events with a 10s timeout. Returns null on timeout. */
    private fun collectEvents(): List<ChatEvent>? {
        val msgs = listOf<Message>(Message(role = "user", content = "Hi"))
        val flow: kotlinx.coroutines.flow.Flow<ChatEvent> = client.chatStream(msgs)
        return runBlocking {
            withTimeoutOrNull(10_000) { flow.toList() }
        }
    }

    /** Pull all Token events out of an event list. */
    private fun tokensOf(events: List<ChatEvent>): List<String> {
        val out = mutableListOf<String>()
        for (e in events) {
            if (e is ChatEvent.Token) {
                out.add(e.text)
            }
        }
        return out
    }

    /** First Complete event, or null. */
    private fun firstComplete(events: List<ChatEvent>): ChatEvent.Complete? {
        for (e in events) {
            if (e is ChatEvent.Complete) return e
        }
        return null
    }

    /** First Error message, or null. */
    private fun firstError(events: List<ChatEvent>): String? {
        for (e in events) {
            if (e is ChatEvent.Error) return e.message
        }
        return null
    }

    // ==================== Non-streaming chat ====================

    @Test
    fun `chat returns assistant content on success`() = runBlocking {
        enqueueJson(
            """{"id":"chatcmpl-123","choices":[{"index":0,"message":{"role":"assistant","content":"Hello, world!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        )

        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue("chat should succeed", result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("Hello, world!", response.content)
        assertEquals("stop", response.choices?.firstOrNull()?.finishReason)
        assertEquals(10, response.usage?.promptTokens)
        assertEquals(5, response.usage?.completionTokens)
    }

    @Test
    fun `chat includes Authorization and Content-Type headers`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val request = server.takeRequest()
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    @Test
    fun `chat sends model and messages in body`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val recorded = server.takeRequest()
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("gpt-4", bodyJson["model"]?.jsonPrimitive?.content)
        assertEquals(1, bodyJson["messages"]?.jsonArray?.size)
    }

    @Test
    fun `chat includes tools in body when provided`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        val tools = listOf(
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "search",
                    description = "Search the web",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf("q" to ToolProperty("string", "query"))
                    )
                )
            )
        )
        client.chat(listOf(Message(role = "user", content = "search")), tools)

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNotNull(bodyJson["tools"])
        assertEquals(1, bodyJson["tools"]?.jsonArray?.size)
    }

    @Test
    fun `chat with multimodal message serializes image_url content`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"I see a cat"},"finish_reason":"stop"}]}"""
        )
        val image = ImageContent(base64 = "abcd1234", mediaType = "image/jpeg")
        val msg = Message(
            role = "user",
            content = "What's in this image?",
            images = listOf(image)
        )
        client.chat(listOf(msg))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val firstMsg = bodyJson["messages"]?.jsonArray?.first()?.jsonObject
        val content = firstMsg?.get("content")?.jsonArray
        assertNotNull("Multimodal content should be an array", content)
        val firstContent = content!!.first().jsonObject
        assertEquals("image_url", firstContent["type"]?.jsonPrimitive?.content)
        val imageUrl = firstContent["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        assertEquals("data:image/jpeg;base64,abcd1234", imageUrl)
    }

    @Test
    fun `chat pure-text message uses string content`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val firstMsg = bodyJson["messages"]?.jsonArray?.first()?.jsonObject
        assertEquals("Hi", firstMsg?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `chat returns failure on HTTP 401`() = runBlocking {
        enqueueJson("Unauthorized", code = 401)
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue("chat should fail on 401", result.isFailure)
        assertTrue(
            "Failure message should mention 401",
            result.exceptionOrNull()?.message?.contains("401") == true
        )
    }

    @Test
    fun `chat returns failure on HTTP 429`() = runBlocking {
        enqueueJson("Rate limit exceeded", code = 429)
        val result = client.chat(listOf(Message(role = "user", content = "Hi")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("429") == true)
    }

    @Test
    fun `chat returns failure on HTTP 500`() = runBlocking {
        enqueueJson("Internal server error", code = 500)
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

    // ==================== Streaming chat ====================

    @Test
    fun `chatStream emits tokens then Complete`() = runBlocking {
        val chunks = listOf(
            """{"id":"chatcmpl-x","choices":[{"index":0,"delta":{"role":"assistant","content":"He"},"finish_reason":null}]}""",
            """{"id":"chatcmpl-x","choices":[{"index":0,"delta":{"content":"llo"},"finish_reason":null}]}""",
            """{"id":"chatcmpl-x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        enqueueSse(*chunks.toTypedArray())

        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        assertEquals(listOf("He", "llo"), tokensOf(events))

        val complete = firstComplete(events)
        assertNotNull(complete)
        assertEquals("Hello", complete!!.response.content)
        assertEquals("stop", complete.response.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun `chatStream accumulates tool call deltas`() = runBlocking {
        val chunks = listOf(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"search","arguments":""}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"test\"}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        enqueueSse(*chunks.toTypedArray())

        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")
        val complete = firstComplete(events)
        assertNotNull(complete)

        val toolCalls: List<ToolCall>? = complete!!.response.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        val tc = toolCalls[0]
        assertEquals("call_abc", tc.id)
        assertEquals("search", tc.function.name)
        assertEquals("{\"q\":\"test\"}", tc.function.arguments)
        assertEquals("tool_calls", complete.response.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun `chatStream handles stream with only finish event`() = runBlocking {
        enqueueSse(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")

        val complete = firstComplete(events)
        assertNotNull(complete)
        assertNull(complete!!.response.content)
    }

    @Test
    fun `chatStream includes Accept text-event-stream header`() = runBlocking {
        enqueueSse("""{"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"stop"}]}""")
        val events: List<ChatEvent> = collectEvents() ?: error("Streaming did not complete in 10s")

        val request = server.takeRequest()
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
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
    fun `configure updates api key for subsequent requests`() = runBlocking {
        client.configure(
            provider = ModelProvider.OPENAI,
            apiKey = "sk-new",
            model = "gpt-4",
            baseUrl = server.url("/v1").toString().removeSuffix("/")
        )
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        client.chat(listOf(Message(role = "user", content = "Hi")))
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-new", recorded.getHeader("Authorization"))
    }

    @Test
    fun `tool message serialization includes tool_call_id`() = runBlocking {
        enqueueJson(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        )
        val toolMsg = Message(role = "tool", content = "tool result", toolCallId = "call_xyz")
        client.chat(listOf(Message(role = "user", content = "Hi"), toolMsg))

        val bodyJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = bodyJson["messages"]?.jsonArray!!
        val toolMsgJson = messages[1].jsonObject
        assertEquals("call_xyz", toolMsgJson["tool_call_id"]?.jsonPrimitive?.content)
    }
}