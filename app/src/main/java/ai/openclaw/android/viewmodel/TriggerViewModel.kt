package ai.openclaw.android.viewmodel

import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import ai.openclaw.android.trigger.v2.TriggerEngine
import ai.openclaw.android.trigger.v2.AITriggerDecision
import ai.openclaw.android.trigger.v2.TriggerEngineEvent
import ai.openclaw.android.trigger.v2.TriggerConfigManager
import ai.openclaw.android.trigger.v2.TriggerConfig
import ai.openclaw.android.trigger.v2.TriggerLogManager
import ai.openclaw.android.trigger.v2.TriggerDecision
import ai.openclaw.android.trigger.v2.UserFeedback
import ai.openclaw.android.trigger.v2.TriggerTemplates
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.trigger.scheduler.CronScheduler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * TriggerViewModel — Trigger v2 管理界面 ViewModel
 *
 * 管理 Trigger 列表、CRUD 操作、日志查询、AI 决策统计
 */
class TriggerViewModel(
    database: AppDatabase,
    agentSessionFactory: suspend () -> AgentSession?,
    cronScheduler: CronScheduler
) : ViewModel() {

    companion object {
        private const val TAG = "TriggerViewModel"
    }

    // ==================== DAO ====================

    private val ruleDao: TriggerRuleDao = database.triggerRuleDao()
    private val logDao: TriggerLogDao = database.triggerLogDao()

    // ==================== State ====================

    /** Trigger 配置列表 (v1 rules + v2 configs) */
    private val _triggerConfigs = MutableStateFlow<List<TriggerConfig>>(emptyList())
    val triggerConfigs: StateFlow<List<TriggerConfig>> = _triggerConfigs.asStateFlow()

    /** v1 原始规则列表 */
    private val _rules = MutableStateFlow<List<TriggerRule>>(emptyList())
    val rules: StateFlow<List<TriggerRule>> = _rules.asStateFlow()

    /** 最近日志 */
    private val _recentLogs = MutableStateFlow<List<ai.openclaw.android.trigger.models.TriggerLog>>(emptyList())
    val recentLogs: StateFlow<List<ai.openclaw.android.trigger.models.TriggerLog>> = _recentLogs.asStateFlow()

    /** AI 决策统计 */
    private val _decisionStats = MutableStateFlow(ai.openclaw.android.trigger.v2.DecisionStats())
    val decisionStats: StateFlow<ai.openclaw.android.trigger.v2.DecisionStats> = _decisionStats.asStateFlow()

    /** 引擎运行状态 */
    private val _engineRunning = MutableStateFlow(false)
    val engineRunning: StateFlow<Boolean> = _engineRunning.asStateFlow()

    /** UI: 是否显示新增对话框 */
    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    /** 当前选中的 Trigger ID（用于编辑） */
    private val _selectedTriggerId = MutableStateFlow<String?>(null)
    val selectedTriggerId: StateFlow<String?> = _selectedTriggerId.asStateFlow()

    /** Toast 消息 */
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // ==================== 引用（延迟初始化） ====================

    private var triggerEngine: TriggerEngine? = null
    private var aiDecision: AITriggerDecision? = null
    private var logManager: TriggerLogManager? = null

    // ==================== 初始化 ====================

    fun initEngine(
        engine: TriggerEngine,
        decision: AITriggerDecision,
        logMgr: TriggerLogManager
    ) {
        triggerEngine = engine
        aiDecision = decision
        logManager = logMgr

        // 观察引擎事件
        viewModelScope.launch {
            engine.triggerEvents.collect { event ->
                when (event) {
                    is TriggerEngineEvent.EngineStarted -> _engineRunning.value = true
                    is TriggerEngineEvent.EngineStopped -> _engineRunning.value = false
                    is TriggerEngineEvent.RulesLoaded -> loadRules()
                    is TriggerEngineEvent.RuleAdded,
                    is TriggerEngineEvent.RuleUpdated,
                    is TriggerEngineEvent.RuleDeleted,
                    is TriggerEngineEvent.RuleToggled -> loadRules()
                    else -> {}
                }
            }
        }

        // 观察 AI 决策统计
        viewModelScope.launch {
            decision.decisionStats.collect { stats ->
                _decisionStats.value = stats
            }
        }

        loadRules()
        loadRecentLogs()
    }

    // ==================== 规则操作 ====================

    fun loadRules() {
        viewModelScope.launch {
            val allRules = ruleDao.getAll()
            _rules.value = allRules

            // 转换为 v2 Config
            val configs = allRules.map { rule ->
                TriggerConfig.fromRule(rule)
            }
            _triggerConfigs.value = configs
        }
    }

    fun loadRecentLogs(limit: Int = 50) {
        viewModelScope.launch {
            _recentLogs.value = logDao.getRecent(limit)
        }
    }

    fun addRule(rule: TriggerRule) {
        viewModelScope.launch {
            triggerEngine?.addRule(rule)
            _toastMessage.emit("已添加触发器: ${rule.name}")
        }
    }

    fun updateRule(rule: TriggerRule) {
        viewModelScope.launch {
            triggerEngine?.updateRule(rule)
            _toastMessage.emit("已更新触发器: ${rule.name}")
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            triggerEngine?.deleteRule(ruleId)
            _toastMessage.emit("已删除触发器")
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            triggerEngine?.toggleRule(ruleId, enabled)
        }
    }

    fun addPresetTemplate(template: TriggerConfig) {
        viewModelScope.launch {
            when (template) {
                is ai.openclaw.android.trigger.v2.TimeTrigger -> {
                    addRule(template.toV1Rule())
                }
                is ai.openclaw.android.trigger.v2.NotificationPatternTrigger -> {
                    addRule(template.toV1Rule())
                }
                is ai.openclaw.android.trigger.v2.DeviceStateTrigger -> {
                    addRule(template.toV1Rule())
                }
                else -> {}
            }
        }
    }

    // ==================== 用户反馈 ====================

    fun onUserFeedback(eventId: String, feedback: UserFeedback) {
        viewModelScope.launch {
            logManager?.updateUserFeedback(eventId, feedback)
            _toastMessage.emit("反馈已记录: ${feedback.name}")
        }
    }

    // ==================== UI 状态 ====================

    fun setShowAddDialog(show: Boolean) {
        _showAddDialog.value = show
    }

    fun setSelectedTriggerId(id: String?) {
        _selectedTriggerId.value = id
    }

    // ==================== 预设模板 ====================

    fun getPresetTemplates(): List<TriggerConfig> {
        return TriggerTemplates.getAllTemplates()
    }
}

