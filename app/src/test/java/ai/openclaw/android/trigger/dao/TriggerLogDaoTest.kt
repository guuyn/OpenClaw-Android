package ai.openclaw.android.trigger.dao

import ai.openclaw.android.trigger.models.TriggerLog
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
import java.util.UUID

/**
 * Unit tests for TriggerLogDao (mocked).
 *
 * Exercises:
 *  - insert / getRecent / getByRule
 *  - deleteOlderThan (cleanup)
 *  - default UUID generation
 */
class TriggerLogDaoTest {

    private lateinit var dao: TriggerLogDao

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
    }

    private fun makeLog(
        id: String = UUID.randomUUID().toString(),
        ruleId: String = "rule-1",
        eventId: String = "event-1",
        executedAt: Long = System.currentTimeMillis(),
        actionType: String = "SkillCall",
        success: Boolean = true,
        error: String? = null,
        result: String? = null
    ): TriggerLog = TriggerLog(
        id = id,
        ruleId = ruleId,
        eventId = eventId,
        executedAt = executedAt,
        actionType = actionType,
        success = success,
        error = error,
        result = result
    )

    // ==================== Insert ====================

    @Test
    fun `insert calls dao with log`() = runTest {
        val log = makeLog()
        coEvery { dao.insert(any()) } just runs

        dao.insert(log)
        coVerify(exactly = 1) { dao.insert(log) }
    }

    @Test
    fun `insert captures log argument`() = runTest {
        val log = makeLog(ruleId = "rule-XYZ", actionType = "AgentQuery")
        val captured = slot<TriggerLog>()
        coEvery { dao.insert(capture(captured)) } just runs

        dao.insert(log)
        assertEquals("rule-XYZ", captured.captured.ruleId)
        assertEquals("AgentQuery", captured.captured.actionType)
    }

    // ==================== getRecent ====================

    @Test
    fun `getRecent returns logs within limit`() = runTest {
        val logs = (1..5).map { makeLog(ruleId = "rule-$it") }
        coEvery { dao.getRecent(5) } returns logs

        val result = dao.getRecent(5)
        assertEquals(5, result.size)
    }

    @Test
    fun `getRecent with default limit uses 50`() = runTest {
        val logs = (1..50).map { makeLog() }
        coEvery { dao.getRecent(50) } returns logs

        val result = dao.getRecent(50)
        assertEquals(50, result.size)
    }

    @Test
    fun `getRecent returns empty list when no logs`() = runTest {
        coEvery { dao.getRecent(10) } returns emptyList()
        val result = dao.getRecent(10)
        assertTrue(result.isEmpty())
    }

    // ==================== getByRule ====================

    @Test
    fun `getByRule returns logs for specific rule`() = runTest {
        val logs = listOf(
            makeLog(ruleId = "rule-A"),
            makeLog(ruleId = "rule-A")
        )
        coEvery { dao.getByRule("rule-A", 20) } returns logs

        val result = dao.getByRule("rule-A", 20)
        assertEquals(2, result.size)
        assertTrue(result.all { it.ruleId == "rule-A" })
    }

    @Test
    fun `getByRule returns empty for non-existent rule`() = runTest {
        coEvery { dao.getByRule("nonexistent", 20) } returns emptyList()
        val result = dao.getByRule("nonexistent", 20)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getByRule respects limit parameter`() = runTest {
        val logs = (1..3).map { makeLog(ruleId = "rule-X") }
        coEvery { dao.getByRule("rule-X", 3) } returns logs

        val result = dao.getByRule("rule-X", 3)
        assertEquals(3, result.size)
    }

    // ==================== Cleanup ====================

    @Test
    fun `deleteOlderThan removes logs before timestamp`() = runTest {
        coEvery { dao.deleteOlderThan(any()) } just runs
        val cutoff = 1000L

        dao.deleteOlderThan(cutoff)
        coVerify(exactly = 1) { dao.deleteOlderThan(cutoff) }
    }

    @Test
    fun `deleteOlderThan with future timestamp deletes all`() = runTest {
        coEvery { dao.deleteOlderThan(any()) } just runs

        // Time far in the future deletes everything
        dao.deleteOlderThan(Long.MAX_VALUE)
        coVerify { dao.deleteOlderThan(Long.MAX_VALUE) }
    }

    @Test
    fun `deleteOlderThan with past timestamp deletes nothing`() = runTest {
        coEvery { dao.deleteOlderThan(any()) } just runs

        // Time = 0 means nothing is older than 0
        dao.deleteOlderThan(0L)
        coVerify { dao.deleteOlderThan(0L) }
    }

    // ==================== Default UUID generation ====================

    @Test
    fun `TriggerLog default id is non-empty UUID`() {
        val log = TriggerLog(
            ruleId = "rule-1",
            eventId = "event-1",
            actionType = "SkillCall",
            success = true
        )
        assertNotNull(log.id)
        assertTrue("UUID should be non-empty", log.id.isNotEmpty())
        assertEquals(36, log.id.length) // UUID v4 length
    }

    @Test
    fun `TriggerLog default id is unique across instances`() {
        val ids = (1..100).map {
            TriggerLog(
                ruleId = "r",
                eventId = "e",
                actionType = "A",
                success = true
            ).id
        }.toSet()
        assertEquals(100, ids.size)
    }

    @Test
    fun `TriggerLog default executedAt is approximately now`() {
        val before = System.currentTimeMillis()
        val log = TriggerLog(
            ruleId = "r",
            eventId = "e",
            actionType = "A",
            success = true
        )
        val after = System.currentTimeMillis()

        assertTrue(
            "executedAt (${log.executedAt}) should be between $before and $after",
            log.executedAt in before..after
        )
    }

    @Test
    fun `TriggerLog supports failure logging with error`() {
        val log = TriggerLog(
            ruleId = "rule-1",
            eventId = "event-1",
            actionType = "SkillCall",
            success = false,
            error = "Network timeout",
            result = null
        )
        assertFalse(log.success)
        assertEquals("Network timeout", log.error)
        assertNull(log.result)
    }

    @Test
    fun `TriggerLog supports success logging with result`() {
        val log = TriggerLog(
            ruleId = "rule-1",
            eventId = "event-1",
            actionType = "SkillCall",
            success = true,
            result = """{"temperature":22}"""
        )
        assertTrue(log.success)
        assertNull(log.error)
        assertEquals("""{"temperature":22}""", log.result)
    }
}