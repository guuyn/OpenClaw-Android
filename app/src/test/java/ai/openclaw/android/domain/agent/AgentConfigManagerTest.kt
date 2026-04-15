package ai.openclaw.android.domain.agent

import ai.openclaw.android.data.model.AgentConfig
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * Unit tests for AgentConfigManager.
 * Uses relaxed mocks for Context + AssetManager to avoid real Android dependencies.
 */
class AgentConfigManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockAssetManager: AssetManager
    private lateinit var manager: AgentConfigManager

    private val testAgentsJson = """
        {
          "agents": [
            {
              "id": "main",
              "name": "OpenClaw",
              "model": "bailian/qwen3.5-plus",
              "systemPrompt": "你是 AI 助手",
              "tools": ["all"],
              "isDefault": true
            },
            {
              "id": "coder",
              "name": "Coder",
              "model": "bailian/qwen3.5-coder",
              "systemPrompt": "你是开发助手",
              "tools": ["script", "search"],
              "keywords": ["代码", "kotlin", "build", "gradle"]
            },
            {
              "id": "security",
              "name": "Security",
              "model": "bailian/qwen3.5-plus",
              "systemPrompt": "你是安全专家",
              "tools": ["search", "audit"],
              "keywords": ["安全", "漏洞", "audit", "token"]
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockAssetManager = mockk(relaxed = true)

        // Mock Log static methods
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        manager = AgentConfigManager(mockContext)
    }

    // ========== Load from Assets ==========

    @Test
    fun `loadFromAssets loads agents correctly`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream

        // Act
        val agents = manager.loadFromAssets()

        // Assert
        assertEquals(3, agents.size)
        assertEquals("main", agents[0].id)
        assertEquals("OpenClaw", agents[0].name)
        assertEquals("coder", agents[1].id)
        assertEquals("security", agents[2].id)
    }

    @Test
    fun `loadFromAssets returns empty list when file not found`() {
        // Arrange
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } throws FileNotFoundException("agents.json not found")

        // Act
        val agents = manager.loadFromAssets()

        // Assert
        assertTrue(agents.isEmpty())
    }

    // ========== Get Agent by ID ==========

    @Test
    fun `getAgentById returns correct agent`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act & Assert
        val mainAgent = manager.getAgentById("main")
        assertNotNull(mainAgent)
        assertEquals("OpenClaw", mainAgent!!.name)
        assertEquals("bailian/qwen3.5-plus", mainAgent.model)
        assertTrue(mainAgent.isDefault)

        val coderAgent = manager.getAgentById("coder")
        assertNotNull(coderAgent)
        assertEquals("Coder", coderAgent!!.name)
        assertEquals(4, coderAgent.keywords.size)
    }

    @Test
    fun `getAgentById returns null for nonexistent agent`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act
        val result = manager.getAgentById("nonexistent")

        // Assert
        assertNull(result)
    }

    @Test
    fun `getAgentById returns null before loading`() {
        // Act (no loadFromAssets called)
        val result = manager.getAgentById("main")

        // Assert
        assertNull(result)
    }

    // ========== Get Default Agent ==========

    @Test
    fun `getDefaultAgent returns the default agent`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act
        val defaultAgent = manager.getDefaultAgent()

        // Assert
        assertEquals("main", defaultAgent.id)
        assertTrue(defaultAgent.isDefault)
    }

    @Test
    fun `getDefaultAgent throws when no agents configured`() {
        // Act & Assert
        assertThrows(IllegalStateException::class.java) {
            manager.getDefaultAgent()
        }
    }

    // ========== Get All Agents ==========

    @Test
    fun `getAllAgents returns all loaded agents`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act
        val agents = manager.getAllAgents()

        // Assert
        assertEquals(3, agents.size)
    }

    @Test
    fun `getAllAgents returns empty list before loading`() {
        // Act
        val agents = manager.getAllAgents()

        // Assert
        assertTrue(agents.isEmpty())
    }

    // ========== Keyword Index ==========

    @Test
    fun `getKeywordIndex builds correct keyword-to-agentId mapping`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act
        val index = manager.getKeywordIndex()

        // Assert — coder keywords
        assertEquals("coder", index["代码"])
        assertEquals("coder", index["kotlin"])
        assertEquals("coder", index["build"])
        assertEquals("coder", index["gradle"])

        // Assert — security keywords
        assertEquals("security", index["安全"])
        assertEquals("security", index["漏洞"])
        assertEquals("security", index["audit"])
        assertEquals("security", index["token"])

        // Main has no keywords
        assertNull(index["all"])
    }

    @Test
    fun `getKeywordIndex is empty before loading`() {
        // Act
        val index = manager.getKeywordIndex()

        // Assert
        assertTrue(index.isEmpty())
    }

    // ========== Has Agent ==========

    @Test
    fun `hasAgent returns true for existing agent`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act & Assert
        assertTrue(manager.hasAgent("main"))
        assertTrue(manager.hasAgent("coder"))
        assertTrue(manager.hasAgent("security"))
    }

    @Test
    fun `hasAgent returns false for nonexistent agent`() {
        // Arrange
        val inputStream = ByteArrayInputStream(testAgentsJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream
        manager.loadFromAssets()

        // Act
        val result = manager.hasAgent("nonexistent")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasAgent returns false before loading`() {
        // Act
        val result = manager.hasAgent("main")

        // Assert
        assertFalse(result)
    }

    // ========== Duplicate Keyword Warning ==========

    @Test
    fun `duplicate keyword logs warning and first mapping wins`() {
        // Arrange: two agents sharing a keyword
        val duplicateJson = """
            {
              "agents": [
                {
                  "id": "alpha",
                  "name": "Alpha",
                  "keywords": ["shared", "unique-a"]
                },
                {
                  "id": "beta",
                  "name": "Beta",
                  "keywords": ["shared", "unique-b"]
                }
              ]
            }
        """.trimIndent()

        val inputStream = ByteArrayInputStream(duplicateJson.toByteArray())
        every { mockContext.assets } returns mockAssetManager
        every { mockAssetManager.open("agents.json") } returns inputStream

        // Capture Log.w calls
        val warningSlot = slot<String>()
        every { Log.w(any(), capture(warningSlot)) } returns 0

        // Act
        manager.loadFromAssets()

        // Assert — warning was logged for duplicate
        assertTrue(warningSlot.isCaptured)
        assertTrue(warningSlot.captured.contains("shared"))

        // Assert — first agent wins
        val index = manager.getKeywordIndex()
        assertEquals("alpha", index["shared"])
        assertEquals("alpha", index["unique-a"])
        assertEquals("beta", index["unique-b"])
    }
}
