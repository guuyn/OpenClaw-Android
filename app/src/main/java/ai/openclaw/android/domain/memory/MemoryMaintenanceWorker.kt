package ai.openclaw.android.domain.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.AppDatabase
import org.koin.mp.KoinPlatform

/**
 * WorkManager periodic worker for memory maintenance:
 * - Cleanup expired memories (forgetting curve)
 * - Rebuild BM25 index
 * - Storage threshold enforcement
 */
class MemoryMaintenanceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MemoryMaintenanceWorker"
    }

    override suspend fun doWork(): Result {
        LogManager.shared.log("INFO", TAG, "Starting memory maintenance...")

        return try {
            val db = KoinPlatform.getKoin().get<AppDatabase>()
            val memoryDao = db.memoryDao()
            val vectorDao = db.memoryVectorDao()
            val embeddingService = KoinPlatform.getKoin().get<EmbeddingService>()
            val bm25Index = KoinPlatform.getKoin().get<ai.openclaw.android.data.local.BM25Index>()

            val memoryManager = MemoryManager(
                memoryDao = memoryDao,
                vectorDao = vectorDao,
                embeddingService = embeddingService,
                extractor = object : MemoryExtractorInterface {
                    override suspend fun extractFromConversation(messages: List<ai.openclaw.android.data.model.MessageEntity>): kotlin.Result<List<ai.openclaw.android.data.model.MemoryEntity>> =
                        kotlin.Result.success(emptyList())
                    override suspend fun extractFromUserInput(content: String, type: ai.openclaw.android.data.model.MemoryType?): kotlin.Result<ai.openclaw.android.data.model.MemoryEntity> =
                        kotlin.Result.failure(UnsupportedOperationException())
                }
            )

            // 1. Cleanup expired memories
            val deleted = memoryManager.cleanup()
            LogManager.shared.log("INFO", TAG, "Cleaned up $deleted expired memories")

            // 2. Rebuild BM25 index
            bm25Index.rebuildFromDao(memoryDao)
            LogManager.shared.log("INFO", TAG, "BM25 index rebuilt (${bm25Index.size} docs)")

            // 3. Storage threshold enforcement
            val count = memoryDao.count()
            if (count > MemoryManager.MAX_MEMORY_COUNT) {
                LogManager.shared.log("WARN", TAG,
                    "Memory count $count still exceeds ${MemoryManager.MAX_MEMORY_COUNT} after cleanup")
            }

            LogManager.shared.log("INFO", TAG, "Memory maintenance complete: $count memories retained")
            Result.success()
        } catch (e: Exception) {
            LogManager.shared.log("ERROR", TAG, "Memory maintenance failed: ${e.message}")
            Result.retry()
        }
    }
}
