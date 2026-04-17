package ai.openclaw.android.trigger.scheduler

import ai.openclaw.android.trigger.EventBus
import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerEvent
import ai.openclaw.android.trigger.models.TriggerRule
import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * CronScheduler — 基于 WorkManager 的定时任务调度器
 *
 * 支持 cron 表达式 → 转换为 PeriodicWorkRequest
 * 每个 CRON 类型的规则对应一个唯一的 WorkManager 任务
 */
class CronScheduler(
    private val context: Context,
    private val eventBus: EventBus
) {
    companion object {
        private const val TAG = "CronScheduler"
        private const val WORK_PREFIX = "cron_trigger_"
    }

    private val workManager = WorkManager.getInstance(context)

    /**
     * 为规则创建定时任务
     */
    fun scheduleCronTask(rule: TriggerRule) {
        if (rule.source != EventSource.CRON || rule.scheduleCron == null) {
            Log.w(TAG, "Rule ${rule.id} is not a CRON rule or has no cron expression")
            return
        }

        val intervalMs = parseCronToIntervalMs(rule.scheduleCron)
        if (intervalMs < 15 * 60 * 1000L) { // WorkManager 最小间隔 15 分钟
            Log.w(TAG, "Cron interval too short for ${rule.id}, minimum is 15 minutes")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<CronWorker>(
            intervalMs,
            TimeUnit.MILLISECONDS
        )
            .setConstraints(constraints)
            .setInputData(workDataOf("ruleId" to rule.id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            getWorkName(rule.id),
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        Log.i(TAG, "Scheduled cron task for ${rule.name} (${rule.id}), interval=${intervalMs}ms")
    }

    /**
     * 取消规则的定时任务
     */
    fun cancelCronTask(ruleId: String) {
        workManager.cancelUniqueWork(getWorkName(ruleId))
        Log.i(TAG, "Cancelled cron task for $ruleId")
    }

    /**
     * 调度所有 CRON 类型的规则
     */
    suspend fun scheduleAllCronRules(rules: List<TriggerRule>) {
        val cronRules = rules.filter { it.source == EventSource.CRON && it.scheduleCron != null }
        Log.i(TAG, "Scheduling ${cronRules.size} cron rules")

        for (rule in cronRules) {
            scheduleCronTask(rule)
        }
    }

    /**
     * 取消所有定时任务（用于清理）
     */
    fun cancelAll() {
        workManager.cancelAllWork()
        Log.i(TAG, "Cancelled all cron tasks")
    }

    // ==================== Cron Parser ====================

    /**
     * 解析 cron 表达式为毫秒间隔
     *
     * 支持格式:
     * - "0/30 * * * *" → 30 分钟
     * - "0 * * * *" → 1 小时
     * - "0 0 * * *" → 24 小时
     * - "0 9 * * 1" → 每周一次
     *
     * 简化实现: 只支持 *\/N 模式（每 N 分钟/小时）
     */
    fun parseCronToIntervalMs(cronExpr: String): Long {
        val parts = cronExpr.trim().split(Regex("\\s+"))
        if (parts.size < 5) {
            Log.w(TAG, "Invalid cron expression: $cronExpr")
            return 60 * 60 * 1000L // 默认 1 小时
        }

        val minute = parts[0]
        val hour = parts[1]

        // */N 分钟模式
        if (minute.startsWith("*/")) {
            val n = minute.substring(2).toLongOrNull() ?: return 60 * 60 * 1000L
            return n * 60 * 1000L
        }

        // 0 * * * * → 每小时
        if (minute == "0" && hour == "*") {
            return 60 * 60 * 1000L
        }

        // 0 0 * * * → 每天
        if (minute == "0" && hour == "0") {
            return 24 * 60 * 60 * 1000L
        }

        // N * * * * → 每 N 小时
        if (hour == "*") {
            val n = minute.toLongOrNull()
            if (n != null && n > 0) {
                return n * 60 * 1000L
            }
            return 60 * 60 * 1000L
        }

        // 0 H * * * → 每 24 小时，在 H 点触发
        val h = hour.toIntOrNull()
        if (h != null) {
            return 24 * 60 * 60 * 1000L
        }

        // 默认 1 小时
        return 60 * 60 * 1000L
    }

    private fun getWorkName(ruleId: String): String = "$WORK_PREFIX$ruleId"
}

/**
 * CronWorker — WorkManager Worker，触发 CRON 事件
 *
 * 通过 EventBus 单例直接获取实例，不依赖依赖注入
 */
class CronWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CronWorker"
    }

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString("ruleId") ?: return Result.failure()

        Log.i(TAG, "Cron triggered for rule: $ruleId")

        val eventBus = EventBus.instance ?: run {
            Log.e(TAG, "EventBus not initialized — call EventBus.initialize() first")
            return Result.failure()
        }

        val event = TriggerEvent(
            source = EventSource.CRON,
            payload = mapOf("ruleId" to ruleId, "cronTrigger" to true)
        )

        try {
            eventBus.publish(event)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish cron event: ${e.message}", e)
            return Result.retry()
        }
    }
}
