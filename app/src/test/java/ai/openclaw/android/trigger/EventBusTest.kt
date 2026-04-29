package ai.openclaw.android.trigger

import ai.openclaw.android.trigger.dao.TriggerLogDao
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.models.*
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for EventBus core logic:
 * - Filter matching (Package, Keyword, Time, Category)
 * - Deduplication
 * - Cooldown/debounce
 * - Rule execution flow
 */
class EventBusTest {

    private val mockRuleDao: TriggerRuleDao = mockk(relaxed = true)
    private val mockLogDao: TriggerLogDao = mockk(relaxed = true)
    private val mockActionExecutor: ActionExecutor = mockk(relaxed = true)

    private lateinit var eventBus: EventBus

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        eventBus = EventBus.forTesting(mockRuleDao, mockLogDao, mockActionExecutor)
    }

    @After
    fun tearDown() {
        EventBus.reset()
        clearAllMocks()
    }

    // ==================== Filter Matching ====================

    @Test
    fun `PackageFilter matches when event package contains filter package`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.PackageFilter(listOf("com.example"))),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("package" to "com.example.app")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `PackageFilter does not match when package is different`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.PackageFilter(listOf("com.other"))),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("package" to "com.example.app")
        )

        eventBus.publish(event)

        coVerify(exactly = 0) { mockActionExecutor.execute(any(), any()) }
    }

    @Test
    fun `KeywordFilter OR mode matches any keyword`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.KeywordFilter(listOf("error", "warning"), MatchMode.OR)),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "Hello", "text" to "this is a warning message")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `KeywordFilter AND mode requires all keywords`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.KeywordFilter(listOf("error", "critical"), MatchMode.AND)),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)

        // Only has "error", missing "critical"
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "Error occurred", "text" to "something broke")
        )

        eventBus.publish(event)

        coVerify(exactly = 0) { mockActionExecutor.execute(any(), any()) }
    }

    @Test
    fun `KeywordFilter AND mode matches when all keywords present`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.KeywordFilter(listOf("error", "critical"), MatchMode.AND)),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "Critical error", "text" to "system failure")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `KeywordFilter EXACT mode matches exact text`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.KeywordFilter(listOf("hello match"), MatchMode.EXACT)),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        // EventBus combines title + " " + text, so "hello" + " " + "match" = "hello match"
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("title" to "hello", "text" to "match")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `CategoryFilter matches when category equals`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.CategoryFilter("social")),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("category" to "social", "text" to "new message")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `CategoryFilter does not match different category`() = runTest {
        val rule = createRule(
            filters = listOf(Filter.CategoryFilter("social")),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("category" to "work", "text" to "meeting")
        )

        eventBus.publish(event)

        coVerify(exactly = 0) { mockActionExecutor.execute(any(), any()) }
    }

    @Test
    fun `Rule with no filters matches all events from same source`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("anything" to "goes")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    // ==================== Deduplication ====================

    @Test
    fun `Duplicate events within TTL are deduplicated`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            dedupKey = "same-key",
            payload = mapOf("text" to "hello")
        )

        eventBus.publish(event)
        eventBus.publish(event)
        eventBus.publish(event)

        // Only the first should trigger execution
        coVerify(exactly = 1) { mockActionExecutor.execute(any(), any()) }
        assertEquals(1, eventBus.getDedupCache().size)
    }

    @Test
    fun `Events with different dedupKeys are not deduplicated`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION,
            cooldownMs = 0L
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event1 = TriggerEvent(
            source = EventSource.NOTIFICATION,
            dedupKey = "key-1",
            payload = mapOf("text" to "hello")
        )
        val event2 = TriggerEvent(
            source = EventSource.NOTIFICATION,
            dedupKey = "key-2",
            payload = mapOf("text" to "world")
        )

        eventBus.publish(event1)
        eventBus.publish(event2)

        coVerify(exactly = 2) { mockActionExecutor.execute(any(), any()) }
    }

    @Test
    fun `Events without dedupKey are never deduplicated`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION,
            cooldownMs = 0L
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("text" to "no key")
        )

        eventBus.publish(event)
        eventBus.publish(event)

        // Same payload but no dedupKey → both execute
        coVerify(exactly = 2) { mockActionExecutor.execute(any(), any()) }
    }

    // ==================== Cooldown / Debounce ====================

    @Test
    fun `Rule in cooldown is skipped`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION,
            cooldownMs = 10000L
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockRuleDao.getById(any()) } returns rule
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)
        coEvery { mockLogDao.getRecent(any()) } returns listOf(
            TriggerLog(ruleId = rule.id, eventId = "1", actionType = "SkillCall", success = true)
        )

        // First execution
        eventBus.publish(TriggerEvent(source = EventSource.NOTIFICATION, payload = emptyMap()))
        coVerify(exactly = 1) { mockActionExecutor.execute(any(), any()) }

        // Second execution should be blocked by cooldown
        eventBus.publish(TriggerEvent(source = EventSource.NOTIFICATION, payload = emptyMap()))
        // Still only 1 execution (cooldown blocked the second)
        coVerify(exactly = 1) { mockActionExecutor.execute(any(), any()) }

        assertTrue(eventBus.getCooldowns().isNotEmpty())
    }

    // ==================== Source Matching ====================

    @Test
    fun `Rules only match events from the same source`() = runTest {
        val cronRule = createRule(
            filters = emptyList(),
            source = EventSource.CRON
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(cronRule)

        // NOTIFICATION event should not match CRON rule
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = emptyMap()
        )

        eventBus.publish(event)

        coVerify(exactly = 0) { mockActionExecutor.execute(any(), any()) }
    }

    // ==================== Multiple Filters ====================

    @Test
    fun `Multiple filters must all match (AND semantics)`() = runTest {
        val rule = createRule(
            filters = listOf(
                Filter.PackageFilter(listOf("com.example")),
                Filter.KeywordFilter(listOf("urgent"), MatchMode.OR)
            ),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        // Matches both package and keyword
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("package" to "com.example.app", "text" to "urgent alert")
        )

        eventBus.publish(event)

        coVerify(exactly = 1) { mockActionExecutor.execute(any(), event) }
    }

    @Test
    fun `Multiple filters reject when one does not match`() = runTest {
        val rule = createRule(
            filters = listOf(
                Filter.PackageFilter(listOf("com.example")),
                Filter.KeywordFilter(listOf("urgent"), MatchMode.OR)
            ),
            source = EventSource.NOTIFICATION
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)

        // Package matches but keyword doesn't
        val event = TriggerEvent(
            source = EventSource.NOTIFICATION,
            payload = mapOf("package" to "com.example.app", "text" to "regular notification")
        )

        eventBus.publish(event)

        coVerify(exactly = 0) { mockActionExecutor.execute(any(), any()) }
    }

    // ==================== resetState ====================

    @Test
    fun `resetState clears cooldowns and dedup cache`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION,
            cooldownMs = 10000L
        )
        coEvery { mockRuleDao.getEnabled() } returns listOf(rule)
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true)

        // Publish an event to populate state
        eventBus.publish(TriggerEvent(
            source = EventSource.NOTIFICATION,
            dedupKey = "test-key",
            payload = emptyMap()
        ))

        assertTrue(eventBus.getCooldowns().isNotEmpty())
        assertTrue(eventBus.getDedupCache().isNotEmpty())

        // Reset
        eventBus.resetState()

        assertTrue(eventBus.getCooldowns().isEmpty())
        assertTrue(eventBus.getDedupCache().isEmpty())
    }

    // ==================== Manual Trigger ====================

    @Test
    fun `triggerRuleManually executes rule and returns log`() = runTest {
        val rule = createRule(
            filters = emptyList(),
            source = EventSource.NOTIFICATION,
            cooldownMs = 0L
        )
        coEvery { mockRuleDao.getById(rule.id) } returns rule
        coEvery { mockActionExecutor.execute(any(), any()) } returns ActionResult(success = true, result = "done")
        coEvery { mockLogDao.getRecent(1) } returns listOf(
            TriggerLog(ruleId = rule.id, eventId = "manual-1", actionType = "SkillCall", success = true, result = "done")
        )

        val log = eventBus.triggerRuleManually(rule.id)

        assertTrue(log.success)
        assertEquals("done", log.result)
        coVerify(exactly = 1) { mockActionExecutor.execute(any(), any()) }
    }

    // ==================== Helper ====================

    private fun createRule(
        id: String = "test-rule",
        name: String = "Test Rule",
        source: EventSource = EventSource.NOTIFICATION,
        filters: List<Filter> = emptyList(),
        action: TriggerAction = TriggerAction.SkillCall(
            skillId = "test",
            toolName = "test_tool",
            paramsJson = "{}"
        ),
        cooldownMs: Long = 300_000L,
        scheduleCron: String? = null
    ): TriggerRule {
        // Use TriggerRule's own serialization to produce correct JSON format
        val filtersJson = TriggerRule.serializeFilters(filters)
        val actionJson = TriggerRule.serializeAction(action)

        return TriggerRule(
            id = id,
            name = name,
            source = source,
            filtersJson = filtersJson,
            actionJson = actionJson,
            cooldownMs = cooldownMs,
            scheduleCron = scheduleCron
        )
    }
}
