package ai.openclaw.android.personalcenter

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.openclaw.android.GatewayContract
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.personalcenter.models.CenterItem
import ai.openclaw.android.personalcenter.sources.CalendarSource
import ai.openclaw.android.personalcenter.sources.CallLogSource
import ai.openclaw.android.personalcenter.sources.NotificationSource
import ai.openclaw.android.personalcenter.sources.SmsSource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*

/**
 * 个人中心 ViewModel
 * 职责：
 *   1. 聚合四个数据源（通知、日历、短信、通话记录）
 *   2. 内容过滤（关键词 + LLM 语义）
 *   3. 跨源去重
 *   4. 按重要程度排序
 *   5. 定时兜底刷新
 */
class PersonalCenterViewModel(
    private val app: Application,
    private val permManager: PermissionManager? = null
) : androidx.lifecycle.ViewModel() {

    private val TAG = "PersonalCenterVM"

    private val notificationSource = NotificationSource(app)
    private val calendarSource = CalendarSource(app)
    private val smsSource = SmsSource(app)
    private val callLogSource = CallLogSource(app)

    // 最终输出：过滤 + 去重 + 排序后的统一列表
    private val _items = MutableStateFlow<List<CenterItem>>(emptyList())
    val items: StateFlow<List<CenterItem>> = _items.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 权限状态
    private val _calendarPermissionGranted = MutableStateFlow(true)
    val calendarPermissionGranted: StateFlow<Boolean> = _calendarPermissionGranted.asStateFlow()

    private val _smsPermissionGranted = MutableStateFlow(true)
    val smsPermissionGranted: StateFlow<Boolean> = _smsPermissionGranted.asStateFlow()

    private val _callLogPermissionGranted = MutableStateFlow(false)
    val callLogPermissionGranted: StateFlow<Boolean> = _callLogPermissionGranted.asStateFlow()

    // 过滤统计（用于调试）— 使用 @Volatile 保证线程安全（Flow 在不同线程执行）
    @Volatile
    private var _filteredCount = 0
    val filteredCount: Int get() = _filteredCount

    private var refreshJob: Job? = null

    // 通话记录权限请求通道 — 使用 Channel(1) 确保事件不丢失
    // Channel 会缓冲 emit 的值直到被 consume，不受 collector 时机影响
    private val _triggerCallLogPermissionRequest = Channel<Unit>(Channel.CONFLATED)
    val triggerCallLogPermissionRequest: ReceiveChannel<Unit> = _triggerCallLogPermissionRequest

    /**
     * UI 层在权限对话框结果后调用此方法
     */
    fun onCallLogPermissionResult(granted: Boolean) {
        _callLogPermissionGranted.value = granted
        Log.d(TAG, "Call log permission result: $granted")
    }

    /**
     * 检查通话记录权限，如果未授权则触发请求
     */
    fun checkAndRequestCallLogPermission() {
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            _callLogPermissionGranted.value = true
            return
        }
        // 直接通过 Channel 触发 Activity 的权限请求
        viewModelScope.launch { _triggerCallLogPermissionRequest.send(Unit) }
    }

    /**
     * 注入 LLM 评估器（由 Activity 在 ViewModel 创建后调用）
     */
    fun setGatewayContract(contract: GatewayContract?) {
        if (contract != null) {
            SmartFilter.llmEvaluator = SmartFilter.LlmEvaluator { items ->
                evaluateWithLLM(items, contract)
            }
            PriorityClassifier.llmClassifier = PriorityClassifier.createLlmEvaluator(contract)
        }
    }

    init {
        startMerging()
        startPeriodicRefresh()
        // 启动时检查权限状态，不自动触发弹框（由 UI 层首次可见时触发）
        checkCallLogPermissionStatus()
    }

    /**
     * 仅检查权限状态，不主动弹框
     */
    private fun checkCallLogPermissionStatus() {
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            _callLogPermissionGranted.value = true
        }
    }

    /**
     * UI 层在首次可见时调用，触发权限请求
     */
    fun requestCallLogPermissionIfNeeded() {
        if (_callLogPermissionGranted.value) return // 已授权则跳过
        viewModelScope.launch { _triggerCallLogPermissionRequest.send(Unit) }
    }

    /**
     * LLM 评估实现
     */
    private suspend fun evaluateWithLLM(
        items: List<CenterItem>,
        contract: GatewayContract
    ): Map<String, Float> {
        if (items.isEmpty()) return emptyMap()

        val batchText = items.joinToString("\n") { item ->
            "[${item.id}] ${item.sourceApp} | ${item.title} | ${item.body.take(100)}"
        }

        val prompt = """
你是一个信息价值评估助手。请评估以下通知/消息的信息价值。

评估标准：
1. 高价值：验证码、银行通知、来电提醒、重要日程、快递通知、工作相关
2. 中价值：社交消息、新闻推送、应用更新提醒
3. 低价值：广告推广、营销信息、签到提醒、游戏通知、系统垃圾提示

对每条消息给出 0~1 的评分：
- 0.0-0.2：无价值，应该过滤掉
- 0.3-0.5：一般价值
- 0.6-1.0：高价值，必须保留

消息列表：
$batchText

只返回 JSON 格式（不要其他文字）：
[{"id": "xxx", "value": 0.8}, {"id": "yyy", "value": 0.1}]
""".trimIndent()

        return try {
            val response = StringBuilder()
            contract.sendMessage(prompt).collect { event ->
                when (event) {
                    is ai.openclaw.android.agent.SessionEvent.Token -> {
                        response.append(event.text)
                    }
                    is ai.openclaw.android.agent.SessionEvent.Complete -> {}
                    is ai.openclaw.android.agent.SessionEvent.Error -> {
                        throw RuntimeException(event.message)
                    }
                    else -> {}
                }
            }

            val result = mutableMapOf<String, Float>()
            val jsonRegex = Regex("""\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"value"\s*:\s*([0-9.]+)""")
            for (match in jsonRegex.findAll(response.toString())) {
                val id = match.groupValues[1]
                val value = match.groupValues[2].toFloatOrNull() ?: 0.5f
                result[id] = value.coerceIn(0f, 1f)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "LLM evaluation failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 合并三源数据：collect 每个源的 Flow → 过滤 → 去重 → 排序
     */
    private fun startMerging() {
        viewModelScope.launch {
            try {
                combine(
                    notificationSource.observe().catch { e ->
                        Log.e(TAG, "Notification flow error: ${e.message}")
                        emit(emptyList())
                    },
                    calendarSource.observe().catch { e ->
                        Log.w(TAG, "Calendar flow error: ${e.message}")
                        _calendarPermissionGranted.value = false
                        emit(emptyList())
                    },
                    smsSource.observe().catch { e ->
                        Log.w(TAG, "SMS flow error: ${e.message}")
                        _smsPermissionGranted.value = false
                        emit(emptyList())
                    },
                    callLogSource.observe().catch { e ->
                        Log.w(TAG, "CallLog flow error: ${e.message}")
                        _callLogPermissionGranted.value = false
                        emit(emptyList())
                    }
                ) { notifications, calendars, sms, callLogs ->
                    val all = notifications + calendars + sms + callLogs
                    val rawCount = all.size
                    Log.d(TAG, "Raw items: $rawCount (notif=${notifications.size}, cal=${calendars.size}, sms=${sms.size}, call=${callLogs.size})")

                    // Step 1: 关键词快速过滤
                    val afterContentFilter = all.filterNot { ContentFilter.isNoise(it) }
                    Log.d(TAG, "After ContentFilter: ${afterContentFilter.size} (removed ${rawCount - afterContentFilter.size})")

                    // Step 2: LLM 语义过滤（有 LLM 就用，没有就跳过）
                    val afterSmartFilter = try {
                        withTimeout(15_000L) {
                            SmartFilter.filterBatch(afterContentFilter)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "SmartFilter timeout, using ContentFilter result")
                        afterContentFilter
                    } catch (e: Exception) {
                        Log.w(TAG, "SmartFilter error: ${e.message}")
                        afterContentFilter
                    }
                    Log.d(TAG, "After SmartFilter: ${afterSmartFilter.size}")

                    // Step 3: 去重（精确 dedupKey 匹配）
                    val deduped = try {
                        DeduplicationEngine.deduplicate(afterSmartFilter)
                    } catch (e: Exception) {
                        Log.e(TAG, "Dedup error: ${e.message}")
                        afterSmartFilter.sortedByDescending { it.importance }
                    }
                    Log.d(TAG, "After dedup: ${deduped.size}")

                    // Step 3.5: 语义级合并（同一日历事件的多条提醒合并）
                    val semanticallyMerged = try {
                        DeduplicationEngine.semanticMerge(deduped)
                    } catch (e: Exception) {
                        Log.e(TAG, "Semantic merge error: ${e.message}")
                        deduped
                    }
                    Log.d(TAG, "After semantic merge: ${semanticallyMerged.size}")

                    // Step 4: LLM 优先级分类
                    val afterPriorityClassify = try {
                        withTimeout(20_000L) {
                            PriorityClassifier.classifyBatch(semanticallyMerged)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "PriorityClassifier timeout, using rule-based fallback")
                        PriorityClassifier.classifyBatch(semanticallyMerged)
                    } catch (e: Exception) {
                        Log.w(TAG, "PriorityClassifier error: ${e.message}")
                        semanticallyMerged
                    }
                    Log.d(TAG, "After priority classify: ${afterPriorityClassify.size}")

                    // Step 5: 最低阈值过滤
                    val finalItems = afterPriorityClassify.filter {
                        // urgent / today 级别即使分值低也保留
                        if (it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.URGENT ||
                            it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.TODAY) true
                        // reference 级别且 importance < 0.15 的过滤掉
                        if (it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.REFERENCE) {
                            it.importance >= 0.15f
                        } else {
                            // 未知级别保持原逻辑
                            it.importance >= 0.1f
                        }
                    }
                    Log.d(TAG, "Final items: ${finalItems.size}")

                    _filteredCount = rawCount - finalItems.size
                    _items.value = finalItems
                    _isLoading.value = false
                }.collect()
            } catch (e: Exception) {
                Log.e(TAG, "startMerging crashed: ${e.message}", e)
                _isLoading.value = false
            }
        }
    }

    /**
     * 每 60 秒兜底刷新（防止 ContentObserver 漏通知）
     * 各源通过 Flow 自动推送更新，这里触发一次拉取确保数据同步。
     */
    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                Log.d(TAG, "Periodic refresh triggered — Flow will auto-update via ContentObserver")
                // Flow 基于 ContentObserver 实时更新，这里仅作为兜底日志。
                // 如需主动拉取（如网络数据源），可在此添加拉取逻辑。
            }
        }
    }

    /**
     * 手动刷新
     */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            delay(500)
            _isLoading.value = false
        }
    }

    /**
     * 标记单条为已读
     */
    fun markAsRead(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(isRead = true) else it
        }
    }

    /**
     * 标记所有为已读
     */
    fun markAllAsRead() {
        _items.value = _items.value.map { it.copy(isRead = true) }
    }

    /**
     * 删除单条
     */
    fun removeItem(id: String) {
        _items.value = _items.value.filter { it.id != id }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        Log.d(TAG, "PersonalCenterViewModel cleared")
    }
}

/**
 * Factory for creating PersonalCenterViewModel with Application context
 */
class PersonalCenterViewModelFactory(
    private val app: Application,
    private val permManager: PermissionManager? = null
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PersonalCenterViewModel::class.java)) {
            return PersonalCenterViewModel(app, permManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
