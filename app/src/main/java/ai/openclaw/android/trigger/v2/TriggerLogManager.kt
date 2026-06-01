package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.v2.models.TriggerEventEntity
import ai.openclaw.android.trigger.v2.dao.TriggerEventDao
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TriggerLogManager — v2 日志管理
 *
 * 功能：
 * - 记录触发事件到 Room 数据库
 * - 日志查询（按时间/触发器/决策结果）
 * - 自动清理 30 天前旧日志
 */
class TriggerLogManager(
    private val eventDao: TriggerEventDao
) {
    companion object {
        private const val TAG = "TriggerLogManager"
        private const val LOG_RETENTION_DAYS = 30L
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * 记录一次触发事件
     */
    suspend fun logEvent(
        triggerId: String,
        context: Map<String, Any> = emptyMap(),
        decision: TriggerDecision = TriggerDecision.UNKNOWN,
        userFeedback: UserFeedback = UserFeedback.NONE,
        success: Boolean = true,
        result: String? = null,
        error: String? = null
    ) {
        val contextJson = context.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ", "
        ) { "\"${it.key}\":\"${it.value}\"" }
        val entity = TriggerEventEntity(
            triggerId = triggerId,
            context = if (context.isEmpty()) "{}" else contextJson,
            decision = decision.name.lowercase(),
            userFeedback = userFeedback.name.lowercase(),
            success = success,
            result = result,
            error = error
        )
        eventDao.insert(entity)
        Log.d(TAG, "Logged trigger event: triggerId=$triggerId, decision=${decision.name}")
    }

    /**
     * 查询最近的日志
     */
    suspend fun getRecentLogs(limit: Int = 50): List<TriggerEventEntity> {
        return eventDao.getRecent(limit)
    }

    /**
     * 按触发器 ID 查询日志
     */
    suspend fun getLogsByTriggerId(triggerId: String, limit: Int = 20): List<TriggerEventEntity> {
        return eventDao.getByTriggerId(triggerId, limit)
    }

    /**
     * 按决策结果查询日志
     */
    suspend fun getLogsByDecision(decision: TriggerDecision, limit: Int = 20): List<TriggerEventEntity> {
        return eventDao.getByDecision(decision.name.lowercase(), limit)
    }

    /**
     * 按时间范围查询日志
     */
    suspend fun getLogsByTimeRange(
        startTimeMs: Long,
        endTimeMs: Long
    ): List<TriggerEventEntity> {
        return eventDao.getByTimeRange(startTimeMs, endTimeMs)
    }

    /**
     * 获取触发器的触发次数
     */
    suspend fun getTriggerCount(triggerId: String): Int {
        return eventDao.countByTriggerId(triggerId)
    }

    /**
     * 获取某决策结果的总数
     */
    suspend fun getDecisionCount(decision: TriggerDecision): Int {
        return eventDao.countByDecision(decision.name.lowercase())
    }

    /**
     * 更新用户反馈
     */
    suspend fun updateUserFeedback(eventId: String, feedback: UserFeedback) {
        eventDao.updateUserFeedback(eventId, feedback.name.lowercase())
        Log.i(TAG, "Updated user feedback for event $eventId: ${feedback.name}")
    }

    /**
     * 清理 30 天前的旧日志
     */
    suspend fun cleanupOldLogs() {
        val cutoffMs = System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000)
        val deleted = eventDao.deleteOlderThan(cutoffMs)
        Log.i(TAG, "Cleaned up logs older than $LOG_RETENTION_DAYS days (before $cutoffMs)")
    }

    /**
     * 删除指定触发器的所有日志
     */
    suspend fun deleteLogsByTriggerId(triggerId: String) {
        eventDao.deleteByTriggerId(triggerId)
    }
}

// ==================== 枚举和辅助类 ====================

/**
 * 触发决策结果
 */
enum class TriggerDecision {
    TRIGGERED,      // 执行触发
    FILTERED,       // AI 过滤掉
    ERROR,          // 执行出错
    UNKNOWN         // 未知
}

/**
 * 用户反馈类型
 */
enum class UserFeedback {
    NONE,               // 无反馈
    CORRECT_TRIGGER,    // 正确触发
    CORRECT_SKIP,       // 正确跳过
    SHOULD_HAVE_TRIGGERED,  // 应该触发但没触发
    SHOULD_NOT_TRIGGER    // 不应该触发但触发了
}
