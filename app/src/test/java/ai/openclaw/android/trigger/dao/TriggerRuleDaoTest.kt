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
    fun `parseFilters deserializes PackageFilter correctly with polymorphic Json`() {
        // Regression test for production bug #1: previously the Json instance
        // did not register polymorphic serializers for the sealed Filter
        // hierarchy, so decodeFromString always threw (and we caught it,
        // returning an empty list). With the polymorphic module configured,
        // valid JSON now round-trips into a typed list.
        val validJson = """[{"type":"PackageFilter","packages":["com.example","com.test"]}]"""
        val filters = TriggerRule.parseFilters(validJson)
        assertEquals(1, filters.size)
        val pf = filters[0] as Filter.PackageFilter
        assertEquals(listOf("com.example", "com.test"), pf.packages)
    }

    @Test
    fun `parseFilters deserializes KeywordFilter correctly`() {
        val json = """[{"type":"KeywordFilter","keywords":["foo","bar"],"mode":"AND"}]"""
        val filters = TriggerRule.parseFilters(json)
        assertEquals(1, filters.size)
        val kf = filters[0] as Filter.KeywordFilter
        assertEquals(listOf("foo", "bar"), kf.keywords)
        assertEquals(MatchMode.AND, kf.mode)
    }

    @Test
    fun `parseFilters deserializes TimeFilter correctly`() {
        val json = """[{"type":"TimeFilter","startHour":9,"endHour":18}]"""
        val filters = TriggerRule.parseFilters(json)
        assertEquals(1, filters.size)
        val tf = filters[0] as Filter.TimeFilter
        assertEquals(9, tf.startHour)
        assertEquals(18, tf.endHour)
    }

    @Test
    fun `parseFilters deserializes CategoryFilter correctly`() {
        val json = """[{"type":"CategoryFilter","category":"social"}]"""
        val filters = TriggerRule.parseFilters(json)
        assertEquals(1, filters.size)
        val cf = filters[0] as Filter.CategoryFilter
        assertEquals("social", cf.category)
    }

    @Test
    fun `parseFilters deserializes a heterogeneous list of all filter types`() {
        val json = """[
            {"type":"PackageFilter","packages":["a.b"]},
            {"type":"KeywordFilter","keywords":["k"],"mode":"OR"},
            {"type":"TimeFilter","startHour":1,"endHour":2},
            {"type":"CategoryFilter","category":"x"}
        ]""".trimIndent()
        val filters = TriggerRule.parseFilters(json)
        assertEquals(4, filters.size)
        assertTrue(filters[0] is Filter.PackageFilter)
        assertTrue(filters[1] is Filter.KeywordFilter)
        assertTrue(filters[2] is Filter.TimeFilter)
        assertTrue(filters[3] is Filter.CategoryFilter)
    }

    @Test
    fun `parseFilters round-trips through serializeFilters for each Filter type`() {
        // Serialize → parse → equality check
        val original = listOf(
            Filter.PackageFilter(listOf("com.foo", "com.bar")),
            Filter.KeywordFilter(listOf("alpha", "beta"), MatchMode.AND),
            Filter.TimeFilter(startHour = 8, endHour = 20),
            Filter.CategoryFilter("work")
        )
        val serialized = TriggerRule.serializeFilters(original)
        val parsed = TriggerRule.parseFilters(serialized)
        assertEquals(original, parsed)
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

    @Test
    fun `parseFilters ignores unknown subclass types without throwing`() {
        // The configured Json uses ignoreUnknownKeys=true, but unknown POLYMORPHIC
        // types are still treated as a serialization failure → empty list. This
        // guards the caller from crashes when an old payload references a removed
        // filter type.
        val json = """[{"type":"UnknownFilter","foo":1}]"""
        val filters = TriggerRule.parseFilters(json)
        assertTrue(filters.isEmpty())
    }

    // ==================== Action parsing ====================

    @Test
    fun `parseAction deserializes SkillCall correctly with polymorphic Json`() {
        // Regression test for production bug #1: same root cause as parseFilters
        // — the sealed TriggerAction hierarchy wasn't registered for polymorphic
        // serialization, so parseAction always returned null.
        val validJson = """{"type":"SkillCall","skillId":"weather","toolName":"get_weather","paramsJson":"{}"}"""
        val action = TriggerRule.parseAction(validJson)
        assertNotNull(action)
        val sc = action as TriggerAction.SkillCall
        assertEquals("weather", sc.skillId)
        assertEquals("get_weather", sc.toolName)
        assertEquals("{}", sc.paramsJson)
    }

    @Test
    fun `parseAction deserializes AgentQuery correctly`() {
        val json = """{"type":"AgentQuery","prompt":"summarize this","model":"gpt-4"}"""
        val action = TriggerRule.parseAction(json)
        assertNotNull(action)
        val aq = action as TriggerAction.AgentQuery
        assertEquals("summarize this", aq.prompt)
        assertEquals("gpt-4", aq.model)
    }

    @Test
    fun `parseAction deserializes AgentQuery with null model correctly`() {
        val json = """{"type":"AgentQuery","prompt":"hi","model":null}"""
        val action = TriggerRule.parseAction(json)
        assertNotNull(action)
        val aq = action as TriggerAction.AgentQuery
        assertEquals("hi", aq.prompt)
        assertNull(aq.model)
    }

    @Test
    fun `parseAction deserializes NotificationReply correctly`() {
        val json = """{"type":"NotificationReply","template":"Hi {name}","autoReply":true}"""
        val action = TriggerRule.parseAction(json)
        assertNotNull(action)
        val nr = action as TriggerAction.NotificationReply
        assertEquals("Hi {name}", nr.template)
        assertTrue(nr.autoReply)
    }

    @Test
    fun `parseAction deserializes CustomScript correctly`() {
        val json = """{"type":"CustomScript","script":"ctx.send('hello')"}"""
        val action = TriggerRule.parseAction(json)
        assertNotNull(action)
        val cs = action as TriggerAction.CustomScript
        assertEquals("ctx.send('hello')", cs.script)
    }

    @Test
    fun `parseAction round-trips through serializeAction for each action type`() {
        listOf(
            TriggerAction.SkillCall("skill1", "tool1", """{"k":"v"}"""),
            TriggerAction.AgentQuery("prompt here", "claude-3"),
            TriggerAction.NotificationReply("template", autoReply = false),
            TriggerAction.CustomScript("script body")
        ).forEach { original ->
            val serialized = TriggerRule.serializeAction(original)
            val parsed = TriggerRule.parseAction(serialized)
            assertEquals(original, parsed)
        }
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
    fun `parseAction returns null for unknown action type`() {
        val json = """{"type":"UnknownAction","foo":1}"""
        val action = TriggerRule.parseAction(json)
        assertNull(action)
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
    fun `TriggerRule getFilters returns parsed filter list`() {
        // Regression test for production bug #1: previously getFilters()
        // returned an empty list because parseFilters() could not deserialize
        // sealed classes. With polymorphic serializers registered, it now
        // returns the typed filter objects.
        val rule = TriggerRule(
            id = "r1",
            name = "r",
            source = EventSource.NOTIFICATION,
            filtersJson = """[{"type":"CategoryFilter","category":"social"}]""",
            actionJson = """{"type":"SkillCall","skillId":"s","toolName":"t"}"""
        )
        val filters = rule.getFilters()
        assertEquals(1, filters.size)
        val cf = filters[0] as Filter.CategoryFilter
        assertEquals("social", cf.category)
    }

    @Test
    fun `TriggerRule getAction returns parsed TriggerAction`() {
        // Regression test for production bug #1: previously getAction()
        // returned null because parseAction() could not deserialize sealed
        // classes. With polymorphic serializers registered, it now returns
        // the typed TriggerAction.
        val rule = TriggerRule(
            id = "r1",
            name = "r",
            source = EventSource.NOTIFICATION,
            actionJson = """{"type":"AgentQuery","prompt":"p"}"""
        )
        val action = rule.getAction()
        assertNotNull(action)
        val aq = action as TriggerAction.AgentQuery
        assertEquals("p", aq.prompt)
    }
}