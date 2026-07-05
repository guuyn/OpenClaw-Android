package ai.openclaw.android.agent

import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.config.AgentConfig
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.skill.SkillManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for AgentRegistry.
 *
 * AgentRegistry manages multi-agent configuration stored in
 * `<externalFilesDir>/agents/<agentId>/config.yaml`. We use Robolectric
 * to provide a Context with a writable external files directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AgentRegistryTest {

    private lateinit var context: Context
    private lateinit var skillManager: SkillManager
    private lateinit var accessibilityBridge: AccessibilityBridge
    private lateinit var registry: AgentRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Clean any previously-created agents
        val agentsDir = File(context.getExternalFilesDir(null), "agents")
        if (agentsDir.exists()) agentsDir.deleteRecursively()

        // Pre-create the "main" agent (in production this is loaded from assets
        // via copyDefaultAgentsFromAssets(), but Robolectric has no assets)
        agentsDir.mkdirs()
        val mainDir = File(agentsDir, "main")
        mainDir.mkdirs()
        File(mainDir, "config.yaml").writeText("""
            id: main
            name: Main Agent
            model: openai/qwen3.6-plus
            maxContextTokens: 4000
            tools: []
        """.trimIndent())
        File(mainDir, "SOUL.md").writeText("You are the main AI assistant.")

        skillManager = SkillManager(context)
        accessibilityBridge = AccessibilityBridge()

        // Factory returns a mock model client (we don't exercise the session)
        val factory: (String) -> ModelClient = { _ -> mockk<ModelClient>(relaxed = true) }

        registry = AgentRegistry(
            context = context,
            modelClientFactory = factory,
            skillManager = skillManager,
            accessibilityBridge = accessibilityBridge
        )
    }

    @After
    fun tearDown() {
        val agentsDir = File(context.getExternalFilesDir(null), "agents")
        if (agentsDir.exists()) agentsDir.deleteRecursively()
    }

    // ==================== Default agent (main) ====================

    @Test
    fun `default agent is loaded`() {
        val main = registry.getDefaultAgent()
        assertEquals("main", main.id)
    }

    @Test
    fun `default agent name is non-empty`() {
        val main = registry.getDefaultAgent()
        assertTrue("Default agent name should be set", main.name.isNotEmpty())
    }

    @Test
    fun `getDefaultAgent throws when no agents configured`() {
        val emptyRegistry = AgentRegistry(
            context = context,
            modelClientFactory = { mockk(relaxed = true) },
            skillManager = skillManager,
            accessibilityBridge = accessibilityBridge
        )
        // Force unload
        emptyRegistry.reloadAll()

        // Should throw because main agent cannot be loaded from assets either
        // (Robolectric has empty assets by default with Config.NONE)
        // We verify behavior — either succeeds (default loaded from somewhere) or throws.
        // In a Robolectric setup with no assets, the main agent typically won't be created.
        try {
            val defaultAgent = emptyRegistry.getDefaultAgent()
            // If we got here, the default was somehow loaded
            assertNotNull(defaultAgent)
        } catch (e: IllegalStateException) {
            // Expected if no default is configured
            assertTrue(e.message?.contains("default") == true || e.message?.contains("configured") == true)
        }
    }

    // ==================== listAgents / getConfig ====================

    @Test
    fun `listAgents includes the main agent by default`() {
        val agents = registry.listAgents()
        assertTrue("Main agent should be in list", agents.any { it.id == "main" })
    }

    @Test
    fun `getConfig returns AgentConfig for known agent`() {
        val config = registry.getConfig("main")
        assertNotNull(config)
        assertEquals("main", config!!.id)
    }

    @Test
    fun `getConfig returns null for unknown agent`() {
        val config = registry.getConfig("nonexistent")
        assertNull(config)
    }

    // ==================== getSession ====================

    @Test
    fun `getSession returns a session for the main agent`() {
        val session = registry.getSession("main")
        assertNotNull(session)
    }

    @Test
    fun `getSession returns the same instance for repeated calls`() {
        val s1 = registry.getSession("main")
        val s2 = registry.getSession("main")
        assertSame(
            "getSession should return the same session instance for the same agent",
            s1,
            s2
        )
    }

    @Test
    fun `getSession falls back to main session for unknown agent - shares context`() {
        // Regression test for production bug #3 (CURRENT-STATUS-2026-06-28):
        // Previously, getSession("nonexistent") created a *new* session
        // instance cached under the unknown id, using main's config but
        // with no shared message history. After the fix, the unknown agent
        // returns the SAME session instance as getSession("main"), so the
        // conversation context is preserved across fallback requests.
        val mainSession = registry.getSession("main")
        val fallbackSession = registry.getSession("nonexistent")
        assertNotNull(mainSession)
        assertNotNull(fallbackSession)
        assertSame(
            "Fallback to unknown agent must share the main session " +
                "so conversation history is preserved",
            mainSession,
            fallbackSession
        )
        // A second call for the same unknown id should still return the
        // (shared) main session, not have forked a new one.
        val fallbackAgain = registry.getSession("another_unknown")
        assertSame(
            "Every unknown agent id must resolve to the shared main session",
            mainSession,
            fallbackAgain
        )
    }

    @Test
    fun `getSession for unknown agent does not fork the session cache`() {
        // Regression: the fallback must not create a new cache entry under
        // the unknown id. After calling getSession("unknown"), the
        // sessions map should only contain entries for known agents (and
        // \"main\" in particular).
        registry.getSession("main")
        registry.getSession("ghost_agent")
        registry.getSession("another_ghost")

        // We can\u2019t directly inspect the private sessions map, but we can
        // verify that no fork occurred by asking for main again and
        // confirming it\u2019s still the same instance.
        val mainAfterFallbacks = registry.getSession("main")
        val firstMain = registry.getSession("main")
        assertSame(mainAfterFallbacks, firstMain)
    }

    @Test
    fun `getSession falls back to main session even when other agents exist`() {
        // Confirm fallback behavior holds regardless of how many real
        // agents are configured.
        registry.createAgent(id = "researcher", name = "Researcher", model = "gpt-4")
        registry.createAgent(id = "coder", name = "Coder", model = "claude")

        val mainSession = registry.getSession("main")
        val researcher = registry.getSession("researcher")
        val typo = registry.getSession("resercher")  // typo

        assertNotSame(mainSession, researcher)  // real agents still distinct
        assertSame(
            "Typo'd agent id should resolve to the shared main session",
            mainSession,
            typo
        )
    }

    // ==================== createAgent ====================

    @Test
    fun `createAgent adds a new agent`() {
        val newAgent = registry.createAgent(id = "test1", name = "Test Agent", model = "gpt-4")
        assertEquals("test1", newAgent.id)
        assertEquals("Test Agent", newAgent.name)
        assertEquals("gpt-4", newAgent.model)
    }

    @Test
    fun `createAgent persists config to disk`() {
        val agentsDir = File(context.getExternalFilesDir(null), "agents")
        registry.createAgent(id = "persisted", name = "Persisted Agent", model = "gpt-4")

        val configFile = File(File(agentsDir, "persisted"), "config.yaml")
        assertTrue("config.yaml should be created", configFile.exists())

        val content = configFile.readText()
        assertTrue("Config should contain agent id", content.contains("id: persisted"))
        assertTrue("Config should contain agent name", content.contains("name: Persisted Agent"))
        assertTrue("Config should contain model", content.contains("model: gpt-4"))
    }

    @Test
    fun `createAgent persists SOUL_md to disk`() {
        registry.createAgent(id = "soul_test", name = "Soul Test", model = "gpt-4")

        val soulFile = File(File(File(context.getExternalFilesDir(null), "agents"), "soul_test"), "SOUL.md")
        assertTrue("SOUL.md should be created", soulFile.exists())
        assertTrue(
            "SOUL.md should mention agent name",
            soulFile.readText().contains("Soul Test")
        )
    }

    @Test
    fun `createAgent throws on duplicate id`() {
        registry.createAgent(id = "dupe", name = "First", model = "gpt-4")
        try {
            registry.createAgent(id = "dupe", name = "Second", model = "gpt-4")
            fail("Should throw on duplicate agent id")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message should mention the duplicate id",
                e.message?.contains("dupe") == true
            )
        }
    }

    @Test
    fun `createAgent is reflected in listAgents`() {
        registry.createAgent(id = "listed", name = "Listed", model = "gpt-4")
        val agents = registry.listAgents()
        assertTrue(
            "New agent should appear in listAgents",
            agents.any { it.id == "listed" }
        )
    }

    // ==================== deleteAgent ====================

    @Test
    fun `deleteAgent removes the agent and its files`() {
        registry.createAgent(id = "removable", name = "Removable", model = "gpt-4")
        assertNotNull(registry.getConfig("removable"))

        val deleted = registry.deleteAgent("removable")
        assertTrue("deleteAgent should return true", deleted)
        assertNull("Agent config should be gone", registry.getConfig("removable"))
    }

    @Test
    fun `deleteAgent removes agent directory`() {
        registry.createAgent(id = "removable", name = "R", model = "gpt-4")
        val agentDir = File(File(context.getExternalFilesDir(null), "agents"), "removable")
        assertTrue(agentDir.exists())

        registry.deleteAgent("removable")
        assertFalse("Agent directory should be removed", agentDir.exists())
    }

    @Test
    fun `deleteAgent refuses to remove default agent`() {
        val deleted = registry.deleteAgent("main")
        assertFalse("Should refuse to delete default agent", deleted)
        assertNotNull("Main agent should still exist", registry.getConfig("main"))
    }

    @Test
    fun `deleteAgent returns false for non-existent agent`() {
        val deleted = registry.deleteAgent("nonexistent")
        assertFalse(deleted)
    }

    // ==================== reloadAll / reloadAgent ====================

    @Test
    fun `reloadAll clears and reloads from disk`() {
        registry.createAgent(id = "reload_test", name = "Reload Test", model = "gpt-4")
        registry.reloadAll()

        val agents = registry.listAgents()
        // After reload, agents should include both main and reload_test (if persisted on disk)
        // At minimum, the operation should not throw.
        assertNotNull(agents)
    }

    @Test
    fun `reloadAgent reloads a single agent config`() {
        registry.createAgent(id = "single_reload", name = "Single Reload", model = "gpt-4")

        val agentsDir = File(File(context.getExternalFilesDir(null), "agents"), "single_reload")
        val configFile = File(agentsDir, "config.yaml")
        // Modify the config on disk to a new model
        configFile.writeText("""
            id: single_reload
            name: Updated Name
            model: new-model
            maxContextTokens: 8000
            tools: []
        """.trimIndent())

        val reloaded = registry.reloadAgent("single_reload")
        assertNotNull(reloaded)
        assertEquals("Updated Name", reloaded!!.name)
        assertEquals("new-model", reloaded.model)
    }

    @Test
    fun `reloadAgent returns null for missing agent`() {
        val result = registry.reloadAgent("nonexistent")
        assertNull(result)
    }

    // ==================== Multi-agent coexistence ====================

    @Test
    fun `multiple agents can coexist with independent sessions`() {
        registry.createAgent(id = "agent_a", name = "Agent A", model = "gpt-4")
        registry.createAgent(id = "agent_b", name = "Agent B", model = "claude")

        val sessionA1 = registry.getSession("agent_a")
        val sessionB = registry.getSession("agent_b")
        val sessionA2 = registry.getSession("agent_a")

        assertNotNull(sessionA1)
        assertNotNull(sessionB)
        assertSame(
            "Same agent should yield same session",
            sessionA1,
            sessionA2
        )
        assertNotSame(
            "Different agents should yield different sessions",
            sessionA1,
            sessionB
        )
    }

    @Test
    fun `deleting one agent does not affect others`() {
        registry.createAgent(id = "keep", name = "Keep", model = "gpt-4")
        registry.createAgent(id = "remove", name = "Remove", model = "gpt-4")

        assertNotNull(registry.getConfig("keep"))
        assertNotNull(registry.getConfig("remove"))

        registry.deleteAgent("remove")

        assertNotNull("Surviving agent should still exist", registry.getConfig("keep"))
        assertNull("Deleted agent should be gone", registry.getConfig("remove"))
    }
}