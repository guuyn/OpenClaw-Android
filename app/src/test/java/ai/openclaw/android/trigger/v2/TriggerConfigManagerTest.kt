package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerAction
import ai.openclaw.android.trigger.models.TriggerRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for TriggerConfigManager (mocked DAO).
 *
 * AppDatabase uses SQLCipher which is not available in unit-test JVMs, so we
 * mock TriggerRuleDao. Tests cover the post-fix Room-backed behaviour:
 *  - save() converts TriggerConfig to v1 TriggerRule and inserts via dao
 *  - load() / loadAll() read via dao and convert back to v2 TriggerConfig
 *  - delete() / deleteAll() delegate to dao
 *  - initDefaults() seeds 5 templates when dao is empty
 *  - initDefaults() preserves existing user data when dao has rules
 *  - Templates survive a round-trip through the dao (TriggerConfig → Rule → TriggerConfig)
 */
class TriggerConfigManagerTest {

    private lateinit var dao: TriggerRuleDao

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        // By default, dao reports empty. Tests override as needed.
        coEvery { dao.getAll() } returns emptyList()
    }

    /**
     * Helper: create a TriggerRule that matches what TriggerConfig.toV1Rule() would
     * produce for a given v2 template (cron + agent query action).
     */
    private fun cronRule(id: String, name: String, cron: String): TriggerRule = TriggerRule(
        id = id,
        name = name,
        enabled = true,
        source = EventSource.CRON,
        scheduleCron = cron,
        actionJson = TriggerRule.serializeAction(
            TriggerAction.AgentQuery(prompt = "ping from $name")
        ),
        filtersJson = "[]"
    )

    // ==================== save() ====================

    @Test
    fun `save converts TimeTrigger to v1 CRON rule and inserts`() = runTest {
        val trigger = TimeTrigger(
            id = "rule-cron-1",
            name = "通勤天气",
            description = "工作日早 8 点自动获取天气",
            cronExpression = "0 8 * * 1-5",
            action = AgentQueryActionDef(prompt = "天气查询")
        )

        val captured = slot<TriggerRule>()
        coEvery { dao.insert(capture(captured)) } just runs

        val manager = TriggerConfigManager(dao = dao)
        manager.save(trigger)

        coVerify(exactly = 1) { dao.insert(any()) }
        val rule = captured.captured
        assertEquals("rule-cron-1", rule.id)
        assertEquals("通勤天气", rule.name)
        assertEquals(EventSource.CRON, rule.source)
        assertEquals("0 8 * * 1-5", rule.scheduleCron)
        assertEquals("[]", rule.filtersJson)
        assertNotNull(rule.actionJson)
        assertTrue("actionJson should encode AgentQuery", rule.actionJson.contains("AgentQuery"))
    }

    @Test
    fun `save converts NotificationPatternTrigger to NOTIFICATION rule with filters`() = runTest {
        val trigger = NotificationPatternTrigger(
            id = "rule-notif-1",
            name = "重要通知",
            description = "重要通知触发",
            packageName = "com.example.chat",
            keywords = listOf("紧急", "important"),
            matchMode = "OR",
            action = AgentQueryActionDef(prompt = "分析通知")
        )

        val captured = slot<TriggerRule>()
        coEvery { dao.insert(capture(captured)) } just runs

        val manager = TriggerConfigManager(dao = dao)
        manager.save(trigger)

        val rule = captured.captured
        assertEquals(EventSource.NOTIFICATION, rule.source)
        assertNull(rule.scheduleCron)
        // filtersJson should contain both PackageFilter and KeywordFilter
        assertTrue("filtersJson should contain PackageFilter", rule.filtersJson.contains("PackageFilter"))
        assertTrue("filtersJson should contain KeywordFilter", rule.filtersJson.contains("KeywordFilter"))
    }

    @Test
    fun `save converts DeviceStateTrigger to SYSTEM_BROADCAST rule`() = runTest {
        val trigger = DeviceStateTrigger(
            id = "rule-battery-1",
            name = "低电量提醒",
            description = "电量低于 15% 时提醒充电",
            stateType = DeviceStateType.BATTERY_LOW,
            threshold = 0.15f,
            action = NotificationActionDef("电量低")
        )

        val captured = slot<TriggerRule>()
        coEvery { dao.insert(capture(captured)) } just runs

        val manager = TriggerConfigManager(dao = dao)
        manager.save(trigger)

        val rule = captured.captured
        assertEquals(EventSource.SYSTEM_BROADCAST, rule.source)
        assertEquals("[]", rule.filtersJson)
    }

    // ==================== load() / loadAll() ====================

    @Test
    fun `load returns null when dao has no rule`() = runTest {
        coEvery { dao.getById("missing") } returns null
        val manager = TriggerConfigManager(dao = dao)
        assertNull(manager.load("missing"))
    }

    @Test
    fun `load converts v1 rule back to v2 TriggerConfig`() = runTest {
        coEvery { dao.getById("rule-cron-1") } returns cronRule(
            id = "rule-cron-1",
            name = "通勤天气",
            cron = "0 8 * * 1-5"
        )

        val manager = TriggerConfigManager(dao = dao)
        val config = manager.load("rule-cron-1")

        assertNotNull(config)
        assertEquals("rule-cron-1", config!!.id)
        assertEquals("通勤天气", config.name)
        // Note: TriggerConfig.fromRule() wraps v1 rules in TriggerConfigV1Compat (by design).
        // The internal condition retains type info — here it should be TimeCondition.
        assertTrue("Expected TriggerConfigV1Compat, got ${config::class.simpleName}", config is TriggerConfigV1Compat)
        val condition = (config as TriggerConfigV1Compat).condition
        assertTrue("Expected TimeCondition, got ${condition::class.simpleName}", condition is TimeCondition)
        assertEquals("0 8 * * 1-5", (condition as TimeCondition).cronExpression)
    }

    @Test
    fun `loadAll returns all v2 configs converted from dao rules`() = runTest {
        val rules = listOf(
            cronRule("rule-1", "通勤天气", "0 8 * * 1-5"),
            cronRule("rule-2", "工作时段", "0 9 * * 1-5")
        )
        coEvery { dao.getAll() } returns rules

        val manager = TriggerConfigManager(dao = dao)
        val configs = manager.loadAll()

        assertEquals(2, configs.size)
        assertEquals(setOf("rule-1", "rule-2"), configs.map { it.id }.toSet())
        assertTrue("All configs should be TriggerConfigV1Compat", configs.all { it is TriggerConfigV1Compat })
    }

    // ==================== delete() / deleteAll() ====================

    @Test
    fun `delete delegates to dao deleteById`() = runTest {
        coEvery { dao.deleteById("rule-1") } just runs

        val manager = TriggerConfigManager(dao = dao)
        manager.delete("rule-1")

        coVerify(exactly = 1) { dao.deleteById("rule-1") }
    }

    @Test
    fun `deleteAll iterates dao getAll and deletes each`() = runTest {
        val rules = listOf(
            cronRule("rule-1", "a", "0 * * * *"),
            cronRule("rule-2", "b", "0 * * * *"),
            cronRule("rule-3", "c", "0 * * * *")
        )
        coEvery { dao.getAll() } returns rules
        coEvery { dao.delete(any()) } just runs

        val manager = TriggerConfigManager(dao = dao)
        manager.deleteAll()

        coVerify(exactly = 3) { dao.delete(any()) }
        coVerify(exactly = 1) { dao.delete(rules[0]) }
        coVerify(exactly = 1) { dao.delete(rules[1]) }
        coVerify(exactly = 1) { dao.delete(rules[2]) }
    }

    // ==================== initDefaults() — happy path ====================

    @Test
    fun `initDefaults seeds 5 templates when dao is empty`() = runTest {
        // dao is empty (from setUp) and remains empty until inserts happen
        val insertedRules = mutableListOf<TriggerRule>()
        coEvery { dao.insert(capture(insertedRules)) } answers {
            // After insert, dao would include the new rule. But for this test, dao.getAll()
            // is mocked to always return empty, simulating a fresh install.
        }

        val manager = TriggerConfigManager(dao = dao)
        manager.initDefaults()

        // Should have inserted exactly the 5 preset templates
        coVerify(exactly = 5) { dao.insert(any()) }
        assertEquals(5, insertedRules.size)

        // Verify the 5 template names are present
        val names = insertedRules.map { it.name }.toSet()
        assertTrue(
            "Expected 通勤天气, got $names",
            "通勤天气" in names
        )
        assertTrue(
            "Expected 重要通知提醒, got $names",
            "重要通知提醒" in names
        )
        assertTrue(
            "Expected 低电量提醒, got $names",
            "低电量提醒" in names
        )
        assertTrue(
            "Expected 充电完成提醒, got $names",
            "充电完成提醒" in names
        )
        assertTrue(
            "Expected 工作时段免打扰, got $names",
            "工作时段免打扰" in names
        )
    }

    @Test
    fun `initDefaults seeds 2 CRON rules and 1 NOTIFICATION and 2 SYSTEM_BROADCAST`() = runTest {
        val insertedRules = mutableListOf<TriggerRule>()
        coEvery { dao.insert(capture(insertedRules)) } answers {}

        val manager = TriggerConfigManager(dao = dao)
        manager.initDefaults()

        assertEquals(5, insertedRules.size)
        // TriggerTemplates.getAllTemplates() distribution:
        //   commuteWeather()           -> TimeTrigger -> CRON
        //   workHoursDnd()             -> TimeTrigger -> CRON
        //   importantNotificationReminder() -> NotificationPatternTrigger -> NOTIFICATION
        //   lowBatteryReminder()       -> DeviceStateTrigger -> SYSTEM_BROADCAST
        //   chargingCompleteReminder() -> DeviceStateTrigger -> SYSTEM_BROADCAST
        val sources = insertedRules.map { it.source }
        assertEquals(2, sources.count { it == EventSource.CRON })
        assertEquals(1, sources.count { it == EventSource.NOTIFICATION })
        assertEquals(2, sources.count { it == EventSource.SYSTEM_BROADCAST })
    }

    // ==================== initDefaults() — preserve existing data ====================

    @Test
    fun `initDefaults preserves existing dao rules and does not insert templates`() = runTest {
        // User already has 2 rules in dao — initDefaults should NOT touch them.
        val existingRules = listOf(
            cronRule("user-1", "用户自定义1", "0 12 * * *"),
            cronRule("user-2", "用户自定义2", "0 18 * * *")
        )
        coEvery { dao.getAll() } returns existingRules

        val manager = TriggerConfigManager(dao = dao)
        manager.initDefaults()

        // Critical: NO inserts should have happened
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    // ==================== Round-trip integrity ====================

    @Test
    fun `round-trip TimeTrigger preserves id name and cron expression`() = runTest {
        // Simulate dao storage via a Map; capture inserts to populate, lookup on getById.
        val store = mutableMapOf<String, TriggerRule>()
        val insertSlot = slot<TriggerRule>()
        coEvery { dao.insert(capture(insertSlot)) } answers {
            store[insertSlot.captured.id] = insertSlot.captured
        }
        coEvery { dao.getById(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            store[it.invocation.args[0] as String]
        }

        val manager = TriggerConfigManager(dao = dao)
        val original = TimeTrigger(
            id = UUID.randomUUID().toString(),
            name = "round-trip-test",
            description = "测试",
            cronExpression = "0 7 * * 1-5",
            action = AgentQueryActionDef(prompt = "天气")
        )

        manager.save(original)
        val loaded = manager.load(original.id)

        assertNotNull("Loaded config should not be null after save", loaded)
        // TriggerConfig.fromRule(rule) wraps v1 rules in TriggerConfigV1Compat by design
        // (see also load converts v1 rule back to v2 TriggerConfig test). The id/name
        // and the underlying TimeCondition are preserved.
        assertTrue(
            "Expected TriggerConfigV1Compat, got ${loaded!!::class.simpleName}",
            loaded is TriggerConfigV1Compat
        )
        val compat = loaded as TriggerConfigV1Compat
        assertEquals(original.id, compat.id)
        assertEquals(original.name, compat.name)
        val condition = compat.condition
        assertTrue(
            "Expected TimeCondition, got ${condition::class.simpleName}",
            condition is TimeCondition
        )
        assertEquals(original.cronExpression, (condition as TimeCondition).cronExpression)
    }
}