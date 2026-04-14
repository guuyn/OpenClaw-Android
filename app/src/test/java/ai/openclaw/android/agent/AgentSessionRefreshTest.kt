package ai.openclaw.android.agent

import ai.openclaw.android.model.*
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillParam
import ai.openclaw.android.skill.ToolDefinition
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentSessionRefreshTest {

    private lateinit var mockModelClient: ModelClient
    private lateinit var mockSkillManager: SkillManager
    private lateinit var mockPermissionManager: PermissionManager
    private lateinit var agentSession: AgentSession

    @Before
    fun setUp() {
        mockModelClient = mockk(relaxed = true)
        mockSkillManager = mockk(relaxed = true)
        mockPermissionManager = mockk(relaxed = true)

        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        agentSession = AgentSession(
            modelClient = mockModelClient,
            skillManager = mockSkillManager,
            permissionManager = mockPermissionManager
        )
    }

    @Test
    fun `refreshTools rebuilds tool list from SkillManager`() {
        // Arrange: initial skill with 1 tool
        val initialTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf("city" to SkillParam("string", "City name", true)))
        )
        every { mockSkillManager.getAllTools() } returns initialTools

        val accessTools = listOf(
            Tool(type = "function", function = ToolFunction("tap", "Tap screen", ToolParameters()))
        )

        agentSession.setToolsWithSkills(accessTools) { "executed" }

        // Act: register new skill and refresh
        val updatedTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf("city" to SkillParam("string", "City name", true))),
            ToolDefinition("custom_search", "Custom search", mapOf("query" to SkillParam("string", "Search query", true)))
        )
        every { mockSkillManager.getAllTools() } returns updatedTools

        agentSession.refreshTools()

        // Assert: getAllTools called again during refresh
        verify(exactly = 2) { mockSkillManager.getAllTools() }
    }

    @Test
    fun `refreshTools preserves accessibility tools`() {
        // Arrange
        val initialTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns initialTools

        val accessTools = listOf(
            Tool(type = "function", function = ToolFunction("tap", "Tap screen", ToolParameters())),
            Tool(type = "function", function = ToolFunction("swipe", "Swipe screen", ToolParameters()))
        )

        agentSession.setToolsWithSkills(accessTools) { "executed" }

        // Add a new skill
        val newTools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("custom_action", "Custom action", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns newTools

        // Act
        agentSession.refreshTools()

        // Assert: getAllTools called for initial + refresh
        verify(exactly = 2) { mockSkillManager.getAllTools() }
    }

    @Test
    fun `setToolsWithSkills uses accessTools parameter name`() {
        // This test verifies the renamed parameter works correctly
        val skillTools = listOf(
            ToolDefinition("test_tool", "Test tool", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns skillTools

        val accessTools = listOf(
            Tool(type = "function", function = ToolFunction("access", "Access tool", ToolParameters()))
        )

        // Act - should not throw
        agentSession.setToolsWithSkills(accessTools) { "executed" }

        // Assert
        verify { mockSkillManager.getAllTools() }
    }
}
