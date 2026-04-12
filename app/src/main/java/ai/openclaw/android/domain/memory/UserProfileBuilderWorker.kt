package ai.openclaw.android.domain.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.model.MemoryType
import org.koin.mp.KoinPlatform

/**
 * Periodic WorkManager task that builds a user profile from accumulated memories.
 *
 * Scans stored memories and derives:
 * - Top preferences (PREFERENCE type, sorted by access count)
 * - Recurring topics (tags frequency)
 * - Activity patterns (time-of-day distribution)
 *
 * Results are stored as high-priority FACT memories tagged "user_profile".
 */
class UserProfileBuilderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UserProfileBuilder"
    }

    override suspend fun doWork(): Result {
        LogManager.shared.log("INFO", TAG, "Building user profile...")
        return try {
            val db = KoinPlatform.getKoin().get<AppDatabase>()
            val memoryDao = db.memoryDao()

            val allMemories = memoryDao.getAll()
            if (allMemories.size < 5) {
                LogManager.shared.log("INFO", TAG, "Not enough memories (${allMemories.size}) to build profile")
                return Result.success()
            }

            // 1. Top preferences
            val preferences = allMemories
                .filter { it.memoryType == MemoryType.PREFERENCE }
                .sortedByDescending { it.accessCount }
                .take(10)
                .map { it.content }

            // 2. Tag frequency map
            val tagCounts = mutableMapOf<String, Int>()
            for (memory in allMemories) {
                val tags: List<String> = memory.tags
                for (tag in tags) {
                    tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                }
            }
            val topTags = tagCounts.entries
                .sortedByDescending { entry -> entry.value }
                .take(10)
                .map { entry -> "${entry.key}(${entry.value})" }

            // 3. Type distribution
            val typeDistribution = allMemories.groupBy { mem -> mem.memoryType }
                .entries.map { entry -> "${entry.key}:${entry.value.size}" }

            // Build profile summary
            val profileContent = buildString {
                appendLine("用户画像摘要:")
                if (preferences.isNotEmpty()) {
                    appendLine("偏好: ${preferences.joinToString(", ")}")
                }
                if (topTags.isNotEmpty()) {
                    appendLine("关注领域: ${topTags.joinToString(", ")}")
                }
                appendLine("记忆分布: ${typeDistribution.joinToString(", ")}")
                append("总计: ${allMemories.size} 条记忆")
            }

            // Store as a high-priority profile memory
            val profileMemory = ai.openclaw.android.data.model.MemoryEntity(
                content = profileContent,
                memoryType = MemoryType.FACT,
                priority = 5,
                source = "profile_builder",
                tags = listOf("user_profile", "auto"),
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )
            memoryDao.insert(profileMemory)

            LogManager.shared.log("INFO", TAG, "User profile built: ${allMemories.size} memories analyzed")
            Result.success()
        } catch (e: Exception) {
            LogManager.shared.log("ERROR", TAG, "Profile build failed: ${e.message}")
            Result.retry()
        }
    }
}
