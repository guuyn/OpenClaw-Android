package ai.openclaw.android.trigger

import ai.openclaw.android.trigger.models.*
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import kotlin.collections.LinkedHashMap

/**
 * 执行动作的结果
 */
data class ActionResult(
    val success: Boolean,
    val result: String? = null,
    val error: String? = null
)

/**
 * EventBus — 事件总线 + 规则匹配 + 防抖去重
 *
 * 单例模式，全局唯一实例
 */
class EventBus private constructor(
    private val ruleDao: TriggerRuleDao,
    private val logDao: TriggerLogDao,
    private val actionExecutor: ActionExecutor
) {
    companion object {
        private const val TAG = "EventBus"

        @Volatile
        internal var instance: EventBus? = null

        fun getInstance(
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor
        ): EventBus = instance ?: synchronized(this) {
            instance ?: EventBus(ruleDao, logDao, actionExecutor).also { instance = it }
        }

        /**
         * 初始化 EventBus 单例（供 Application 或 CronWorker 等无 DI 场景使用）
         */
        fun initialize(
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor
        ) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = EventBus(ruleDao, logDao, actionExecutor)
                    }
                }
            }
        }

        fun reset() {
            instance = null
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 防抖: ruleId -> lastExecutedAt
    private val cooldowns = mutableMapOf<String, Long>()

    // 去重: LRU cache of dedupKey -> timestamp (max 100, TTL 5min)
    private val dedupCache = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 100
    }
    private val DEDUP_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * 发布事件到总线
     */
    suspend fun publish(event: TriggerEvent) {
        Log.d(TAG, "Event published: source=${event.source}, id=${event.id}")

        // 去重检查
        event.dedupKey?.let { key ->
            val now = System.currentTimeMillis()
            dedupCache[key]?.let { lastSeen ->
                if (now - lastSeen < DEDUP_TTL_MS) {
                    Log.d(TAG, "Event deduped: $key")
                    return
                }
            }
            dedupCache[key] = now
        }

        // 获取匹配的规则
        val matchedRules = getMatchingRules(event)
        Log.d(TAG, "Matched ${matchedRules.size} rules for event ${event.id}")

        for (rule in matchedRules) {
            executeRule(rule, event)
        }
    }

    /**
     * 获取匹配的规则
     */
    private suspend fun getMatchingRules(event: TriggerEvent): List<TriggerRule> {
        val rules = ruleDao.getEnabled()
        return rules.filter { rule ->
            rule.source == event.source && matchesFilters(rule, event)
        }
    }

    /**
     * 检查事件是否匹配规则的所有过滤器
     */
    private fun matchesFilters(rule: TriggerRule, event: TriggerEvent): Boolean {
        val filters = rule.getFilters()
        if (filters.isEmpty()) return true // 无过滤器 = 全部匹配

        return filters.all { filter -> filterMatches(filter, event) }
    }

    /**
     * 单个过滤器匹配
     */
    private fun filterMatches(filter: Filter, event: TriggerEvent): Boolean {
        return when (filter) {
            is Filter.PackageFilter -> {
                val pkg = event.payload["package"] as? String ?: return false
                filter.packages.any { pkg.contains(it, ignoreCase = true) }
            }

            is Filter.KeywordFilter -> {
                val text = (event.payload["title"] as? String ?: "") +
                        " " + (event.payload["text"] as? String ?: "")
                when (filter.mode) {
                    MatchMode.OR -> filter.keywords.any { text.contains(it, ignoreCase = true) }
                    MatchMode.AND -> filter.keywords.all { text.contains(it, ignoreCase = true) }
                    MatchMode.CONTAINS -> filter.keywords.any { text.contains(it, ignoreCase = true) }
                    MatchMode.EXACT -> filter.keywords.any { text.equals(it, ignoreCase = true) }
                }
            }

            is Filter.TimeFilter -> {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (filter.startHour <= filter.endHour) {
                    hour in filter.startHour..filter.endHour
                } else {
                    // 跨午夜: e.g., 22:00 - 07:00
                    hour >= filter.startHour || hour <= filter.endHour
                }
            }

            is Filter.CategoryFilter -> {
                val category = event.payload["category"] as? String ?: return false
                category == filter.category
            }
        }
    }

    /**
     * 执行规则（含防抖检查）
     */
    private suspend fun executeRule(rule: TriggerRule, event: TriggerEvent) {
        // 防抖检查
        val now = System.currentTimeMillis()
        cooldowns[rule.id]?.let { lastExec ->
            if (now - lastExec < rule.cooldownMs) {
                Log.d(TAG, "Rule ${rule.id} in cooldown, skipping")
                return
            }
        }

        Log.i(TAG, "Executing rule: ${rule.name} (${rule.id})")

        val action = rule.getAction() ?: run {
            Log.w(TAG, "Rule ${rule.id} has no valid action")
            return
        }

        val result = actionExecutor.execute(action, event)

        // 更新防抖
        cooldowns[rule.id] = now

        // 记录日志
        val log = TriggerLog(
            ruleId = rule.id,
            eventId = event.id,
            actionType = action::class.simpleName ?: "unknown",
            success = result.success,
            error = result.error,
            result = result.result
        )
        logDao.insert(log)

        Log.i(TAG, "Rule ${rule.id} executed: success=${result.success}")
    }

    /**
     * 手动触发规则（用于测试）
     */
    suspend fun triggerRuleManually(ruleId: String): TriggerLog {
        val rule = ruleDao.getById(ruleId)
            ?: throw IllegalArgumentException("Rule not found: $ruleId")

        val event = TriggerEvent(
            source = EventSource.USER_ACTION,
            payload = mapOf("manual" to true, "ruleId" to ruleId)
        )

        executeRule(rule, event)
        return logDao.getRecent(1).first()
    }

    /**
     * 清理过期的防抖记录
     */
    fun cleanupCooldowns() {
        val now = System.currentTimeMillis()
        cooldowns.entries.removeAll { (ruleId, lastExec) ->
            val rule = runBlocking { ruleDao.getById(ruleId) }
            rule == null || now - lastExec > (rule.cooldownMs * 2)
        }
    }

    /**
     * 清理过期的日志
     */
    suspend fun cleanupLogs() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        logDao.deleteOlderThan(thirtyDaysAgo)
        Log.i(TAG, "Cleaned up logs older than 30 days")
    }
}
