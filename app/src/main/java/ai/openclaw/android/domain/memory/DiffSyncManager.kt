package ai.openclaw.android.domain.memory

import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryVectorEntity
import ai.openclaw.android.security.AuditLogger

/**
 * Incremental (diff) sync for memories between local store and an external snapshot.
 *
 * Sync logic:
 * 1. Local → Remote: export memories modified since last sync.
 * 2. Remote → Local: import remote changes, resolving conflicts by version (higher wins).
 *
 * The "remote" side is represented by a [SyncTarget] interface so this class
 * is transport-agnostic (file, cloud, peer device).
 */
class DiffSyncManager(
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingService: EmbeddingService
) {
    companion object {
        private const val TAG = "DiffSyncManager"
    }

    /** Abstraction for the remote sync target. */
    interface SyncTarget {
        suspend fun push(changes: List<MemoryEntity>)
        suspend fun pull(sinceVersion: Long): List<MemoryEntity>
        suspend fun getLastSyncTimestamp(): Long
        suspend fun setLastSyncTimestamp(timestamp: Long)
    }

    data class SyncResult(
        val pushed: Int,
        val pulled: Int,
        val conflicts: Int
    )

    /**
     * Run a full bidirectional diff sync.
     * @param target The remote sync target.
     */
    suspend fun sync(target: SyncTarget): Result<SyncResult> = runCatching {
        val lastSync = target.getLastSyncTimestamp()

        // Phase 1: Push local changes
        val localChanges = memoryDao.getModifiedSince(lastSync)
        if (localChanges.isNotEmpty()) {
            target.push(localChanges)
            AuditLogger.log("SYNC_PUSH", 0, "pushed ${localChanges.size} changes since $lastSync")
            LogManager.shared.log("INFO", TAG, "Pushed ${localChanges.size} local changes")
        }

        // Phase 2: Pull remote changes
        val remoteChanges = target.pull(lastSync)
        var pulled = 0
        var conflicts = 0

        for (remote in remoteChanges) {
            val local = memoryDao.getById(remote.id)
            if (local == null) {
                // New from remote
                insertWithVector(remote)
                AuditLogger.log("SYNC_PULL", remote.id, "new from remote")
                pulled++
            } else if (remote.version > local.version) {
                // Remote is newer — overwrite
                memoryDao.insert(remote)
                updateVectorForMemory(remote)
                AuditLogger.log("SYNC_CONFLICT", remote.id, "remote v${remote.version} > local v${local.version}")
                conflicts++
            }
            // else: local is newer or equal, skip
        }

        val now = System.currentTimeMillis()
        target.setLastSyncTimestamp(now)

        LogManager.shared.log("INFO", TAG,
            "Sync complete: pushed=${localChanges.size}, pulled=$pulled, conflicts=$conflicts")

        SyncResult(
            pushed = localChanges.size,
            pulled = pulled,
            conflicts = conflicts
        )
    }

    /**
     * Export all memories modified since [sinceTimestamp].
     */
    suspend fun exportChanges(sinceTimestamp: Long): List<MemoryEntity> {
        return memoryDao.getModifiedSince(sinceTimestamp)
    }

    private suspend fun insertWithVector(memory: MemoryEntity) {
        val id = memoryDao.insert(memory)
        val vector = if (embeddingService.isReady()) {
            embeddingService.embed(memory.content)
        } else {
            FloatArray(embeddingService.getDimension()) {
                (memory.content.hashCode() * (it + 1) % 1000) / 1000f
            }
        }
        vectorDao.insert(MemoryVectorEntity(
            memoryId = id,
            vector = vector,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private suspend fun updateVectorForMemory(memory: MemoryEntity) {
        vectorDao.deleteByMemoryId(memory.id)
        val vector = if (embeddingService.isReady()) {
            embeddingService.embed(memory.content)
        } else {
            FloatArray(embeddingService.getDimension()) {
                (memory.content.hashCode() * (it + 1) % 1000) / 1000f
            }
        }
        vectorDao.insert(MemoryVectorEntity(
            memoryId = memory.id,
            vector = vector,
            updatedAt = System.currentTimeMillis()
        ))
    }
}
