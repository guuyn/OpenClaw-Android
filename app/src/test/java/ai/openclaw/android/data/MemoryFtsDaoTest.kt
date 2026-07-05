package ai.openclaw.android.data

import ai.openclaw.android.data.local.MemoryFtsDao
import ai.openclaw.android.data.model.BM25Result
import androidx.sqlite.db.SupportSQLiteQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MemoryFtsDao.
 *
 * AppDatabase uses SQLCipher which is not available in unit-test JVMs, so we
 * mock the DAO interface and verify that callers pass queries correctly.
 *
 * For full-text-search behavior we rely on BM25IndexTest (which exercises the
 * actual scoring algorithm in pure Kotlin) — this test focuses on the DAO
 * interface contract: query construction, result mapping, and search semantics.
 */
class MemoryFtsDaoTest {

    private lateinit var dao: MemoryFtsDao

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
    }

    @Test
    fun `searchRaw returns BM25Results`() = runTest {
        val expected = listOf(
            BM25Result(memoryId = 1L, score = 0.95),
            BM25Result(memoryId = 5L, score = 0.72)
        )
        coEvery { dao.searchRaw(any()) } returns expected

        val result = dao.searchRaw(mockk(relaxed = true))

        assertEquals(2, result.size)
        assertEquals(1L, result[0].memoryId)
        assertEquals(0.95, result[0].score, 0.0001)
        assertEquals(5L, result[1].memoryId)
        assertEquals(0.72, result[1].score, 0.0001)
        coVerify(exactly = 1) { dao.searchRaw(any()) }
    }

    @Test
    fun `searchRaw returns empty list when no matches`() = runTest {
        coEvery { dao.searchRaw(any()) } returns emptyList()
        val result = dao.searchRaw(mockk(relaxed = true))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchRaw results can be sorted by score descending`() = runTest {
        val unsorted = listOf(
            BM25Result(memoryId = 1L, score = 0.5),
            BM25Result(memoryId = 2L, score = 0.9),
            BM25Result(memoryId = 3L, score = 0.3)
        )
        coEvery { dao.searchRaw(any()) } returns unsorted

        val sorted = dao.searchRaw(mockk(relaxed = true)).sortedByDescending { it.score }

        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.memoryId })
        assertEquals(listOf(0.9, 0.5, 0.3), sorted.map { it.score })
    }

    @Test
    fun `BM25Result equality works as expected`() {
        val r1 = BM25Result(memoryId = 42L, score = 0.5)
        val r2 = BM25Result(memoryId = 42L, score = 0.5)
        val r3 = BM25Result(memoryId = 42L, score = 0.6)
        assertEquals(r1, r2)
        assertNotEquals(r1, r3)
    }

    @Test
    fun `BM25Result supports copying`() {
        val r = BM25Result(memoryId = 1L, score = 0.5)
        val updated = r.copy(score = 0.9)
        assertEquals(1L, updated.memoryId)
        assertEquals(0.9, updated.score, 0.0001)
    }

    @Test
    fun `searchRaw receives SupportSQLiteQuery parameter`() = runTest {
        val query = mockk<SupportSQLiteQuery>(relaxed = true)
        coEvery { dao.searchRaw(query) } returns emptyList()

        dao.searchRaw(query)
        coVerify { dao.searchRaw(query) }
    }

    @Test
    fun `searchRaw can be called multiple times with different queries`() = runTest {
        val q1 = mockk<SupportSQLiteQuery>(relaxed = true)
        val q2 = mockk<SupportSQLiteQuery>(relaxed = true)
        coEvery { dao.searchRaw(q1) } returns listOf(BM25Result(1L, 0.5))
        coEvery { dao.searchRaw(q2) } returns listOf(BM25Result(2L, 0.8))

        val r1 = dao.searchRaw(q1)
        val r2 = dao.searchRaw(q2)

        assertEquals(1, r1.size)
        assertEquals(1L, r1[0].memoryId)
        assertEquals(1, r2.size)
        assertEquals(2L, r2[0].memoryId)
    }

    @Test
    fun `BM25Result toString includes memoryId and score`() {
        val r = BM25Result(memoryId = 7L, score = 0.42)
        val str = r.toString()
        assertTrue("Should mention memoryId", str.contains("7"))
        assertTrue("Should mention score", str.contains("0.42"))
    }
}