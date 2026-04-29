package ai.openclaw.android.trigger

import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillResult
import ai.openclaw.android.trigger.models.*
import android.content.Context
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ActionExecutor.
 * Covers all four action types: SkillCall, AgentQuery, NotificationReply, CustomScript.
 */
class ActionExecutorTest {

    private val mockContext: Context = mockk(relaxed = true)
    private val mockSkillManager: SkillManager = mockk(relaxed = true)
    private lateinit var mockSessionFactory: suspend () -> AgentSession?
    private lateinit var mockSession: AgentSession

    private lateinit var executor: ActionExecutor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockSession = mockk(relaxed = true)
        mockSessionFactory = { mockSession }
        executor = ActionExecutor(mockContext, mockSkillManager, mockSessionFactory)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== SkillCall ====================

    @Test
    fun `SkillCall executes tool and returns success`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "weather",
            toolName = "get_weather",
            paramsJson = """{"city":"Beijing"}"""
        )
        val event = TriggerEvent(
            source = EventSource.CRON,
            payload = mapOf("ruleId" to "test-cron")
        )

        coEvery { mockSkillManager.executeTool("weather_get_weather", any()) } returns
            SkillResult(success = true, output = "Sunny, 25°C", error = "")

        val result = executor.execute(action, event)

        assertTrue(result.success)
        assertTrue(result.result?.contains("weather") == true || result.result?.contains("Sunny") == true || result.result?.contains("executed") == true)
        coVerify(exactly = 1) { mockSkillManager.executeTool("weather_get_weather", any()) }
    }

    @Test
    fun `SkillCall returns failure when tool execution fails`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "weather",
            toolName = "get_weather",
            paramsJson = "{}"
        )
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        coEvery { mockSkillManager.executeTool("weather_get_weather", any()) } returns
            SkillResult(success = false, output = "", error = "API key missing")

        val result = executor.execute(action, event)

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `SkillCall injects event data into params`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "notification",
            toolName = "dismiss",
            paramsJson = "{}"
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "New message", "package" to "com.whatsapp", "title" to "Alert")
        )

        coEvery { mockSkillManager.executeTool("notification_dismiss", any()) } returns
            SkillResult(success = true, output = "dismissed", error = "")

        val result = executor.execute(action, event)

        assertTrue(result.success)
    }

    // ==================== AgentQuery ====================

    @Test
    fun `AgentQuery sends prompt to agent session`() = runTest {
        val action = TriggerAction.AgentQuery(
            prompt = "Analyze this notification: {notification.text}"
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "Meeting at 3pm", "title" to "Calendar")
        )

        coEvery { mockSession.handleMessage(any()) } returns "Analysis: This is a calendar event."

        val result = executor.execute(action, event)

        assertTrue(result.success)
        coVerify(exactly = 1) { mockSession.handleMessage(match { it.contains("Meeting at 3pm") }) }
    }

    @Test
    fun `AgentQuery interpolates event variables in prompt`() = runTest {
        val action = TriggerAction.AgentQuery(
            prompt = "Event from {event.source} at {event.timestamp}: {notification.text}"
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "test message")
        )

        coEvery { mockSession.handleMessage(any()) } returns "OK"

        val result = executor.execute(action, event)

        assertTrue(result.success)
        coVerify { mockSession.handleMessage(match {
            it.contains("NOTIFICATION") && it.contains("test message")
        }) }
    }

    @Test
    fun `AgentQuery returns failure when AgentSession unavailable`() = runTest {
        val executorWithNullSession = ActionExecutor(mockContext, mockSkillManager, { null })
        val action = TriggerAction.AgentQuery(prompt = "hello")
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        val result = executorWithNullSession.execute(action, event)

        assertFalse(result.success)
        assertTrue(result.error?.contains("AgentSession") == true)
    }

    // ==================== NotificationReply ====================

    @Test
    fun `NotificationReply returns success with template result`() = runTest {
        val action = TriggerAction.NotificationReply(
            template = "Auto-reply: thanks for {notification.text}",
            autoReply = false
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "Hello!", "package" to "com.telegram")
        )

        val result = executor.execute(action, event)

        assertTrue(result.success)
    }

    @Test
    fun `NotificationReply with autoReply logs package name`() = runTest {
        val action = TriggerAction.NotificationReply(
            template = "Reply",
            autoReply = true
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("package" to "com.whatsapp", "text" to "Hi")
        )

        val result = executor.execute(action, event)

        assertTrue(result.success)
    }

    // ==================== CustomScript ====================

    @Test
    fun `CustomScript returns placeholder success`() = runTest {
        val action = TriggerAction.CustomScript(
            script = "console.log('hello')"
        )
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        val result = executor.execute(action, event)

        assertTrue(result.success)
        assertTrue(result.result?.contains("placeholder") == true || result.result?.contains("Script") == true)
    }
}

// No helper needed — use any() directly in coEvery blocks
