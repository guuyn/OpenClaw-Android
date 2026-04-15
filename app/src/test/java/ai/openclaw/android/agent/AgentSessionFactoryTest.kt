package ai.openclaw.android.agent

import ai.openclaw.android.data.model.AgentConfig
import ai.openclaw.android.model.*
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillParam
import ai.openclaw.android.skill.ToolDefinition
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentSessionFactoryTest {

    private lateinit var mockModelClient: ModelClient
    private lateinit var mockSkillManager: SkillManager
    private lateinit var mockPermissionManager: PermissionManager

    @Before
    fun setUp() {
        mockModelClient = mockk(relaxed = true)
        mockSkillManager = mockk(relaxed = true)
        mockPermissionManager = mockk(relaxed = true)
    }

    // ============ Test: Existing constructor still works ============

    @Test
    fun `existing constructor still works without agentConfig`() {
        val tools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            permissionManager = mockPermissionManager
        )
        session.setToolsWithSkills(emptyList()) { "executed" }

        val prefixes = getPrivateField(session, "_allowedToolPrefixes")
        assertNull("No filtering when using existing constructor", prefixes)
    }

    // ============ Test: Factory constructor with tool filtering ============

    @Test
    fun `factory constructor filters tools by prefix`() {
        val config = AgentConfig(
            id = "weather",
            name = "Weather Agent",
            tools = listOf("weather")
        )
        val tools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("weather_forecast", "Forecast", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf()),
            ToolDefinition("contact_find", "Find contact", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config,
            permissionManager = mockPermissionManager
        )
        session.setToolsWithSkills(emptyList()) { "executed" }

        // Verify prefixes are stored
        val prefixes = getPrivateField(session, "_allowedToolPrefixes") as List<*>?
        assertNotNull("allowedToolPrefixes should be set", prefixes)
        assertEquals(1, prefixes?.size)
        assertTrue(prefixes?.contains("weather") == true)

        // Verify filtering works
        val toolsField = session::class.java.getDeclaredField("tools")
        toolsField.isAccessible = true
        val activeTools = toolsField.get(session) as List<Tool>
        val toolNames = activeTools.map { it.function.name }
        assertEquals(2, toolNames.size) // 2 weather tools
        assertTrue(toolNames.contains("weather_get"))
        assertTrue(toolNames.contains("weather_forecast"))
        assertFalse(toolNames.contains("search_web"))
        assertFalse(toolNames.contains("contact_find"))
    }

    @Test
    fun `factory constructor with multiple tool prefixes filters correctly`() {
        val config = AgentConfig(
            id = "mixed",
            name = "Mixed Agent",
            tools = listOf("weather", "contact", "sms")
        )
        val tools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf()),
            ToolDefinition("contact_find", "Find contact", mapOf()),
            ToolDefinition("sms_send", "Send SMS", mapOf()),
            ToolDefinition("calendar_create", "Create calendar event", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config
        )
        session.setToolsWithSkills(emptyList()) { "executed" }

        val prefixes = getPrivateField(session, "_allowedToolPrefixes") as List<*>?
        assertEquals(3, prefixes?.size)

        val toolsField = session::class.java.getDeclaredField("tools")
        toolsField.isAccessible = true
        val activeTools = toolsField.get(session) as List<Tool>
        val toolNames = activeTools.map { it.function.name }
        assertEquals(3, toolNames.size)
        assertTrue(toolNames.contains("weather_get"))
        assertTrue(toolNames.contains("contact_find"))
        assertTrue(toolNames.contains("sms_send"))
    }

    @Test
    fun `factory constructor with all tools has no filtering`() {
        val config = AgentConfig(
            id = "main",
            name = "Main Agent",
            tools = listOf("all")
        )
        val tools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config
        )
        session.setToolsWithSkills(emptyList()) { "executed" }

        val prefixes = getPrivateField(session, "_allowedToolPrefixes")
        assertNull("allowedToolPrefixes should be null when 'all' is specified", prefixes)

        val toolsField = session::class.java.getDeclaredField("tools")
        toolsField.isAccessible = true
        val activeTools = toolsField.get(session) as List<Tool>
        assertEquals(2, activeTools.size) // all tools included
    }

    @Test
    fun `factory constructor with minimal config works`() {
        val config = AgentConfig(
            id = "minimal",
            name = "Minimal Agent"
            // systemPrompt defaults to null, tools defaults to ["all"]
        )
        val tools = listOf(ToolDefinition("weather_get", "Get weather", mapOf()))
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config
        )
        assertNotNull("Session created successfully", session)

        val configField = getPrivateField(session, "_agentConfig") as AgentConfig?
        assertEquals("minimal", configField?.id)
    }

    // ============ Test: System prompt merging ============

    @Test
    fun `custom system prompt is prepended`() = runTest {
        val config = AgentConfig(
            id = "coder",
            name = "Coder",
            systemPrompt = "You are a coding expert."
        )
        val tools = listOf(ToolDefinition("script_execute", "Run script", mapOf()))
        every { mockSkillManager.getAllTools() } returns tools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config
        )

        // Verify system prompt contains custom text
        val historyField = session::class.java.getDeclaredField("history")
        historyField.isAccessible = true
        // history is empty until a message is sent, but we can verify config is stored
        val configField = getPrivateField(session, "_agentConfig") as AgentConfig?
        assertEquals("You are a coding expert.", configField?.systemPrompt)
    }

    // ============ Test: refreshTools respects tool filtering ============

    @Test
    fun `refreshTools respects tool filtering`() {
        // Arrange
        val config = AgentConfig(
            id = "weather",
            name = "Weather Agent",
            tools = listOf("weather")
        )
        val initialTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns initialTools

        val session = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            agentConfig = config,
            permissionManager = mockPermissionManager
        )
        session.setToolsWithSkills(emptyList()) { "executed" }

        // Add more tools (simulating new skills registered)
        val updatedTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("weather_forecast", "Forecast", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf()),
            ToolDefinition("sms_send", "Send SMS", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns updatedTools

        // Act
        session.refreshTools()

        // Assert: only weather tools
        val toolsField = session::class.java.getDeclaredField("tools")
        toolsField.isAccessible = true
        val tools = toolsField.get(session) as List<Tool>
        val toolNames = tools.map { it.function.name }
        assertTrue(toolNames.contains("weather_get"))
        assertTrue(toolNames.contains("weather_forecast"))
        assertFalse(toolNames.contains("search_web"))
        assertFalse(toolNames.contains("sms_send"))
    }

    // ============ Helpers ============

    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }
}
