package ai.openclaw.android.skill

import ai.openclaw.android.data.local.DynamicSkillDao
import ai.openclaw.android.data.model.DynamicSkillEntity
import ai.openclaw.script.ScriptOrchestrator
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * DynamicSkillManager 单元测试
 *
 * 使用 mockk 模拟所有依赖，测试核心功能：
 * - registerFromJson: 解析 + 持久化 + 注册
 * - loadAllSaved: 从数据库恢复技能
 * - disableUnusedSkills: 30 天未使用停用
 * - purgeDisabledSkills: 90 天已停用删除
 * - enableSkill / disableSkill / removeSkill: 手动管理
 * - recordUsage: 记录使用
 */
class DynamicSkillManagerTest {

    private val mockContext = mockk<android.content.Context>(relaxed = true)
    private val mockDao = mockk<DynamicSkillDao>(relaxed = true)
    private val mockSkillManager = mockk<SkillManager>(relaxed = true)
    private val mockOrchestrator = mockk<ScriptOrchestrator>(relaxed = true)
    private val mockPrefs = mockk<UserPreferenceManager>(relaxed = true)

    private lateinit var manager: DynamicSkillManager

    private val validSkillJson = """
        {
            "id": "test_skill",
            "name": "Test Skill",
            "description": "A test skill",
            "version": "1.0.0",
            "instructions": "Do something",
            "script": "function test() { return 'ok'; }",
            "tools": [
                {
                    "name": "test_tool",
                    "description": "Test tool",
                    "parameters": {}
                }
            ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        manager = DynamicSkillManager(
            context = mockContext,
            dynamicSkillDao = mockDao,
            skillManager = mockSkillManager,
            orchestrator = mockOrchestrator,
            preferenceManager = mockPrefs,
            onUserConfirmation = { _, _ -> null }
        )
    }

    // ========== registerFromJson ==========

    @Test
    fun `registerFromJson success`() = runBlocking {
        val result = manager.registerFromJson(validSkillJson)

        assertTrue(result.isSuccess)
        assertEquals("test_skill", result.getOrNull())
        coVerify { mockDao.insert(any()) }
        verify { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `registerFromJson invalid JSON fails`() = runBlocking {
        val result = manager.registerFromJson("not valid json")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { mockDao.insert(any()) }
        verify(exactly = 0) { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `registerFromJson missing id fails`() = runBlocking {
        val invalidJson = """{"name": "no id", "description": "x", "script": "fn()", "tools": []}"""
        val result = manager.registerFromJson(invalidJson)

        assertTrue(result.isFailure)
    }

    @Test
    fun `registerFromJson missing tools fails`() = runBlocking {
        val invalidJson = """{"id": "x", "name": "x", "description": "x", "script": "fn()"}"""
        val result = manager.registerFromJson(invalidJson)

        assertTrue(result.isFailure)
    }

    // ========== loadAllSaved ==========

    @Test
    fun `loadAllSaved loads enabled skills`() = runBlocking {
        val entities = listOf(
            DynamicSkillEntity(
                id = "skill_1", name = "Skill 1", description = "d", version = "1.0",
                instructions = "", script = "fn()", toolsJson = validSkillJson, enabled = true
            ),
            DynamicSkillEntity(
                id = "skill_2", name = "Skill 2", description = "d", version = "1.0",
                instructions = "", script = "fn()", toolsJson = validSkillJson, enabled = true
            )
        )
        coEvery { mockDao.getAllEnabledList() } returns entities

        val count = manager.loadAllSaved()

        assertEquals(2, count)
        verify(exactly = 2) { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `loadAllSaved returns 0 when no skills`() = runBlocking {
        coEvery { mockDao.getAllEnabledList() } returns emptyList()

        val count = manager.loadAllSaved()

        assertEquals(0, count)
    }

    @Test
    fun `loadAllSaved skips invalid skills`() = runBlocking {
        val invalidEntity = DynamicSkillEntity(
            id = "bad_skill", name = "Bad", description = "d", version = "1.0",
            instructions = "", script = "fn()", toolsJson = "invalid json", enabled = true
        )
        coEvery { mockDao.getAllEnabledList() } returns listOf(invalidEntity)

        val count = manager.loadAllSaved()

        assertEquals(0, count)
    }

    // ========== disableUnusedSkills ==========

    @Test
    fun `disableUnusedSkills disables skills unused for 30 days`() = runBlocking {
        val oldThreshold = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val oldSkill = listOf(
            DynamicSkillEntity(
                id = "old_skill", name = "Old", description = "d", version = "1.0",
                instructions = "", script = "fn()", toolsJson = "",
                lastUsedAt = oldThreshold - 1000, enabled = true
            )
        )
        coEvery { mockDao.getEnabledSkillsLastUsedBefore(any()) } returns oldSkill

        manager.disableUnusedSkills()

        coVerify { mockDao.disable("old_skill") }
        verify { mockSkillManager.unregisterSkill("old_skill") }
    }

    @Test
    fun `disableUnusedSkills does nothing when no unused skills`() = runBlocking {
        coEvery { mockDao.getEnabledSkillsLastUsedBefore(any()) } returns emptyList()

        manager.disableUnusedSkills()

        coVerify(exactly = 0) { mockDao.disable(any()) }
        verify(exactly = 0) { mockSkillManager.unregisterSkill(any()) }
    }

    // ========== purgeDisabledSkills ==========

    @Test
    fun `purgeDisabledSkills removes skills disabled for 90 days`() = runBlocking {
        val oldThreshold = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        val disabledSkill = listOf(
            DynamicSkillEntity(
                id = "disabled_skill", name = "Disabled", description = "d", version = "1.0",
                instructions = "", script = "fn()", toolsJson = "",
                lastUsedAt = oldThreshold - 1000, enabled = false
            )
        )
        coEvery { mockDao.getDisabledSkillsDisabledBefore(any()) } returns disabledSkill

        manager.purgeDisabledSkills()

        coVerify { mockDao.deleteById("disabled_skill") }
    }

    @Test
    fun `purgeDisabledSkills does nothing when no disabled skills`() = runBlocking {
        coEvery { mockDao.getDisabledSkillsDisabledBefore(any()) } returns emptyList()

        manager.purgeDisabledSkills()

        coVerify(exactly = 0) { mockDao.deleteById(any()) }
    }

    // ========== enableSkill ==========

    @Test
    fun `enableSkill enables a disabled skill`() = runBlocking {
        val entity = DynamicSkillEntity(
            id = "skill_to_enable", name = "Enable Me", description = "d", version = "1.0",
            instructions = "", script = "fn()", toolsJson = validSkillJson, enabled = false
        )
        coEvery { mockDao.getById("skill_to_enable") } returns entity

        manager.enableSkill("skill_to_enable")

        coVerify { mockDao.enable("skill_to_enable") }
        verify { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `enableSkill does nothing when skill not found`() = runBlocking {
        coEvery { mockDao.getById("nonexistent") } returns null

        manager.enableSkill("nonexistent")

        coVerify(exactly = 0) { mockDao.enable(any()) }
    }

    // ========== disableSkill ==========

    @Test
    fun `disableSkill disables a skill`() = runBlocking {
        manager.disableSkill("some_skill")

        coVerify { mockDao.disable("some_skill") }
        verify { mockSkillManager.unregisterSkill("some_skill") }
    }

    // ========== removeSkill ==========

    @Test
    fun `removeSkill removes from database, runtime, and preferences`() = runBlocking {
        manager.removeSkill("some_skill")

        coVerify { mockDao.deleteById("some_skill") }
        verify { mockSkillManager.unregisterSkill("some_skill") }
        verify { mockPrefs.clearPreference("some_skill") }
    }

    // ========== recordUsage ==========

    @Test
    fun `recordUsage updates lastUsedAt`() = runBlocking {
        manager.recordUsage("some_skill")

        coVerify { mockDao.updateLastUsed("some_skill", any()) }
    }

    // ========== runMaintenance ==========

    @Test
    fun `runMaintenance runs both disable and purge`() = runBlocking {
        coEvery { mockDao.getEnabledSkillsLastUsedBefore(any()) } returns emptyList()
        coEvery { mockDao.getDisabledSkillsDisabledBefore(any()) } returns emptyList()

        manager.runMaintenance()

        coVerify { mockDao.getEnabledSkillsLastUsedBefore(any()) }
        coVerify { mockDao.getDisabledSkillsDisabledBefore(any()) }
    }

    // ========== cleanup ==========

    @Test
    fun `cleanup cancels scope`() {
        manager.cleanup()
        // No crash = success; scope cancellation is internal
    }

    // ========== onToolsChanged callback (Task 6) ==========

    @Test
    fun `registerFromJson triggers onToolsChanged callback`() = runBlocking {
        var callbackCount = 0
        manager.setToolsChangedListener { callbackCount++ }

        val result = manager.registerFromJson(validSkillJson)

        assertTrue(result.isSuccess)
        assertEquals(1, callbackCount)
        verify { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `registerFromJson without listener does not crash`() = runBlocking {
        // No listener set — should still work
        val result = manager.registerFromJson(validSkillJson)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `registerFromJson invalid JSON does not trigger callback`() = runBlocking {
        var callbackCount = 0
        manager.setToolsChangedListener { callbackCount++ }

        val result = manager.registerFromJson("not valid json")

        assertTrue(result.isFailure)
        assertEquals(0, callbackCount)
    }
}
