package ai.openclaw.android.domain.agent

import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.data.model.AgentConfig
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.Tool
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.ToolDefinition
import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AgentSessionManager.
 *
 * Uses a test subclass that overrides createModelClient() to return a mock,
 * avoiding Android runtime dependencies (ConfigManager, BailianClient).
 */
class AgentSessionManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockConfigManager: AgentConfigManager
    private lateinit var mockSkillManager: SkillManager
    private lateinit var mockAccessibilityBridge: AccessibilityBridge
    private lateinit var mockPermissionManager: PermissionManager
    private lateinit var mockModelClient: ModelClient

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockContext = mockk(relaxed = true)
        mockConfigManager = mockk(relaxed = true)
        mockSkillManager = mockk(relaxed = true)
        mockAccessibilityBridge = mockk(relaxed = true)
        mockPermissionManager = mockk(relaxed = true)
        mockModelClient = mockk(relaxed = true)

        // Setup common skill manager mock
        val tools = listOf(
            ToolDefinition("weather_get", "Get weather", mapOf()),
            ToolDefinition("search_web", "Web search", mapOf())
        )
        every { mockSkillManager.getAllTools() } returns tools
        every { mockAccessibilityBridge.getTools() } returns emptyList()
        coEvery { mockAccessibilityBridge.execute(any()) } returns "executed"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ============ Helper: create manager with test subclass ============

    private fun createManager(maxCachedSessions: Int = 3): TestableAgentSessionManager {
        return TestableAgentSessionManager(
            context = mockContext,
            configManager = mockConfigManager,
            skillManager = mockSkillManager,
            accessibilityBridge = mockAccessibilityBridge,
            permissionManager = mockPermissionManager,
            maxCachedSessions = maxCachedSessions,
            mockModelClient = mockModelClient
        )
    }

    // ============ Test: getOrCreate creates session with correct config ============

    @Test
    fun `getOrCreate creates session with correct agent config`() {
        val config = AgentConfig(
            id = "coder",
            name = "Coder Agent",
            model = "bailian/qwen3.5-coder"
        )
        every { mockConfigManager.getAgentById("coder") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        val session = manager.getOrCreate("coder")

        assertNotNull(session)
        assertEquals("coder", manager.getActiveAgentIds().first())
    }

    @Test
    fun `getOrCreate falls back to default agent when ID not found`() {
        val defaultConfig = AgentConfig(
            id = "main",
            name = "Main Agent",
            isDefault = true
        )
        every { mockConfigManager.getAgentById("unknown") } returns null
        every { mockConfigManager.getDefaultAgent() } returns defaultConfig

        val manager = createManager()
        val session = manager.getOrCreate("unknown")

        assertNotNull(session)
        verify(exactly = 1) { mockConfigManager.getAgentById("unknown") }
        verify(exactly = 1) { mockConfigManager.getDefaultAgent() }
    }

    // ============ Test: getOrCreate returns cached session on second call ============

    @Test
    fun `getOrCreate returns same cached instance on second call`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        val session1 = manager.getOrCreate("main")
        val session2 = manager.getOrCreate("main")

        assertSame("Should return same cached instance", session1, session2)
    }

    @Test
    fun `getOrCreate does not call configManager on cache hit`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config

        val manager = createManager()
        manager.getOrCreate("main") // first call: creates
        clearMocks(mockConfigManager) // clear invocation counts
        every { mockConfigManager.getAgentById("main") } returns config

        manager.getOrCreate("main") // second call: cache hit

        verify(exactly = 0) { mockConfigManager.getAgentById(any()) }
        verify(exactly = 0) { mockConfigManager.getDefaultAgent() }
    }

    // ============ Test: LRU eviction when maxCachedSessions exceeded ============

    @Test
    fun `LRU eviction removes oldest session when cache is full`() {
        val configs = mapOf(
            "agent1" to AgentConfig(id = "agent1", name = "Agent 1"),
            "agent2" to AgentConfig(id = "agent2", name = "Agent 2"),
            "agent3" to AgentConfig(id = "agent3", name = "Agent 3"),
            "agent4" to AgentConfig(id = "agent4", name = "Agent 4")
        )
        configs.forEach { (id, config) ->
            every { mockConfigManager.getAgentById(id) } returns config
        }
        every { mockConfigManager.getDefaultAgent() } returns configs["agent1"]!!

        // maxCachedSessions = 3 (default)
        val manager = createManager(maxCachedSessions = 3)

        // Fill cache to capacity
        manager.getOrCreate("agent1")
        manager.getOrCreate("agent2")
        manager.getOrCreate("agent3")
        assertEquals(3, manager.getActiveAgentIds().size)

        // Adding 4th should evict agent1 (oldest)
        manager.getOrCreate("agent4")

        assertEquals(3, manager.getActiveAgentIds().size)
        assertFalse("agent1 should be evicted", manager.getActiveAgentIds().contains("agent1"))
        assertTrue("agent4 should be in cache", manager.getActiveAgentIds().contains("agent4"))
    }

    @Test
    fun `LRU eviction respects access order (touch moves to end)`() {
        val configs = mapOf(
            "agent1" to AgentConfig(id = "agent1", name = "Agent 1"),
            "agent2" to AgentConfig(id = "agent2", name = "Agent 2"),
            "agent3" to AgentConfig(id = "agent3", name = "Agent 3"),
            "agent4" to AgentConfig(id = "agent4", name = "Agent 4")
        )
        configs.forEach { (id, config) ->
            every { mockConfigManager.getAgentById(id) } returns config
        }
        every { mockConfigManager.getDefaultAgent() } returns configs["agent1"]!!

        val manager = createManager(maxCachedSessions = 3)

        // Fill cache
        manager.getOrCreate("agent1")
        manager.getOrCreate("agent2")
        manager.getOrCreate("agent3")

        // Access agent1 again — should move to most-recently-used
        manager.getOrCreate("agent1")

        // Now agent2 is oldest. Adding agent4 should evict agent2
        manager.getOrCreate("agent4")

        assertEquals(3, manager.getActiveAgentIds().size)
        assertFalse("agent2 should be evicted (oldest after touch)", manager.getActiveAgentIds().contains("agent2"))
        assertTrue("agent1 should survive (touched)", manager.getActiveAgentIds().contains("agent1"))
    }

    // ============ Test: evict() removes session ============

    @Test
    fun `evict removes session from cache`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        manager.getOrCreate("main")
        assertTrue("main should be in cache", manager.getActiveAgentIds().contains("main"))

        manager.evict("main")
        assertFalse("main should be evicted", manager.getActiveAgentIds().contains("main"))
    }

    @Test
    fun `evict is idempotent (no crash on double evict)`() {
        val manager = createManager()
        manager.evict("nonexistent") // Should not throw
        manager.evict("nonexistent") // Should not throw again
    }

    @Test
    fun `evict allows re-creation of session`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        val session1 = manager.getOrCreate("main")
        manager.evict("main")
        val session2 = manager.getOrCreate("main")

        assertNotSame("Should create new session after evict", session1, session2)
    }

    // ============ Test: getActiveAgentIds() returns correct list ============

    @Test
    fun `getActiveAgentIds returns empty list when no sessions`() {
        val manager = createManager()
        assertTrue(manager.getActiveAgentIds().isEmpty())
    }

    @Test
    fun `getActiveAgentIds returns IDs in access order`() {
        val configs = mapOf(
            "alpha" to AgentConfig(id = "alpha", name = "Alpha"),
            "beta" to AgentConfig(id = "beta", name = "Beta"),
            "gamma" to AgentConfig(id = "gamma", name = "Gamma")
        )
        configs.forEach { (id, config) ->
            every { mockConfigManager.getAgentById(id) } returns config
        }
        every { mockConfigManager.getDefaultAgent() } returns configs["alpha"]!!

        val manager = createManager()
        manager.getOrCreate("alpha")
        manager.getOrCreate("beta")
        manager.getOrCreate("gamma")

        val ids = manager.getActiveAgentIds()
        assertEquals(listOf("alpha", "beta", "gamma"), ids)
    }

    @Test
    fun `getActiveAgentIds returns a defensive copy`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        manager.getOrCreate("main")

        val ids1 = manager.getActiveAgentIds()
        manager.getOrCreate("other") // This won't work since "other" not in config, but list shouldn't change

        // ids1 should not have been modified by subsequent operations
        // (defensive copy check — the list should be a snapshot)
        assertEquals(1, ids1.size)
    }

    // ============ Test: cleanup() clears all sessions ============

    @Test
    fun `cleanup clears all sessions`() {
        val configs = mapOf(
            "agent1" to AgentConfig(id = "agent1", name = "Agent 1"),
            "agent2" to AgentConfig(id = "agent2", name = "Agent 2")
        )
        configs.forEach { (id, config) ->
            every { mockConfigManager.getAgentById(id) } returns config
        }
        every { mockConfigManager.getDefaultAgent() } returns configs["agent1"]!!

        val manager = createManager()
        manager.getOrCreate("agent1")
        manager.getOrCreate("agent2")
        assertEquals(2, manager.getActiveAgentIds().size)

        manager.cleanup()
        assertTrue(manager.getActiveAgentIds().isEmpty())
    }

    @Test
    fun `cleanup allows new sessions to be created afterwards`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager()
        val session1 = manager.getOrCreate("main")
        manager.cleanup()
        val session2 = manager.getOrCreate("main")

        assertNotSame("Should create fresh session after cleanup", session1, session2)
    }

    // ============ Test: maxCachedSessions = 1 (extreme case) ============

    @Test
    fun `maxCachedSessions of 1 keeps only latest session`() {
        val configs = mapOf(
            "a" to AgentConfig(id = "a", name = "A"),
            "b" to AgentConfig(id = "b", name = "B"),
            "c" to AgentConfig(id = "c", name = "C")
        )
        configs.forEach { (id, config) ->
            every { mockConfigManager.getAgentById(id) } returns config
        }
        every { mockConfigManager.getDefaultAgent() } returns configs["a"]!!

        val manager = createManager(maxCachedSessions = 1)
        manager.getOrCreate("a")
        assertEquals(listOf("a"), manager.getActiveAgentIds())

        manager.getOrCreate("b")
        assertEquals(listOf("b"), manager.getActiveAgentIds())

        manager.getOrCreate("c")
        assertEquals(listOf("c"), manager.getActiveAgentIds())
    }

    // ============ Test: maxCachedSessions = 0 (edge case, no caching) ============

    @Test
    fun `maxCachedSessions of 0 creates new session each time`() {
        val config = AgentConfig(id = "main", name = "Main")
        every { mockConfigManager.getAgentById("main") } returns config
        every { mockConfigManager.getDefaultAgent() } returns config

        val manager = createManager(maxCachedSessions = 0)
        val session1 = manager.getOrCreate("main")
        val session2 = manager.getOrCreate("main")

        assertNotSame("Should always create new session with 0 cache", session1, session2)
    }

    // ============ Testable subclass ============

    /**
     * Test subclass that overrides createModelClient to return a mock.
     * This avoids Android runtime dependencies (ConfigManager, BailianClient).
     */
    private class TestableAgentSessionManager(
        context: Context,
        configManager: AgentConfigManager,
        skillManager: SkillManager,
        accessibilityBridge: AccessibilityBridge?,
        permissionManager: PermissionManager? = null,
        maxCachedSessions: Int = 3,
        private val mockModelClient: ModelClient
    ) : AgentSessionManager(
        context, configManager, skillManager, accessibilityBridge,
        permissionManager, maxCachedSessions
    ) {
        override fun createModelClient(config: AgentConfig): ModelClient {
            return mockModelClient
        }
    }
}
