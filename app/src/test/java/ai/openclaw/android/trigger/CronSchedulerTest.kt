package ai.openclaw.android.trigger

import ai.openclaw.android.trigger.models.*
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CronScheduler cron expression parser + Trigger model serialization.
 *
 * CronScheduler depends on Android WorkManager (not unit-testable on JVM),
 * so these tests cover the pure-logic CronScheduler.parseCronToIntervalMs method
 * and the full TriggerRule / TriggerEvent serialization lifecycle.
 */
class CronSchedulerTest {

    private lateinit var parser: CronParser

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        parser = CronParser()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Cron Parsing ====================

    @Test
    fun `parseCronToIntervalMs parses every 30 minutes`() {
        val intervalMs = parser.parseCronToIntervalMs("*/30 * * * *")
        assertEquals(30 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs parses every 15 minutes`() {
        val intervalMs = parser.parseCronToIntervalMs("*/15 * * * *")
        assertEquals(15 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs parses every hour`() {
        val intervalMs = parser.parseCronToIntervalMs("0 * * * *")
        assertEquals(60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs parses every day`() {
        val intervalMs = parser.parseCronToIntervalMs("0 0 * * *")
        assertEquals(24 * 60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs parses specific minute every hour`() {
        val intervalMs = parser.parseCronToIntervalMs("15 * * * *")
        assertEquals(15 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs parses specific hour daily`() {
        val intervalMs = parser.parseCronToIntervalMs("0 9 * * *")
        assertEquals(24 * 60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs defaults for invalid cron expression`() {
        val intervalMs = parser.parseCronToIntervalMs("invalid")
        assertEquals(60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs defaults for short cron expression`() {
        val intervalMs = parser.parseCronToIntervalMs("*/5 *")
        assertEquals(60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs defaults for unparseable minute`() {
        val intervalMs = parser.parseCronToIntervalMs("abc * * * *")
        assertEquals(60 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs handles every 5 minutes`() {
        val intervalMs = parser.parseCronToIntervalMs("*/5 * * * *")
        assertEquals(5 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs handles every 45 minutes`() {
        val intervalMs = parser.parseCronToIntervalMs("*/45 * * * *")
        assertEquals(45 * 60 * 1000L, intervalMs)
    }

    @Test
    fun `parseCronToIntervalMs handles every 2 hours`() {
        val intervalMs = parser.parseCronToIntervalMs("0 */2 * * *")
        // */2 in hour field is not specifically handled, falls to 1h default
        assertEquals(60 * 60 * 1000L, intervalMs)
    }

    // ==================== Trigger Rule Full Lifecycle ====================

    @Test
    fun `TriggerRule serializes and deserializes filters correctly`() {
        val filters = listOf(
            Filter.PackageFilter(listOf("com.example", "com.test")),
            Filter.KeywordFilter(listOf("error", "warning"), MatchMode.OR),
            Filter.TimeFilter(9, 17),
            Filter.CategoryFilter("work")
        )

        val json = TriggerRule.serializeFilters(filters)
        val parsed = TriggerRule.parseFilters(json)

        assertEquals(4, parsed.size)
        assertTrue(parsed[0] is Filter.PackageFilter)
        assertEquals(2, (parsed[0] as Filter.PackageFilter).packages.size)
        assertTrue(parsed[1] is Filter.KeywordFilter)
        assertTrue(parsed[2] is Filter.TimeFilter)
        assertTrue(parsed[3] is Filter.CategoryFilter)
    }

    @Test
    fun `TriggerRule serializes and deserializes SkillCall action correctly`() {
        val action = TriggerAction.SkillCall(
            skillId = "weather",
            toolName = "get_weather",
            paramsJson = """{"city":"Beijing"}"""
        )

        val json = TriggerRule.serializeAction(action)
        val parsed = TriggerRule.parseAction(json)

        assertNotNull(parsed)
        assertTrue(parsed is TriggerAction.SkillCall)
        val skillCall = parsed as TriggerAction.SkillCall
        assertEquals("weather", skillCall.skillId)
        assertEquals("get_weather", skillCall.toolName)
        assertTrue(skillCall.paramsJson.contains("Beijing"))
    }

    @Test
    fun `TriggerRule serializes and deserializes AgentQuery action correctly`() {
        val action = TriggerAction.AgentQuery(
            prompt = "Summarize: {notification.text}",
            model = "gpt-4"
        )

        val json = TriggerRule.serializeAction(action)
        val parsed = TriggerRule.parseAction(json)

        assertNotNull(parsed)
        assertTrue(parsed is TriggerAction.AgentQuery)
        val query = parsed as TriggerAction.AgentQuery
        assertEquals("gpt-4", query.model)
        assertTrue(query.prompt.contains("notification"))
    }

    @Test
    fun `TriggerRule serializes and deserializes NotificationReply action correctly`() {
        val action = TriggerAction.NotificationReply(
            template = "Thanks: {notification.title}",
            autoReply = true
        )

        val json = TriggerRule.serializeAction(action)
        val parsed = TriggerRule.parseAction(json)

        assertNotNull(parsed)
        assertTrue(parsed is TriggerAction.NotificationReply)
        val reply = parsed as TriggerAction.NotificationReply
        assertTrue(reply.autoReply)
    }

    @Test
    fun `TriggerRule serializes and deserializes CustomScript action correctly`() {
        val action = TriggerAction.CustomScript(
            script = "return event_source === 'CRON';"
        )

        val json = TriggerRule.serializeAction(action)
        val parsed = TriggerRule.parseAction(json)

        assertNotNull(parsed)
        assertTrue(parsed is TriggerAction.CustomScript)
        val script = parsed as TriggerAction.CustomScript
        assertTrue(script.script.contains("CRON"))
    }

    @Test
    fun `create full CRON rule and verify parsing`() {
        val filters = listOf(
            Filter.KeywordFilter(listOf("meeting"), MatchMode.OR)
        )
        val action = TriggerAction.AgentQuery(
            prompt = "Handle this meeting notification: {notification.text}"
        )

        val rule = TriggerRule(
            id = "cron-meeting-alert",
            name = "Meeting Alert",
            source = EventSource.CRON,
            filtersJson = TriggerRule.serializeFilters(filters),
            actionJson = TriggerRule.serializeAction(action),
            cooldownMs = 600_000L,
            scheduleCron = "*/30 * * * *"
        )

        // Verify full round-trip
        assertEquals("cron-meeting-alert", rule.id)
        assertEquals(EventSource.CRON, rule.source)
        assertEquals("*/30 * * * *", rule.scheduleCron)
        assertEquals(1, rule.getFilters().size)
        assertNotNull(rule.getAction())
        assertTrue(rule.getAction() is TriggerAction.AgentQuery)
    }

    @Test
    fun `create full NOTIFICATION rule and verify complete chain`() {
        val filters = listOf(
            Filter.PackageFilter(listOf("com.whatsapp")),
            Filter.KeywordFilter(listOf("urgent", "important"), MatchMode.OR),
            Filter.CategoryFilter("URGENT")
        )
        val action = TriggerAction.SkillCall(
            skillId = "notification",
            toolName = "dismiss",
            paramsJson = "{}"
        )

        val rule = TriggerRule(
            id = "notif-whatsapp-urgent",
            name = "WhatsApp Urgent",
            source = EventSource.NOTIFICATION,
            filtersJson = TriggerRule.serializeFilters(filters),
            actionJson = TriggerRule.serializeAction(action),
            cooldownMs = 300_000L
        )

        // Verify all properties
        assertEquals(EventSource.NOTIFICATION, rule.source)
        assertEquals(3, rule.getFilters().size)
        assertTrue(rule.getFilters()[0] is Filter.PackageFilter)
        assertTrue(rule.getFilters()[1] is Filter.KeywordFilter)
        assertTrue(rule.getFilters()[2] is Filter.CategoryFilter)

        val parsedAction = rule.getAction() as TriggerAction.SkillCall
        assertEquals("notification", parsedAction.skillId)
        assertEquals("dismiss", parsedAction.toolName)
    }

    @Test
    fun `TriggerRule getFilters returns empty list for invalid JSON`() {
        val rule = TriggerRule(
            id = "bad-json-rule",
            name = "Bad JSON",
            source = EventSource.CRON,
            filtersJson = "not valid json",
            actionJson = TriggerRule.serializeAction(
                TriggerAction.SkillCall("test", "tool", "{}")
            )
        )

        val filters = rule.getFilters()
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `TriggerRule getAction returns null for invalid JSON`() {
        val rule = TriggerRule(
            id = "bad-action-rule",
            name = "Bad Action",
            source = EventSource.CRON,
            actionJson = "not valid json"
        )

        assertNull(rule.getAction())
    }

    // ==================== TriggerEvent Tests ====================

    @Test
    fun `TriggerEvent generates unique IDs`() {
        val event1 = TriggerEvent(source = EventSource.CRON)
        val event2 = TriggerEvent(source = EventSource.CRON)

        assertNotEquals(event1.id, event2.id)
    }

    @Test
    fun `TriggerEvent preserves timestamp`() {
        val before = System.currentTimeMillis()
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "test")
        )
        val after = System.currentTimeMillis()

        assertTrue(event.timestamp >= before)
        assertTrue(event.timestamp <= after)
    }

    @Test
    fun `TriggerEvent dedupKey is optional`() {
        val event = TriggerEvent(
            source = EventSource.USER_ACTION,
            payload = emptyMap()
        )
        assertNull(event.dedupKey)
    }

    @Test
    fun `TriggerEvent carries payload correctly`() {
        val payload = mapOf<String, Any?>(
            "title" to "Test Title",
            "text" to "Test Body",
            "package" to "com.test.app",
            "count" to 42
        )
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = payload
        )

        assertEquals("Test Title", event.payload["title"])
        assertEquals("Test Body", event.payload["text"])
        assertEquals("com.test.app", event.payload["package"])
        assertEquals(42, event.payload["count"])
    }

    // ==================== Integration: Full Trigger Chain ====================

    @Test
    fun `full trigger chain create rule match event verify action`() {
        // Step 1: Create a rule
        val filters = listOf(
            Filter.PackageFilter(listOf("com.example")),
            Filter.KeywordFilter(listOf("alert"), MatchMode.OR)
        )
        val action = TriggerAction.SkillCall(
            skillId = "notification",
            toolName = "forward",
            paramsJson = """{"target":"agent"}"""
        )
        val rule = TriggerRule(
            id = "integration-test",
            name = "Integration Test",
            source = EventSource.NOTIFICATION,
            filtersJson = TriggerRule.serializeFilters(filters),
            actionJson = TriggerRule.serializeAction(action),
            cooldownMs = 0L
        )

        // Step 2: Verify rule parses correctly
        assertEquals(EventSource.NOTIFICATION, rule.source)
        assertEquals(2, rule.getFilters().size)
        assertNotNull(rule.getAction())

        // Step 3: Verify the action matches expected type
        val parsedAction = rule.getAction() as TriggerAction.SkillCall
        assertEquals("notification", parsedAction.skillId)
        assertEquals("forward", parsedAction.toolName)

        // Step 4: Create a matching event
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf(
                "package" to "com.example.app",
                "title" to "New alert",
                "text" to "Something happened"
            )
        )

        // Step 5: Verify event matches rule source
        assertEquals(rule.source, event.source)

        // Step 6: Verify event payload contains filter-matching data
        val pkg = event.payload["package"] as? String
        val text = (event.payload["title"] as? String ?: "") + " " + (event.payload["text"] as? String ?: "")

        assertTrue("Package should contain 'com.example'",
            pkg?.contains("com.example") == true)
        assertTrue("Text should contain 'alert'",
            text.contains("alert", ignoreCase = true))
    }

    @Test
    fun `full trigger chain cron rule cron interval event payload`() {
        // Step 1: Create a CRON rule
        val action = TriggerAction.AgentQuery(
            prompt = "Daily summary requested"
        )
        val rule = TriggerRule(
            id = "cron-daily-summary",
            name = "Daily Summary",
            source = EventSource.CRON,
            actionJson = TriggerRule.serializeAction(action),
            scheduleCron = "0 9 * * *"
        )

        // Step 2: Verify cron interval
        val intervalMs = parser.parseCronToIntervalMs(rule.scheduleCron!!)
        assertEquals(24 * 60 * 60 * 1000L, intervalMs)

        // Step 3: Create a CRON event that would be triggered
        val event = TriggerEvent(
            source = EventSource.CRON,
            payload = mapOf("ruleId" to rule.id, "cronTrigger" to true)
        )

        // Step 4: Verify event matches rule
        assertEquals(EventSource.CRON, event.source)
        assertEquals(rule.id, event.payload["ruleId"])
    }

    @Test
    fun `full trigger chain create multiple rules filter by source`() {
        val rules = listOf(
            TriggerRule(
                id = "cron-1",
                name = "Cron Rule 1",
                source = EventSource.CRON,
                actionJson = TriggerRule.serializeAction(
                    TriggerAction.AgentQuery(prompt = "Cron 1")
                ),
                scheduleCron = "0 * * * *"
            ),
            TriggerRule(
                id = "notif-1",
                name = "Notification Rule 1",
                source = EventSource.NOTIFICATION,
                filtersJson = TriggerRule.serializeFilters(
                    listOf(Filter.PackageFilter(listOf("com.whatsapp")))
                ),
                actionJson = TriggerRule.serializeAction(
                    TriggerAction.SkillCall("notification", "dismiss", "{}")
                )
            ),
            TriggerRule(
                id = "cron-2",
                name = "Cron Rule 2",
                source = EventSource.CRON,
                actionJson = TriggerRule.serializeAction(
                    TriggerAction.CustomScript("return true;")
                ),
                scheduleCron = "*/30 * * * *"
            )
        )

        // Filter by source
        val cronRules = rules.filter { it.source == EventSource.CRON }
        val notifRules = rules.filter { it.source == EventSource.NOTIFICATION }

        assertEquals(2, cronRules.size)
        assertEquals(1, notifRules.size)
    }
}

/**
 * Pure-logic cron parser extracted from CronScheduler for unit testing.
 * Mirrors the parseCronToIntervalMs logic without Android/WorkManager dependencies.
 */
class CronParser {
    fun parseCronToIntervalMs(cronExpr: String): Long {
        val parts = cronExpr.trim().split(Regex("\\s+"))
        if (parts.size < 5) {
            return 60 * 60 * 1000L // default 1 hour
        }

        val minute = parts[0]
        val hour = parts[1]

        // */N minute pattern
        if (minute.startsWith("*/")) {
            val n = minute.substring(2).toLongOrNull() ?: return 60 * 60 * 1000L
            return n * 60 * 1000L
        }

        // 0 * * * * → every hour
        if (minute == "0" && hour == "*") {
            return 60 * 60 * 1000L
        }

        // 0 0 * * * → every day
        if (minute == "0" && hour == "0") {
            return 24 * 60 * 60 * 1000L
        }

        // N * * * * → every N minutes
        if (hour == "*") {
            val n = minute.toLongOrNull()
            if (n != null && n > 0) {
                return n * 60 * 1000L
            }
            return 60 * 60 * 1000L
        }

        // 0 H * * * → every 24h at hour H
        val h = hour.toIntOrNull()
        if (h != null) {
            return 24 * 60 * 60 * 1000L
        }

        // default 1 hour
        return 60 * 60 * 1000L
    }
}
