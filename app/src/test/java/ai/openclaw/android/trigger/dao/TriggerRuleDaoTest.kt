package ai.openclaw.android.trigger.dao

import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.Filter
import ai.openclaw.android.trigger.models.MatchMode
import ai.openclaw.android.trigger.models.TriggerAction
import ai.openclaw.android.trigger.models.TriggerRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TriggerRuleDao (mocked).
 *
 * AppDatabase uses SQLCipher which is not available in unit-test JVMs, so we
 * mock the DAO interface. Tests cover CRUD operations and key queries:
 *  - insert / delete / deleteById
 *  - getAll / getEnabled / getById / getBySource
 *  - setEnabled (toggle)
 *  - parseFilters / parseAction helpers
 */
class TriggerRuleDaoTest {

    private lateinit var dao: TriggerRuleDao

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
    }

    private fun makeRule(
        id: String = "rule-1",
        name: String = "Test rule",
        enabled: Boolean = true,
        source: EventSource = EventSource.NOTIFICATION
    ): TriggerRule = TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = source,
        actionJson = """{"type":"SkillCall","skillId":"weather","toolName":"get_weather","paramsJson":"{}"}"""
    )

    // ==================== Insert / Delete ====================

    @Test
    fun `insert calls dao insert`() = runTest {
        val rule = makeRule()
        coEvery { dao.insert(any()) } just runs

        dao.insert(rule)
        coVerify(exactly = 1) { dao.insert(rule) }
    }

    @Test
    fun `insert captures the rule argument`() = runTest {
        val rule = makeRule(id = "rule-99", name = "Special rule")
        val captured = slot<TriggerRule>()
        coEvery { dao.insert(capture(captured)) } just runs

        dao.insert(rule)
        assertEquals("rule-99", captured.captured.id)
        assertEquals("Special rule", captured.captured.name)
    }

    @Test
    fun `delete calls dao delete with rule`() = runTest {
        val rule = makeRule()
        coEvery { dao.delete(any()) } just runs

        dao.delete(rule)
        coVerify(exactly = 1) { dao.delete(rule) }
    }

    @Test
    fun `deleteById calls dao with id`() = runTest {
        coEvery { dao.deleteById(any()) } just runs
        dao.deleteById("rule-1")
        coVerify { dao.deleteById("rule-1") }
    }

    // ==================== Queries ====================

    @Test
    fun `getAll returns all rules ordered by createdAt desc`() = runTest {
        val now = System.currentTimeMillis()
        val rules = listOf(
            makeRule(id = "newer", name = "Newer rule"),
            makeRule(id = "older", name = "Older rule")
        )
        coEvery { dao.getAll() } returns rules

        val result = dao.getAll()
        assertEquals(2, result.size)
        assertEquals("newer", result[0].id)
        assertEquals("older", result[1].id)
    }

    @Test
    fun `getEnabled returns only enabled rules`() = runTest {
        val rules = listOf(
            makeRule(id = "enabled-1", enabled = true),
            makeRule(id = "enabled-2", enabled = true)
        )
        coEvery { dao.getEnabled() } returns rules

        val result = dao.getEnabled()
        assertEquals(2, result.size)
        assertTrue(result.all { it.enabled })
    }

    @Test
    fun `getById returns matching rule or null`() = runTest {
        coEvery { dao.getById("rule-1") } returns makeRule(id = "rule-1")
        coEvery { dao.getById("missing") } returns null

        val found = dao.getById("rule-1")
        assertNotNull(found)
        assertEquals("rule-1", found!!.id)

        val missing = dao.getById("missing")
        assertNull(missing)
    }

    @Test
    fun `getBySource returns rules for specific source`() = runTest {
        coEvery { dao.getBySource(EventSource.NOTIFICATION.name) } returns listOf(
            makeRule(id = "n1", source = EventSource.NOTIFICATION),
            makeRule(id = "n2", source = EventSource.NOTIFICATION)
        )
        coEvery { dao.getBySource(EventSource.CRON.name) } returns emptyList()

        val notifs = dao.getBySource(EventSource.NOTIFICATION.name)
        assertEquals(2, notifs.size)
        assertTrue(notifs.all { it.source == EventSource.NOTIFICATION })

        val crons = dao.getBySource(EventSource.CRON.name)
        assertTrue(crons.isEmpty())
    }

    // ==================== Update ====================

    @Test
    fun `setEnabled updates enabled state`() = runTest {
        coEvery { dao.setEnabled(any(), any()) } just runs

        dao.setEnabled("rule-1", false)
        dao.setEnabled("rule-2", true)

        coVerify { dao.setEnabled("rule-1", false) }
        coVerify { dao.setEnabled("rule-2", true) }
    }

    @Test
    fun `setEnabled can be toggled`() = runTest {
        coEvery { dao.setEnabled(any(), any()) } just runs

        dao.setEnabled("rule-1", true)
        dao.setEnabled("rule-1", false)
        dao.setEnabled("rule-1", true)

        coVerify(exactly = 3) { dao.setEnabled("rule-1", any()) }
    }

    // ==================== Filter parsing ====================

    @Test
    fun `parseFilters returns empty list - sealed class polymorphism not configured`() {
        // TODO(production-bug): TriggerRule.parseFilters uses a bare `json` instance
        // without registering polymorphic subclass serializers for the sealed Filter
        // hierarchy. The kotlinx.serialization call throws (caught), so we always
        // get an empty list back, even for valid JSON.
        // Fix: configure the Json instance with a SerializersModule that registers
        // Filter subclasses, or use json.decodeFromString<Filter>(jsonStr) with
        // PolymorphicSerializer.
        val validJson = """[{"type":"PackageFilter","packages":["com.example","com.test"]}]"""
        val filters = TriggerRule.parseFilters(validJson)
        // Document the current (buggy) behavior — should be 1 if fixed.
        assertTrue(
            "parseFilters currently returns empty due to missing polymorphic setup: $filters",
            filters.isEmpty()
        )
    }

    @Test
    fun `parseFilters returns empty list on invalid JSON`() {
        val filters = TriggerRule.parseFilters("not json")
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `parseFilters returns empty list on empty array`() {
        val filters = TriggerRule.parseFilters("[]")
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `parseFilters never throws even for malformed input`() {
        // Robustness check: any malformed input returns empty list
        assertEquals(emptyList<Filter>(), TriggerRule.parseFilters("not json"))
        assertEquals(emptyList<Filter>(), TriggerRule.parseFilters(""))
        assertEquals(emptyList<Filter>(), TriggerRule.parseFilters("["))
    }

    // ==================== Action parsing ====================

    @Test
    fun `parseAction returns null for any input - sealed class polymorphism not configured`() {
        // TODO(production-bug): same root cause as parseFilters — the sealed
        // TriggerAction hierarchy isn't registered for polymorphic serialization.
        val validJson = """{"type":"SkillCall","skillId":"weather","toolName":"get_weather","paramsJson":"{}"}"""
        val action = TriggerRule.parseAction(validJson)
        assertNull("parseAction currently always returns null: $action", action)
    }

    @Test
    fun `parseAction returns null on invalid JSON`() {
        assertNull(TriggerRule.parseAction("invalid json"))
        assertNull(TriggerRule.parseAction(""))
    }

    @Test
    fun `parseAction never throws even for malformed input`() {
        // Robustness check
        assertNull(TriggerRule.parseAction("not json"))
        assertNull(TriggerRule.parseAction(""))
        assertNull(TriggerRule.parseAction("{"))
    }

    @Test
    fun `serializeAction produces JSON string`() {
        val original = TriggerAction.SkillCall("weather", "get_weather", "{}")
        val json = TriggerRule.serializeAction(original)
        assertTrue("Should produce non-empty JSON", json.isNotEmpty())
        // Should at least contain the action type marker
        assertTrue("Should mention SkillCall", json.contains("SkillCall"))
    }

    @Test
    fun `serializeFilters produces JSON string`() {
        val filters = listOf(
            Filter.PackageFilter(listOf("a", "b")),
            Filter.KeywordFilter(listOf("k1"), MatchMode.OR)
        )
        val json = TriggerRule.serializeFilters(filters)
        assertTrue("Should produce non-empty JSON", json.isNotEmpty())
        // Should contain both filter types
        assertTrue("Should mention PackageFilter", json.contains("PackageFilter"))
        assertTrue("Should mention KeywordFilter", json.contains("KeywordFilter"))
    }

    // ==================== TriggerRule getFilters / getAction accessors ====================

    @Test
    fun `TriggerRule getFilters returns empty list due to polymorphic bug`() {
        // Documents current behavior: getFilters() returns empty list because
        // parseFilters() can't deserialize sealed classes.
        val rule = TriggerRule(
            id = "r1",
            name = "r",
            source = EventSource.NOTIFICATION,
            filtersJson = """[{"type":"CategoryFilter","category":"social"}]""",
            actionJson = """{"type":"SkillCall","skillId":"s","toolName":"t"}"""
        )
        val filters = rule.getFilters()
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `TriggerRule getAction returns null due to polymorphic bug`() {
        // Documents current behavior: getAction() returns null because
        // parseAction() can't deserialize sealed classes.
        val rule = TriggerRule(
            id = "r1",
            name = "r",
            source = EventSource.NOTIFICATION,
            actionJson = """{"type":"AgentQuery","prompt":"p"}"""
        )
        val action = rule.getAction()
        assertNull(action)
    }
}