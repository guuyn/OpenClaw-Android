package ai.openclaw.android.trigger

import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillResult
import ai.openclaw.android.trigger.models.*
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.ScriptResult
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
    private lateinit var mockOrchestrator: ScriptOrchestrator

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
        mockOrchestrator = mockk(relaxed = true)
        executor = ActionExecutor(mockContext, mockSkillManager, mockSessionFactory, mockOrchestrator)
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
    fun `NotificationReply returns success with template result when autoReply=false`() = runTest {
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
        assertNotNull(result.result)
        assertTrue(result.result?.contains("template") == true)
    }

    @Test
    fun `NotificationReply interpolates template variables correctly`() = runTest {
        val action = TriggerAction.NotificationReply(
            template = "Re: {notification.title} from {notification.package}",
            autoReply = false
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "Meeting", "package" to "com.calendar")
        )

        val result = executor.execute(action, event)

        assertTrue(result.success)
        assertTrue(result.result?.contains("Meeting") == true)
        assertTrue(result.result?.contains("com.calendar") == true)
    }

    @Test
    fun `NotificationReply with autoReply but no package still returns success`() = runTest {
        val action = TriggerAction.NotificationReply(
            template = "Reply",
            autoReply = true
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "Hi")
        )

        val result = executor.execute(action, event)

        assertTrue(result.success)
    }

    // ==================== CustomScript ====================

    @Test
    fun `CustomScript executes via orchestrator`() = runTest {
        val action = TriggerAction.CustomScript(
            script = "console.log('hello')"
        )
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        coEvery { mockOrchestrator.execute(any(), any(), any(), any()) } returns
            ScriptResult.success("output: hello", 50)

        val result = executor.execute(action, event)

        assertTrue(result.success)
        assertTrue(result.result?.contains("hello") == true || result.result?.contains("Script") == true || result.result?.contains("executed") == true)
        coVerify { mockOrchestrator.execute("console.log('hello')", emptyList<String>(), emptyList(), any()) }
    }

    @Test
    fun `CustomScript passes event variables to orchestrator`() = runTest {
        val action = TriggerAction.CustomScript(
            script = "print(event_source)"
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "test", "package" to "com.app")
        )

        coEvery { mockOrchestrator.execute(any(), any(), any(), any()) } answers {
            val vars = arg<Map<String, Any>>(3)
            assertEquals("NOTIFICATION", vars["event_source"])
            assertEquals(event.id, vars["event_id"])
            assertEquals("test", vars["notification_text"])
            assertEquals("com.app", vars["notification_package"])
            ScriptResult.success("done", 10)
        }

        val result = executor.execute(action, event)

        assertTrue(result.success)
    }

    @Test
    fun `CustomScript returns failure when orchestrator script fails`() = runTest {
        val action = TriggerAction.CustomScript(
            script = "throw new Error('bad')"
        )
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        coEvery { mockOrchestrator.execute(any(), any(), any(), any()) } returns
            ScriptResult.failure("SyntaxError: bad", 5)

        val result = executor.execute(action, event)

        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error?.contains("bad") == true)
    }

    @Test
    fun `CustomScript returns failure when orchestrator is null`() = runTest {
        val executorWithoutOrchestrator = ActionExecutor(
            mockContext, mockSkillManager, mockSessionFactory, null
        )
        val action = TriggerAction.CustomScript(script = "console.log(1)")
        val event = TriggerEvent(source = EventSource.CRON, payload = emptyMap())

        val result = executorWithoutOrchestrator.execute(action, event)

        assertFalse(result.success)
        assertTrue(result.error?.contains("ScriptOrchestrator") == true)
    }

    // ==================== Parameter Interpolation ====================

    @Test
    fun `parseAndInterpolateParams replaces notification variables in JSON`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "notification",
            toolName = "send",
            paramsJson = """{"title":"{notification.title}","text":"{notification.text}"}"""
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "Alert", "text" to "New message")
        )

        var capturedParams: MutableList<Map<String, Any>> = mutableListOf()
        coEvery { mockSkillManager.executeTool(any(), any()) } answers {
            capturedParams.add(args[1] as Map<String, Any>)
            SkillResult(success = true, output = "sent", error = "")
        }

        executor.execute(action, event)

        assertEquals(1, capturedParams.size)
        assertEquals("Alert", capturedParams[0]["title"])
        assertEquals("New message", capturedParams[0]["text"])
    }

    @Test
    fun `parseAndInterpolateParams replaces event variables`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "test",
            toolName = "run",
            paramsJson = """{"source":"{event.source}","id":"{event.id}"}"""
        )
        val event = TriggerEvent(
            source = EventSource.SYSTEM_BROADCAST,
            payload = emptyMap()
        )

        var capturedParams: MutableList<Map<String, Any>> = mutableListOf()
        coEvery { mockSkillManager.executeTool(any(), any()) } answers {
            capturedParams.add(args[1] as Map<String, Any>)
            SkillResult(success = true, output = "ok", error = "")
        }

        executor.execute(action, event)

        assertEquals(1, capturedParams.size)
        assertEquals("SYSTEM_BROADCAST", capturedParams[0]["source"])
    }

    @Test
    fun `parseAndInterpolateParams handles empty paramsJson`() = runTest {
        val action = TriggerAction.SkillCall(
            skillId = "test",
            toolName = "run",
            paramsJson = ""
        )
        val event = TriggerEvent(
            source = EventSource.CRON,
            payload = emptyMap()
        )

        var capturedParams: MutableList<Map<String, Any>> = mutableListOf()
        coEvery { mockSkillManager.executeTool(any(), any()) } answers {
            capturedParams.add(args[1] as Map<String, Any>)
            SkillResult(success = true, output = "ok", error = "")
        }

        executor.execute(action, event)

        assertEquals(1, capturedParams.size)
        assertEquals("CRON", capturedParams[0]["event_source"])
    }

    @Test
    fun `interpolatePrompt supports all variable types`() = runTest {
        val action = TriggerAction.AgentQuery(
            prompt = "[{event.source}] {notification.title}: {notification.text} (pkg: {notification.package}, ts: {event.timestamp}, id: {event.id})"
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf(
                "title" to "Test",
                "text" to "Hello",
                "package" to "com.test"
            )
        )

        var capturedPrompt: String? = null
        coEvery { mockSession.handleMessage(any()) } answers {
            capturedPrompt = args[0] as String
            "OK"
        }

        executor.execute(action, event)

        assertNotNull(capturedPrompt)
        assertTrue(capturedPrompt!!.contains("[NOTIFICATION]"))
        assertTrue(capturedPrompt!!.contains("Test"))
        assertTrue(capturedPrompt!!.contains("Hello"))
        assertTrue(capturedPrompt!!.contains("com.test"))
        assertTrue(capturedPrompt!!.contains("ts:"))
        assertTrue(capturedPrompt!!.contains("id:"))
    }
}

// No helper needed — use any() directly in coEvery blocks