// ==================== v2 Config → v1 Rule 转换 ====================

fun ai.openclaw.android.trigger.v2.TimeTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = ai.openclaw.android.trigger.models.EventSource.CRON,
        scheduleCron = cronExpression,
        actionJson = ai.openclaw.android.trigger.models.TriggerRule.serializeAction(
            when (action) {
                is ai.openclaw.android.trigger.v2.SkillCallActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.SkillCall(
                        action.skillId, action.toolName, action.paramsJson
                    )
                is ai.openclaw.android.trigger.v2.AgentQueryActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.prompt, action.model)
                is ai.openclaw.android.trigger.v2.NotificationReplyActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.NotificationReply(action.template, action.autoReply)
                is ai.openclaw.android.trigger.v2.CustomScriptActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.CustomScript(action.script)
                is ai.openclaw.android.trigger.v2.NotificationActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.message)
            }
        ),
        filtersJson = "[]"
    )
}

fun ai.openclaw.android.trigger.v2.NotificationPatternTrigger.toV1Rule(): TriggerRule {
    val filters = mutableListOf<ai.openclaw.android.trigger.models.Filter>()
    if (packageName.isNotBlank()) {
        filters.add(ai.openclaw.android.trigger.models.Filter.PackageFilter(listOf(packageName)))
    }
    if (keywords.isNotEmpty()) {
        val mode = when (matchMode) {
            "AND" -> ai.openclaw.android.trigger.models.MatchMode.AND
            "EXACT" -> ai.openclaw.android.trigger.models.MatchMode.EXACT
            else -> ai.openclaw.android.trigger.models.MatchMode.OR
        }
        filters.add(ai.openclaw.android.trigger.models.Filter.KeywordFilter(keywords, mode))
    }
    timeRange?.let { (start, end) ->
        filters.add(ai.openclaw.android.trigger.models.Filter.TimeFilter(start, end))
    }

    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = ai.openclaw.android.trigger.models.EventSource.NOTIFICATION,
        filtersJson = ai.openclaw.android.trigger.models.TriggerRule.serializeFilters(filters),
        actionJson = ai.openclaw.android.trigger.models.TriggerRule.serializeAction(
            when (action) {
                is ai.openclaw.android.trigger.v2.SkillCallActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.SkillCall(
                        action.skillId, action.toolName, action.paramsJson
                    )
                is ai.openclaw.android.trigger.v2.AgentQueryActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.prompt, action.model)
                is ai.openclaw.android.trigger.v2.NotificationReplyActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.NotificationReply(action.template, action.autoReply)
                is ai.openclaw.android.trigger.v2.CustomScriptActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.CustomScript(action.script)
                is ai.openclaw.android.trigger.v2.NotificationActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

fun ai.openclaw.android.trigger.v2.DeviceStateTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = ai.openclaw.android.trigger.models.EventSource.SYSTEM_BROADCAST,
        filtersJson = "[]",
        actionJson = ai.openclaw.android.trigger.models.TriggerRule.serializeAction(
            when (action) {
                is ai.openclaw.android.trigger.v2.SkillCallActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.SkillCall(
                        action.skillId, action.toolName, action.paramsJson
                    )
                is ai.openclaw.android.trigger.v2.AgentQueryActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.prompt, action.model)
                is ai.openclaw.android.trigger.v2.NotificationReplyActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.NotificationReply(action.template, action.autoReply)
                is ai.openclaw.android.trigger.v2.CustomScriptActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.CustomScript(action.script)
                is ai.openclaw.android.trigger.v2.NotificationActionDef ->
                    ai.openclaw.android.trigger.models.TriggerAction.AgentQuery(action.message)
            }
        )
    )
}
