package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
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

/**
 * Unit tests for DiffSyncManager.
 *
 * Sync logic:
 * 1. Local → Remote: push memories modified since last sync.
 * 2. Remote → Local: pull remote changes, resolve conflicts by version (higher wins).
 */
class DiffSyncManagerTest {

    private lateinit var memoryDao: MemoryDao
    private lateinit var vectorDao: MemoryVectorDao
    private lateinit var embeddingService: EmbeddingService
    private lateinit var target: DiffSyncManager.SyncTarget
    private lateinit var syncManager: DiffSyncManager

    @Before
    fun setUp() {
        memoryDao = mockk(relaxed = true)
        vectorDao = mockk(relaxed = true)
        embeddingService = mockk(relaxed = true)
        target = mockk(relaxed = true)
        syncManager = DiffSyncManager(memoryDao, vectorDao, embeddingService)

        // Default: no embedding service available (use hash-based fallback)
        every { embeddingService.isReady() } returns false
        every { embeddingService.getDimension() } returns 384
    }

    private fun memory(
        id: Long,
        content: String = "test",
        version: Int = 1,
        lastAccessedAt: Long = 1000L
    ): MemoryEntity = MemoryEntity(
        id = id,
        content = content,
        memoryType = MemoryType.FACT,
        priority = 3,
        source = "test",
        tags = emptyList(),
        createdAt = 1000L,
        lastAccessedAt = lastAccessedAt,
        version = version
    )

    // ==================== Empty sync ====================

    @Test
    fun `sync with no changes returns zero counts`() = runTest {
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns emptyList()
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertTrue(result.isSuccess)
        val data = result.getOrNull()!!
        assertEquals(0, data.pushed)
        assertEquals(0, data.pulled)
        assertEquals(0, data.conflicts)
    }

    @Test
    fun `sync updates lastSyncTimestamp after completion`() = runTest {
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns emptyList()
        val captured = slot<Long>()
        coEvery { target.setLastSyncTimestamp(capture(captured)) } just runs

        val beforeSync = System.currentTimeMillis()
        syncManager.sync(target)
        val afterSync = System.currentTimeMillis()

        coVerify { target.setLastSyncTimestamp(any()) }
        // The timestamp should be within a reasonable range
        assertTrue(
            "Set timestamp (${captured.captured}) should be in [$beforeSync, $afterSync]",
            captured.captured in beforeSync..afterSync
        )
    }

    // ==================== Push (local → remote) ====================

