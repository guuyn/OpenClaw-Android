package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerEvent
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.TriggerLog
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import ai.openclaw.android.trigger.ActionExecutor
import ai.openclaw.android.trigger.EventBus
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.ConfigManager
import ai.openclaw.android.trigger.scheduler.CronScheduler
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.Notification
import android.app.NotificationManager
import android.os.PowerManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import kotlin.collections.LinkedHashMap

/**
 * TriggerEngine v2 — 核心触发引擎
 *
 * 事件驱动架构：
 * - 监听通知变化、时间事件、设备状态变化
 * - 条件评估引擎（基于规则 + AI 决策）
 * - 使用 Flow 事件总线通知各组件
 * - WorkManager 调度定时触发
 *
 * 与 v1 EventBus 共存，v2 提供更智能的 AI 决策层
 */
class TriggerEngine(
    private val context: Context,
    private val ruleDao: TriggerRuleDao,
    private val logDao: TriggerLogDao,
    private val actionExecutor: ActionExecutor,
    private val aiDecision: AITriggerDecision,
    private val cronScheduler: CronScheduler,
    private val agentSessionFactory: suspend () -> AgentSession?
) {
    companion object {
        private const val TAG = "TriggerEngine"

        @Volatile
        internal var instance: TriggerEngine? = null

        fun getInstance(
            context: Context,
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor,
            aiDecision: AITriggerDecision,
            cronScheduler: CronScheduler,
            agentSessionFactory: suspend () -> AgentSession?
        ): TriggerEngine = instance ?: synchronized(this) {
            instance ?: TriggerEngine(
                context, ruleDao, logDao, actionExecutor,
                aiDecision, cronScheduler, agentSessionFactory
            ).also { instance = it }
        }

        fun reset() { instance = null }
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== 事件总线 (Flow) ====================

    /** v2 内部事件流，供 UI 和其他组件观察 */
    private val _triggerEvents = MutableSharedFlow<TriggerEngineEvent>(extraBufferCapacity = 64)
    val triggerEvents: SharedFlow<TriggerEngineEvent> = _triggerEvents.asSharedFlow()

    /** 触发器列表变更流 */
    private val _triggerConfigs = MutableStateFlow<List<TriggerConfig>>(emptyList())
    val triggerConfigs: StateFlow<List<TriggerConfig>> = _triggerConfigs.asStateFlow()

    // ==================== 运行时状态 ====================

    private val cooldowns = mutableMapOf<String, Long>()
    private val dedupCache = object : LinkedHashMap<String, Long>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 200
    }
    private val DEDUP_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                engineScope.launch {
                    val event = when (action) {
                        Intent.ACTION_BATTERY_LOW -> TriggerEvent(
                            source = EventSource.SYSTEM_BROADCAST,
                            payload = mapOf("deviceState" to "battery_low")
                        )
                        Intent.ACTION_POWER_CONNECTED -> TriggerEvent(
                            source = EventSource.SYSTEM_BROADCAST,
                            payload = mapOf("deviceState" to "charging")
                        )
                        Intent.ACTION_SCREEN_ON -> TriggerEvent(
                            source = EventSource.SYSTEM_BROADCAST,
                            payload = mapOf("deviceState" to "screen_on")
                        )
                        Intent.ACTION_SCREEN_OFF -> TriggerEvent(
                            source = EventSource.SYSTEM_BROADCAST,
                            payload = mapOf("deviceState" to "screen_off")
                        )
                        else -> return@launch
                    }
                    handleEvent(event)
                }
            }
        }
    }

    // ==================== 生命周期 ====================

    fun start() {
        Log.i(TAG, "TriggerEngine v2 starting")

        // 注册系统广播接收器
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            context.registerReceiver(deviceStateReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register device state receiver: ${e.message}")
        }

        // 加载配置并调度 CRON 任务
        engineScope.launch {
            loadAndScheduleRules()
        }

        // 启动日志清理
        engineScope.launch {
            startLogCleanup()
        }

        _triggerEvents.tryEmit(TriggerEngineEvent.EngineStarted)
    }

    fun stop() {
        Log.i(TAG, "TriggerEngine v2 stopping")
        try {
            context.unregisterReceiver(deviceStateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        cronScheduler.cancelAll()
        engineScope.cancel()
        _triggerEvents.tryEmit(TriggerEngineEvent.EngineStopped)
    }

    // ==================== 事件处理 ====================

    /**
     * 处理传入的触发事件
     */
    suspend fun handleEvent(event: TriggerEvent) {
        Log.d(TAG, "Handling event: source=${event.source}, id=${event.id}")

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

        _triggerEvents.tryEmit(TriggerEngineEvent.EventReceived(event))

        val matchedRules = getMatchingRules(event)
        Log.d(TAG, "Matched ${matchedRules.size} rules for event ${event.id}")

        for (rule in matchedRules) {
            evaluateAndExecute(rule, event)
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
        if (filters.isEmpty()) return true
        return filters.all { filter -> filterMatches(filter, event) }
    }

    private fun filterMatches(filter: ai.openclaw.android.trigger.models.Filter, event: TriggerEvent): Boolean {
        return when (filter) {
            is ai.openclaw.android.trigger.models.Filter.PackageFilter -> {
                val pkg = event.payload["package"] as? String ?: return false
                filter.packages.any { pkg.contains(it, ignoreCase = true) }
            }
            is ai.openclaw.android.trigger.models.Filter.KeywordFilter -> {
                val text = (event.payload["title"] as? String ?: "") +
                        " " + (event.payload["text"] as? String ?: "")
                filter.keywords.any { text.contains(it, ignoreCase = true) }
            }
            is ai.openclaw.android.trigger.models.Filter.TimeFilter -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (filter.startHour <= filter.endHour) {
                    hour in filter.startHour..filter.endHour
                } else {
                    hour >= filter.startHour || hour <= filter.endHour
                }
            }
            is ai.openclaw.android.trigger.models.Filter.CategoryFilter -> {
                val category = event.payload["category"] as? String ?: return false
                category == filter.category
            }
        }
    }

    /**
     * 评估并执行规则（包含 AI 决策层）
     */
    private suspend fun evaluateAndExecute(rule: TriggerRule, event: TriggerEvent) {
        // 防抖检查
        val now = System.currentTimeMillis()
        cooldowns[rule.id]?.let { lastExec ->
            if (now - lastExec < rule.cooldownMs) {
                Log.d(TAG, "Rule ${rule.id} in cooldown, skipping")
                return
            }
        }

        // AI 决策：判断是否应该触发
        val shouldTrigger = aiDecision.shouldTrigger(rule, event)

        _triggerEvents.tryEmit(
            TriggerEngineEvent.RuleEvaluated(rule.id, rule.name, shouldTrigger, event)
        )

        if (!shouldTrigger) {
            Log.d(TAG, "AI decided not to trigger rule: ${rule.name}")
            recordLog(rule, event, "ai_filtered", success = false, result = "AI decision: skip")
            return
        }

        Log.i(TAG, "Executing rule: ${rule.name} (${rule.id})")

        val action = rule.getAction() ?: run {
            Log.w(TAG, "Rule ${rule.id} has no valid action")
            return
        }

        val result = actionExecutor.execute(action, event)

        cooldowns[rule.id] = now

        recordLog(rule, event, action::class.simpleName ?: "unknown", result.success, result.result)

        // 收集用户反馈
        aiDecision.onActionExecuted(rule, event, result.success)

        _triggerEvents.tryEmit(
            TriggerEngineEvent.RuleExecuted(rule.id, rule.name, result.success, result.error)
        )

        Log.i(TAG, "Rule ${rule.id} executed: success=${result.success}")
    }

    /**
     * 记录触发日志
     */
    private suspend fun recordLog(
        rule: TriggerRule,
        event: TriggerEvent,
        actionType: String,
        success: Boolean,
        result: String? = null
    ) {
        val log = TriggerLog(
            ruleId = rule.id,
            eventId = event.id,
            executedAt = System.currentTimeMillis(),
            actionType = actionType,
            success = success,
            result = result
        )
        logDao.insert(log)
    }

    // ==================== 规则管理 ====================

    /**
     * 加载并调度所有规则
     */
    suspend fun loadAndScheduleRules() {
        val rules = ruleDao.getAll()
        cronScheduler.scheduleAllCronRules(rules)

        // 更新配置流
        val configs = rules.map { rule ->
            TriggerConfig.fromRule(rule)
        }
        _triggerConfigs.value = configs

        _triggerEvents.tryEmit(TriggerEngineEvent.RulesLoaded(configs.size))
    }

    /**
     * 添加新规则
     */
    suspend fun addRule(rule: TriggerRule) {
        ruleDao.insert(rule)
        if (rule.source == EventSource.CRON && rule.scheduleCron != null) {
            cronScheduler.scheduleCronTask(rule)
        }
        loadAndScheduleRules()
        _triggerEvents.tryEmit(TriggerEngineEvent.RuleAdded(rule.id, rule.name))
    }

    /**
     * 更新规则
     */
    suspend fun updateRule(rule: TriggerRule) {
        ruleDao.insert(rule) // OnConflictStrategy.REPLACE
        if (rule.source == EventSource.CRON && rule.scheduleCron != null) {
            cronScheduler.scheduleCronTask(rule)
        }
        loadAndScheduleRules()
        _triggerEvents.tryEmit(TriggerEngineEvent.RuleUpdated(rule.id, rule.name))
    }

    /**
     * 删除规则
     */
    suspend fun deleteRule(ruleId: String) {
        val rule = ruleDao.getById(ruleId) ?: return
        ruleDao.delete(rule)
        if (rule.source == EventSource.CRON) {
            cronScheduler.cancelCronTask(ruleId)
        }
        loadAndScheduleRules()
        _triggerEvents.tryEmit(TriggerEngineEvent.RuleDeleted(ruleId))
    }

    /**
     * 切换规则启用/禁用
     */
    suspend fun toggleRule(ruleId: String, enabled: Boolean) {
        ruleDao.setEnabled(ruleId, enabled)
        if (!enabled && ruleId.isNotEmpty()) {
            // 取消已禁用的 CRON 任务
            cronScheduler.cancelCronTask(ruleId)
        }
        loadAndScheduleRules()
        _triggerEvents.tryEmit(TriggerEngineEvent.RuleToggled(ruleId, enabled))
    }

    // ==================== 设备状态 ====================

    /**
     * 获取当前设备状态（供 AI 决策使用）
     */
    fun getDeviceState(): Map<String, Any> {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return mapOf(
            "batteryLevel" to batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "isCharging" to (batteryManager.isCharging),
            "isPowerSaveMode" to powerManager.isPowerSaveMode,
            "isInteractive" to powerManager.isInteractive,
            "notificationAccessEnabled" to notificationManager.areNotificationsEnabled(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    // ==================== 日志清理 ====================

    private suspend fun startLogCleanup() {
        // 每天清理一次
        while (engineScope.isActive) {
            delay(24 * 60 * 60 * 1000)
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            logDao.deleteOlderThan(thirtyDaysAgo)
            Log.i(TAG, "Cleaned up logs older than 30 days")
        }
    }
}

/**
 * TriggerEngine 事件类型
 */
sealed class TriggerEngineEvent {
    data object EngineStarted : TriggerEngineEvent()
    data object EngineStopped : TriggerEngineEvent()
    data class EventReceived(val event: TriggerEvent) : TriggerEngineEvent()
    data class RulesLoaded(val count: Int) : TriggerEngineEvent()
    data class RuleAdded(val ruleId: String, val ruleName: String) : TriggerEngineEvent()
    data class RuleUpdated(val ruleId: String, val ruleName: String) : TriggerEngineEvent()
    data class RuleDeleted(val ruleId: String) : TriggerEngineEvent()
    data class RuleToggled(val ruleId: String, val enabled: Boolean) : TriggerEngineEvent()
    data class RuleEvaluated(
        val ruleId: String,
        val ruleName: String,
        val shouldTrigger: Boolean,
        val event: TriggerEvent
    ) : TriggerEngineEvent()
    data class RuleExecuted(
        val ruleId: String,
        val ruleName: String,
        val success: Boolean,
        val error: String? = null
    ) : TriggerEngineEvent()
}
