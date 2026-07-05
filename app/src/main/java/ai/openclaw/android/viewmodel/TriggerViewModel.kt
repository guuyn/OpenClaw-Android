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
import ai.openclaw.android.trigger.v2.toV1Rule
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
 *
 * 设计变更 (2026-07-05)：
 * - 新增可选参数 `triggerConfigManager` 和 `triggerEngine`，仅在调用方传参时启用。
 *   保留 3-参构造以保持向后兼容（TriggerScreen 现有调用点无需修改）。
 * - 当传入 configManager 时，loadRules() 会同时读取 v1 dao 与 v2 configManager 并
 *   按 id 去重合并（v1 dao 是唯一写入路径）。
 * - 当传入 engine 时，addRule/updateRule/deleteRule 优先走 engine.addRule 等方法；
 *   否则直接走 dao。
 */
class TriggerViewModel(
    database: AppDatabase,
    agentSessionFactory: suspend () -> AgentSession?,
    cronScheduler: CronScheduler,
    triggerConfigManager: TriggerConfigManager? = null,
    triggerEngine: TriggerEngine? = null
) : ViewModel() {

    companion object {
        private const val TAG = "TriggerViewModel"
    }

    // ==================== DAO ====================

    private val ruleDao: TriggerRuleDao = database.triggerRuleDao()
    private val logDao: TriggerLogDao = database.triggerLogDao()

    // ==================== 注入依赖 ====================

    private val configManager: TriggerConfigManager? = triggerConfigManager

    // 注：agentSessionFactory / cronScheduler 为预留给未来使用的注入点；当前 ViewModel
    // 主要走 dao / configManager / engine 路径，避免将他们存为私有字段造成 API 膨胀。
    @Suppress("UNUSED_PARAMETER")
    private val _unusedAgentSessionFactory: Any? = agentSessionFactory
    @Suppress("UNUSED_PARAMETER")
    private val _unusedCronScheduler: Any? = cronScheduler

    // ==================== State ====================

    /** Trigger 配置列表 (v1 rules + v2 configs，合并后按 id 去重) */
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

    private var triggerEngine: TriggerEngine? = triggerEngine
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
            // 读取 v1 dao（唯一写入路径）
            val daoRules = ruleDao.getAll()

            // 如果传入了 configManager，同时读取 v2 configManager。
            // 设计上 v2 configManager 在 fix 之后也是读取同一个 dao，所以这两路返回相同的
            // TriggerConfig 集合；dedup 仍然保留是为了在混合配置期间保持幂等。
            val configs = if (configManager != null) {
                val daoConfigs = daoRules.map { TriggerConfig.fromRule(it) }
                val managerConfigs = configManager.loadAll()
                (daoConfigs + managerConfigs)
                    .distinctBy { it.id }
                    .sortedByDescending { it.name }
            } else {
                daoRules.map { TriggerConfig.fromRule(it) }
            }

            _rules.value = daoRules
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
            // 优先走 engine（如可用）；否则直接走 configManager → dao。
            // 两者在 fix 之后都写入同一个 dao，不会产生重复（OnConflictStrategy.REPLACE）。
            if (triggerEngine != null) {
                triggerEngine?.addRule(rule)
            } else if (configManager != null) {
                configManager.save(TriggerConfig.fromRule(rule))
                loadRules()
            } else {
                ruleDao.insert(rule)
                loadRules()
            }
            _toastMessage.emit("已添加触发器: ${rule.name}")
        }
    }

    fun updateRule(rule: TriggerRule) {
        viewModelScope.launch {
            if (triggerEngine != null) {
                triggerEngine?.updateRule(rule)
            } else if (configManager != null) {
                configManager.save(TriggerConfig.fromRule(rule))
                loadRules()
            } else {
                ruleDao.insert(rule)
                loadRules()
            }
            _toastMessage.emit("已更新触发器: ${rule.name}")
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            if (triggerEngine != null) {
                triggerEngine?.deleteRule(ruleId)
            } else if (configManager != null) {
                configManager.delete(ruleId)
                loadRules()
            } else {
                ruleDao.deleteById(ruleId)
                loadRules()
            }
            _toastMessage.emit("已删除触发器")
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            if (triggerEngine != null) {
                triggerEngine?.toggleRule(ruleId, enabled)
            } else {
                ruleDao.setEnabled(ruleId, enabled)
                loadRules()
            }
        }
    }

    fun addPresetTemplate(template: TriggerConfig) {
        viewModelScope.launch {
            // v2 TriggerConfig 统一走 toV1Rule() 转换后走 addRule 路径，
            // v1 dao 的 OnConflictStrategy.REPLACE 保证幂等。
            addRule(template.toV1Rule())
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

// 注意：v2 Config → v1 Rule 的转换函数已迁移至 trigger.v2.TriggerConfig（顶层 dispatch 函数
// TriggerConfig.toV1Rule() 与各子类型的 receiver-specific 扩展）。本文件通过
// `import ai.openclaw.android.trigger.v2.toV1Rule` 自动可见，不再在此重复定义。
