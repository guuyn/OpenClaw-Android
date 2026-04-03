package ai.openclaw.android.agent

import ai.openclaw.android.model.*
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ai.openclaw.android.skill.SkillManager

/**
 * Instrumented test for AgentSession streaming flow.
 * Uses fake ModelClient — no Mockito needed.
 */
@RunWith(AndroidJUnit4::class)
class AgentSessionTest {

    private lateinit var context: Context
    private lateinit var skillManager: SkillManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        skillManager = SkillManager(context)
    }

    /** Create AgentSession with a fake streaming client */
    private fun createSession(
        streamFactory: (Int) -> Flow<ChatEvent>
    ): AgentSession {
        var callCount = 0
        val client = object : ModelClient {
            override suspend fun chat(messages: List<Message>, tools: List<Tool>?): Result<ModelResponse> {
                return Result.success(ModelResponse(
                    choices = listOf(Choice(message = ResponseMessage(role = "assistant", content = "fallback")))
                ))
            }
            override fun chatStream(messages: List<Message>, tools: List<Tool>?): Flow<ChatEvent> {
                return streamFactory(callCount++)
            }
            override fun configure(provider: ModelProvider, apiKey: String, model: String) {}
        }
        return AgentSession(client, skillManager)
    }

    // ===== Test 1: Simple text streaming =====

    @Test
    fun streaming_textOnly_emitsComplete() = runTest {
        val session = createSession { _ ->
            flow {
                emit(ChatEvent.Token("Hello"))
                emit(ChatEvent.Token(" world"))
                emit(ChatEvent.Complete(ModelResponse(
                    choices = listOf(Choice(
                        message = ResponseMessage(role = "assistant", content = "Hello world"),
                        finishReason = "stop"
                    ))
                )))
            }
        }
        val events = session.handleMessageStream("hi").toList()

        val tokens = events.filterIsInstance<SessionEvent.Token>()
        assertEquals(2, tokens.size)
        assertEquals("Hello", tokens[0].text)

        val complete = events.filterIsInstance<SessionEvent.Complete>()
        assertEquals(1, complete.size)
        assertEquals("Hello world", complete[0].fullText)
    }

    // ===== Test 2: Tool call → tool execution → final response =====

    @Test
    fun streaming_toolCall_executesToolAndCompletes() = runTest {
        val session = createSession { callCount ->
            if (callCount == 0) {
                flow {
                    emit(ChatEvent.Complete(ModelResponse(
                        id = "chatcmpl-test",
                        choices = listOf(Choice(
                            message = ResponseMessage(
                                role = "assistant",
                                content = null,
                                toolCalls = listOf(ToolCall(
                                    id = "call_1",
                                    type = "function",
                                    function = ToolCallFunction(name = "getWeather", arguments = "{\"location\":\"Beijing\"}")
                                ))
                            ),
                            finishReason = "tool_calls"
                        ))
                    )))
                }
            } else {
                flow {
                    emit(ChatEvent.Token("It's sunny"))
                    emit(ChatEvent.Complete(ModelResponse(
                        choices = listOf(Choice(
                            message = ResponseMessage(role = "assistant", content = "It's sunny"),
                            finishReason = "stop"
                        ))
                    )))
                }
            }
        }

        // "getWeather" has no underscore → goes to toolExecutor
        session.setTools(listOf(Tool(
            type = "function",
            function = ToolFunction(
                name = "getWeather",
                description = "get weather",
                parameters = ToolParameters()
            )
        ))) { _ -> "sunny, 25°C" }

        val events = session.handleMessageStream("天气怎么样").toList()

        val toolExecuting = events.filterIsInstance<SessionEvent.ToolExecuting>()
        assertEquals(1, toolExecuting.size)
        assertEquals("getWeather", toolExecuting[0].name)

        val toolResults = events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("sunny, 25°C", toolResults[0].result)

        val complete = events.filterIsInstance<SessionEvent.Complete>()
        assertEquals(1, complete.size)
        assertEquals("It's sunny", complete[0].fullText)

        val errors = events.filterIsInstance<SessionEvent.Error>()
        assertTrue("Should have no errors: $errors", errors.isEmpty())
    }

    // ===== Test 3: finish_reason="tool_calls" regression test =====

    @Test
    fun streaming_finishReasonToolCalls_triggersToolExecution() = runTest {
        val session = createSession { callCount ->
            if (callCount == 0) {
                flow {
                    emit(ChatEvent.Complete(ModelResponse(
                        id = "chatcmpl-regression",
                        choices = listOf(Choice(
                            index = 0,
                            message = ResponseMessage(
                                role = "assistant",
                                content = null,
                                toolCalls = listOf(ToolCall(
                                    id = "call_abc123",
                                    type = "function",
                                    function = ToolCallFunction(
                                        name = "getLocation",
                                        arguments = "{}"
                                    )
                                ))
                            ),
                            finishReason = "tool_calls"
                        ))
                    )))
                }
            } else {
                flow {
                    emit(ChatEvent.Token("Beijing, sunny"))
                    emit(ChatEvent.Complete(ModelResponse(
                        choices = listOf(Choice(
                            message = ResponseMessage(role = "assistant", content = "Beijing, sunny"),
                            finishReason = "stop"
                        ))
                    )))
                }
            }
        }

        session.setTools(listOf(Tool(
            type = "function",
            function = ToolFunction(name = "getLocation", description = "get GPS", parameters = ToolParameters())
        ))) { _ -> "Beijing, 39.9N 116.4E" }

        val events = session.handleMessageStream("天气怎么样").toList()

        val toolExecuting = events.filterIsInstance<SessionEvent.ToolExecuting>()
        assertEquals("Tool should execute (old bug would give 'No response')", 1, toolExecuting.size)

        val complete = events.filterIsInstance<SessionEvent.Complete>()
        assertEquals(1, complete.size)

        val errors = events.filterIsInstance<SessionEvent.Error>()
        assertTrue("Should have no errors: $errors", errors.isEmpty())
    }

    // ===== Test 4: Tokens but no Complete → still emits Complete =====

    @Test
    fun streaming_tokensNoComplete_emitsCompleteWithTokens() = runTest {
        val session = createSession { _ ->
            flow {
                emit(ChatEvent.Token("Partial "))
                emit(ChatEvent.Token("response"))
            }
        }
        val events = session.handleMessageStream("test").toList()

        val complete = events.filterIsInstance<SessionEvent.Complete>()
        assertEquals(1, complete.size)
        assertEquals("Partial response", complete[0].fullText)
    }

    // ===== Test 5: Empty stream → error =====

    @Test
    fun streaming_emptyStream_emitsError() = runTest {
        val session = createSession { _ ->
            flow { /* empty */ }
        }
        val events = session.handleMessageStream("test").toList()

        val errors = events.filterIsInstance<SessionEvent.Error>()
        assertEquals(1, errors.size)
        assertEquals("No response from model", errors[0].message)
    }

    // ===== Test 6: Multiple rounds maintain history =====

    @Test
    fun streaming_multipleRounds_maintainsHistory() = runTest {
        var streamCallCount = 0
        val client = object : ModelClient {
            override suspend fun chat(messages: List<Message>, tools: List<Tool>?): Result<ModelResponse> {
                return Result.success(ModelResponse(
                    choices = listOf(Choice(message = ResponseMessage(role = "assistant", content = "ok")))
                ))
            }
            override fun chatStream(messages: List<Message>, tools: List<Tool>?): Flow<ChatEvent> = flow {
                streamCallCount++
                emit(ChatEvent.Complete(ModelResponse(
                    choices = listOf(Choice(
                        message = ResponseMessage(role = "assistant", content = "response $streamCallCount"),
                        finishReason = "stop"
                    ))
                )))
            }
            override fun configure(provider: ModelProvider, apiKey: String, model: String) {}
        }

        val session = AgentSession(client, skillManager)
        session.handleMessageStream("first").toList()
        session.handleMessageStream("second").toList()

        val history = session.getHistory()
        assertEquals(4, history.size)
        assertEquals("first", history[0].content)
        assertEquals("response 1", history[1].content)
        assertEquals("second", history[2].content)
        assertEquals("response 2", history[3].content)
    }

    // ===== Test 7: Model error propagates =====

    @Test
    fun streaming_modelError_propagates() = runTest {
        val session = createSession { _ ->
            flow {
                emit(ChatEvent.Error("API rate limit exceeded"))
            }
        }
        val events = session.handleMessageStream("test").toList()

        val errors = events.filterIsInstance<SessionEvent.Error>()
        assertTrue("Should have at least 1 error", errors.isNotEmpty())
        assertEquals("API rate limit exceeded", errors[0].message)
    }
}
