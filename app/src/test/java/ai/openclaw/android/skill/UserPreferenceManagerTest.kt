package ai.openclaw.android.skill

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class UserPreferenceManagerTest {

    private lateinit var testDir: File
    private lateinit var manager: UserPreferenceManager

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "skill_prefs_test_${System.nanoTime()}")
        testDir.mkdirs()
        manager = UserPreferenceManager(testDir)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `getPreference returns null for unknown tool`() {
        val result = manager.getPreference("unknown_tool")
        assertNull(result)
    }

    @Test
    fun `setPreference and getPreference`() {
        manager.setPreference("bitcoin_price_set_alert", ApprovalDecision.ALWAYS_APPROVE)

        val result = manager.getPreference("bitcoin_price_set_alert")
        assertNotNull(result)
        assertEquals("bitcoin_price_set_alert", result?.toolId)
        assertEquals(ApprovalDecision.ALWAYS_APPROVE, result?.decision)
    }

    @Test
    fun `clearPreference removes tool`() {
        manager.setPreference("bitcoin_price_set_alert", ApprovalDecision.ALWAYS_APPROVE)
        assertNotNull(manager.getPreference("bitcoin_price_set_alert"))

        manager.clearPreference("bitcoin_price_set_alert")
        assertNull(manager.getPreference("bitcoin_price_set_alert"))
    }

    @Test
    fun `getAllPreferences returns all saved preferences`() {
        manager.setPreference("tool_a", ApprovalDecision.ALWAYS_APPROVE)
        manager.setPreference("tool_b", ApprovalDecision.ALWAYS_DENY)

        val all = manager.getAllPreferences()
        assertEquals(2, all.size)
        assertEquals(ApprovalDecision.ALWAYS_APPROVE, all["tool_a"]?.decision)
        assertEquals(ApprovalDecision.ALWAYS_DENY, all["tool_b"]?.decision)
    }

    @Test
    fun `clearAll removes all preferences`() {
        manager.setPreference("tool_a", ApprovalDecision.ALWAYS_APPROVE)
        manager.setPreference("tool_b", ApprovalDecision.ALWAYS_DENY)
        assertEquals(2, manager.getAllPreferences().size)

        manager.clearAll()
        assertTrue(manager.getAllPreferences().isEmpty())
    }

    @Test
    fun `setPreference overwrites existing preference`() {
        manager.setPreference("tool_a", ApprovalDecision.ALWAYS_APPROVE)
        assertEquals(ApprovalDecision.ALWAYS_APPROVE, manager.getPreference("tool_a")?.decision)

        manager.setPreference("tool_a", ApprovalDecision.ALWAYS_DENY)
        assertEquals(ApprovalDecision.ALWAYS_DENY, manager.getPreference("tool_a")?.decision)
        assertEquals(1, manager.getAllPreferences().size)
    }

    @Test
    fun `preferences persist across manager instances`() {
        manager.setPreference("tool_a", ApprovalDecision.ALWAYS_APPROVE)

        // Verify file exists before creating new instance
        val prefsFile = File(testDir, "skill_approval_prefs.json")
        assertTrue("Prefs file should exist after save", prefsFile.exists())
        assertTrue("Prefs file should not be empty", prefsFile.length() > 0)

        // Create new manager instance (simulates app restart)
        val manager2 = UserPreferenceManager(testDir)
        val result = manager2.getPreference("tool_a")
        assertNotNull("Preference should be loaded from file", result)
        assertEquals(ApprovalDecision.ALWAYS_APPROVE, result?.decision)
    }
}