    @Test
    fun `sync pushes local changes to remote`() = runTest {
        val localChanges = listOf(
            memory(id = 1L, lastAccessedAt = 500L),
            memory(id = 2L, lastAccessedAt = 600L)
        )
        coEvery { target.getLastSyncTimestamp() } returns 100L
        coEvery { memoryDao.getModifiedSince(100L) } returns localChanges
        coEvery { target.push(any()) } just runs
        coEvery { target.pull(100L) } returns emptyList()
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.pushed)
        coVerify { target.push(localChanges) }
    }

    @Test
    fun `push is skipped when there are no local changes`() = runTest {
        coEvery { target.getLastSyncTimestamp() } returns 100L
        coEvery { memoryDao.getModifiedSince(100L) } returns emptyList()
        coEvery { target.pull(100L) } returns emptyList()
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        syncManager.sync(target)
        // push should NOT be called when there are no changes
        coVerify(exactly = 0) { target.push(any()) }
    }

    // ==================== Pull (remote → local) ====================

    @Test
    fun `sync inserts new memories from remote`() = runTest {
        val remoteChange = memory(id = 99L, content = "new from remote")
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remoteChange)
        coEvery { memoryDao.getById(99L) } returns null
        coEvery { memoryDao.insert(any()) } returns 99L
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { vectorDao.deleteByMemoryId(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertEquals(1, result.getOrNull()!!.pulled)
        assertEquals(0, result.getOrNull()!!.conflicts)
        coVerify { memoryDao.insert(remoteChange) }
        coVerify { vectorDao.insert(any()) }
    }

    @Test
    fun `sync resolves conflicts via last-write-wins (higher version wins)`() = runTest {
        val localOld = memory(id = 5L, content = "old content", version = 1)
        val remoteNew = memory(id = 5L, content = "new content", version = 3)

        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remoteNew)
        coEvery { memoryDao.getById(5L) } returns localOld
        coEvery { memoryDao.insert(any()) } returns 5L
        coEvery { vectorDao.deleteByMemoryId(any()) } just runs
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertEquals(0, result.getOrNull()!!.pulled)
        assertEquals(1, result.getOrNull()!!.conflicts)
        // Remote (newer version) should overwrite local
        coVerify { memoryDao.insert(remoteNew) }
    }

    @Test
    fun `sync keeps local when local version is newer`() = runTest {
        val localNew = memory(id = 5L, content = "new local", version = 5)
        val remoteOld = memory(id = 5L, content = "old remote", version = 2)

        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remoteOld)
        coEvery { memoryDao.getById(5L) } returns localNew
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertEquals(0, result.getOrNull()!!.pulled)
        assertEquals(0, result.getOrNull()!!.conflicts)
        // Local should NOT be overwritten
        coVerify(exactly = 0) { memoryDao.insert(remoteOld) }
    }

    @Test
    fun `sync keeps local when versions are equal`() = runTest {
        val same = memory(id = 5L, version = 2)
        val remoteSame = memory(id = 5L, version = 2)

        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remoteSame)
        coEvery { memoryDao.getById(5L) } returns same
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertEquals(0, result.getOrNull()!!.pulled)
        assertEquals(0, result.getOrNull()!!.conflicts)
        coVerify(exactly = 0) { memoryDao.insert(any()) }
    }

    // ==================== Mixed push and pull ====================

    @Test
    fun `sync handles multiple new memories from remote`() = runTest {
        val remotes = listOf(
            memory(id = 10L),
            memory(id = 11L),
            memory(id = 12L)
        )
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns remotes
        coEvery { memoryDao.getById(any()) } returns null
        coEvery { memoryDao.insert(any()) } returns 1L
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        assertEquals(3, result.getOrNull()!!.pulled)
        coVerify(exactly = 3) { memoryDao.insert(any()) }
    }

    @Test
    fun `sync handles mix of new, conflicting, and unchanged memories`() = runTest {
        val newRemote = memory(id = 100L, version = 1) // new
        val conflictRemote = memory(id = 5L, version = 5) // newer than local
        val unchangedRemote = memory(id = 7L, version = 1) // same as local

        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(newRemote, conflictRemote, unchangedRemote)
        coEvery { memoryDao.getById(100L) } returns null
        coEvery { memoryDao.getById(5L) } returns memory(id = 5L, version = 1)
        coEvery { memoryDao.getById(7L) } returns memory(id = 7L, version = 1)
        coEvery { memoryDao.insert(any()) } returns 1L
        coEvery { vectorDao.deleteByMemoryId(any()) } just runs
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        val result = syncManager.sync(target)
        val data = result.getOrNull()!!
        assertEquals(1, data.pulled)
        assertEquals(1, data.conflicts)
    }

    // ==================== Embedding service integration ====================

    @Test
    fun `sync uses embedding service when ready`() = runTest {
        every { embeddingService.isReady() } returns true
        coEvery { embeddingService.embed("test") } returns FloatArray(384) { 0.5f }

        val remote = memory(id = 100L, content = "test")
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remote)
        coEvery { memoryDao.getById(100L) } returns null
        coEvery { memoryDao.insert(any()) } returns 100L
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        syncManager.sync(target)
        coVerify { embeddingService.embed("test") }
    }

    @Test
    fun `sync uses hash-based fallback when embedding service unavailable`() = runTest {
        every { embeddingService.isReady() } returns false

        val remote = memory(id = 100L, content = "test")
        coEvery { target.getLastSyncTimestamp() } returns 0L
        coEvery { memoryDao.getModifiedSince(0L) } returns emptyList()
        coEvery { target.pull(0L) } returns listOf(remote)
        coEvery { memoryDao.getById(100L) } returns null
        coEvery { memoryDao.insert(any()) } returns 100L
        coEvery { vectorDao.insert(any()) } just runs
        coEvery { target.setLastSyncTimestamp(any()) } just runs

        syncManager.sync(target)
        // Should NOT call embed when service is not ready
        coVerify(exactly = 0) { embeddingService.embed(any()) }
    }

    // ==================== exportChanges ====================

    @Test
    fun `exportChanges returns memories modified since timestamp`() = runTest {
        val expected = listOf(memory(id = 1L), memory(id = 2L))
        coEvery { memoryDao.getModifiedSince(1000L) } returns expected

        val result = syncManager.exportChanges(1000L)
        assertEquals(expected, result)
    }

    @Test
    fun `exportChanges returns empty list when no changes`() = runTest {
        coEvery { memoryDao.getModifiedSince(any()) } returns emptyList()
        val result = syncManager.exportChanges(System.currentTimeMillis())
        assertTrue(result.isEmpty())
    }

    // ==================== SyncResult data class ====================

    @Test
    fun `SyncResult data class equality`() {
        val r1 = DiffSyncManager.SyncResult(pushed = 5, pulled = 3, conflicts = 1)
        val r2 = DiffSyncManager.SyncResult(pushed = 5, pulled = 3, conflicts = 1)
        assertEquals(r1, r2)
    }
}