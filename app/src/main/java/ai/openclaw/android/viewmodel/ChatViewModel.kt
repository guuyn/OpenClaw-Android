package ai.openclaw.android.viewmodel

import android.content.Context
import android.util.Log
import ai.openclaw.android.ChatMessage
import ai.openclaw.android.ConfigManager
import ai.openclaw.android.GatewayContract
import ai.openclaw.android.LogManager
import ai.openclaw.android.MockDataProvider
import ai.openclaw.android.ModelConfig
import ai.openclaw.android.gateway.MessageGateway
import ai.openclaw.android.gateway.MockGateway
import ai.openclaw.android.gateway.MockScenario
import ai.openclaw.android.gateway.RealGateway
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.domain.AgentResponse
import ai.openclaw.android.domain.AgentResponseParser
import ai.openclaw.android.domain.Deliverable
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.ResponseRouter
import ai.openclaw.android.domain.ResponseType
import ai.openclaw.android.domain.RichContent
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.ui.ScriptUiManager
import ai.openclaw.android.ui.ConfirmRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天 ViewModel
 *
 * 管理聊天消息列表、消息发送与流式响应
 * 模型创建、Session 生命周期、Memory 管理等由 GatewayManager 通过 GatewayContract 提供
 */
class ChatViewModel(
    private val skillManager: SkillManager,
    private val permManager: PermissionManager,
    private val database: AppDatabase
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // ==================== 聊天消息状态 ====================

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // ==================== Sessions 管理状态 ====================

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _allSessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val allSessions: StateFlow<List<SessionEntity>> = _allSessions.asStateFlow()

    // ==================== ScriptEngine UI ====================

    /** ScriptEngine UI 管理器 */
    private var scriptUiManager: ScriptUiManager? = null

    /** 确认弹窗请求 — ChatScreen 监听此 StateFlow 显示 AlertDialog */
    private val _confirmRequest = MutableStateFlow<ConfirmRequest?>(null)
    val confirmRequest: StateFlow<ConfirmRequest?> = _confirmRequest.asStateFlow()

    /** 确认弹窗结果通道 — ChatScreen 通过 submitConfirmResult() 发送 */
    private val confirmResultChannel = Channel<Boolean?>(Channel.RENDEZVOUS)

    /** 用户选择确认结果 */
    fun submitConfirmResult(result: Boolean?) {
        confirmResultChannel.trySend(result)
    }

    // ==================== 响应路由状态 ====================

    private val _lastDeliverable = MutableStateFlow<Deliverable?>(null)
    val lastDeliverable: StateFlow<Deliverable?> = _lastDeliverable.asStateFlow()

    private val _lastRichContent = MutableStateFlow<RichContent?>(null)
    val lastRichContent: StateFlow<RichContent?> = _lastRichContent.asStateFlow()

    // ==================== 测试模式 ====================

    private val _isTestMode = MutableStateFlow(ConfigManager.isTestModeEnabled())
    val isTestMode: StateFlow<Boolean> = _isTestMode.asStateFlow()

    fun setTestMode(
        enabled: Boolean,
        scenario: MockScenario = MockScenario.PlainText,
        contractProvider: (() -> GatewayContract?)? = null
    ) {
        _isTestMode.value = enabled
        ConfigManager.setTestModeEnabled(enabled)
        switchGateway(useMock = enabled, scenario = scenario, contractProvider = contractProvider)
        if (enabled) {
            loadMockData()
        } else {
            clearHistory()
        }
    }

    // ==================== 内部依赖 ====================

    /** GatewayContract 提供者，由 MainActivity 设置 */
    private var gatewayContractProvider: (() -> GatewayContract?)? = null

    /** 消息网关（可切换 RealGateway / MockGateway） */
    private var messageGateway: MessageGateway? = null
    private var responseRouter: ResponseRouter? = null
    private val agentResponseParser = AgentResponseParser()

    /**
     * Set the GatewayContract after service binding.
     * Called from Activity when GatewayService connects.
     * 正常模式下将 GatewayContract 包装为 RealGateway。
     * 测试模式下忽略，保持 MockGateway。
     */
    fun updateGatewayContract(contract: GatewayContract?) {
        if (contract != null && !_isTestMode.value) {
            messageGateway = RealGateway { contract }
            Log.d(TAG, "GatewayContract updated → RealGateway")
        } else if (contract == null) {
            messageGateway = null
            Log.d(TAG, "GatewayContract cleared")
        }
    }

    /**
     * 设置 GatewayContract 提供者回调，供内部 Session/Memory 访问使用。
     * 由 MainActivity 在创建 ViewModel 后立即调用。
     */
    fun setGatewayContractProvider(provider: (() -> GatewayContract?)?) {
        gatewayContractProvider = provider
    }

    /**
     * 设置 MessageGateway（直接注入，支持测试模式切换）
     */
    fun setMessageGateway(gateway: MessageGateway?) {
        messageGateway = gateway
        Log.d(TAG, "MessageGateway set: ${gateway?.javaClass?.simpleName ?: "null"}")
    }

    /**
     * 动态切换网关实现（测试模式 ↔ 正常模式）
     * @param useMock true 使用 MockGateway，false 恢复 RealGateway
     * @param scenario MockGateway 场景（仅 useMock=true 时有效）
     * @param contractProvider GatewayContract 提供者（用于恢复 RealGateway）
     */
    fun switchGateway(
        useMock: Boolean,
        scenario: MockScenario = MockScenario.PlainText,
        contractProvider: (() -> GatewayContract?)? = null
    ) {
        if (useMock) {
            val mg = MockGateway(scenario)
            messageGateway = mg
            Log.d(TAG, "Switched to MockGateway (scenario=$scenario)")
        } else {
            // 恢复 RealGateway — 优先使用传入的 contractProvider，其次使用内部保存的 gatewayContractProvider
            val contract = contractProvider?.invoke() ?: gatewayContractProvider?.invoke()
            if (contract != null) {
                messageGateway = RealGateway { contract }
                Log.d(TAG, "Switched to RealGateway")
            } else {
                messageGateway = null
                Log.d(TAG, "Switched to waiting for RealGateway (no contract)")
            }
        }
    }

    /** 获取当前 MockGateway（用于运行时切换场景） */
    fun getMockGateway(): MockGateway? = messageGateway as? MockGateway

    // ==================== 初始化 ====================

    /**
     * 初始化 ViewModel — 仅初始化 UI 相关组件。
     * 模型创建、Session 管理、Memory 初始化等由 GatewayManager 负责。
     */
    fun initialize(context: Context) {
        if (_isInitialized.value) return
        viewModelScope.launch {
            try {
                ConfigManager.init(context)

                // 初始化 ScriptEngine UI Provider
                initScriptUiProvider(context)

                // 初始化设备能力检测与响应路由 — 通过 GatewayContract
                val gateway = gatewayContractProvider?.invoke()
                val capabilities = gateway?.getDeviceCapabilities()
                    ?: DeviceCapabilities.fromContext(context)
                responseRouter = ResponseRouter(capabilities)

                // 注入 ScriptUiProvider 到 GatewayManager
                gateway?.setScriptUiProvider(scriptUiManager)

                // 初始化 Session 列表收集
                collectSessionList()

                _isInitialized.value = true
                Log.d(TAG, "初始化完成")
            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
            }
        }
    }

    /**
     * 初始化 ScriptUiProvider — 注入 ScriptSkill.setUiProvider()
     */
    private fun initScriptUiProvider(context: android.content.Context) {
        scriptUiManager = ScriptUiManager(
            context = context,
            scope = viewModelScope,
            onAddMessage = { chatMessage ->
                withContext(Dispatchers.Main) {
                    val current = _messages.value.toMutableList()
                    current.add(chatMessage)
                    _messages.value = current
                }
            },
            confirmRequestFlow = _confirmRequest,
            confirmResultChannel = confirmResultChannel,
        )

        Log.d(TAG, "ScriptUiProvider initialized")
    }

    // ==================== 消息发送 ====================

    /**
     * 发送用户消息并接收流式响应
     * 优先使用 GatewayContract，回退到 AgentSession
     */
    fun sendMessage(text: String, images: List<ImageContent> = emptyList()) {
        Log.d(TAG, "=== sendMessage called ===")
        LogManager.shared.log("INFO", "Chat", "User: $text")

        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(ChatMessage(role = "user", content = text, images = images.ifEmpty { null }))
        _messages.value = currentMessages

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val gateway = messageGateway
                if (gateway != null && gateway.isReady()) {
                    sendMessageViaGateway(gateway, text, images)
                    return@launch
                }

                // Fallback to AgentSession via GatewayContract
                sendMessageViaSession(text)
            } catch (e: Exception) {
                Log.e(TAG, "Chat error: ${e.message}", e)
                val updated = _messages.value.toMutableList()
                updated.add(ChatMessage(role = "assistant", content = "错误: ${e.message}"))
                _messages.value = updated
                _isLoading.value = false
            }
        }
    }

    /** 通过 MessageGateway 发送消息（统一路径，RealGateway / MockGateway 均走此方法） */
    private suspend fun sendMessageViaGateway(
        gateway: MessageGateway,
        text: String,
        images: List<ImageContent>
    ) {
        val responseId = java.util.UUID.randomUUID().toString()
        val msgs = _messages.value.toMutableList()
        msgs.add(ChatMessage(id = responseId, role = "assistant", content = ""))
        _messages.value = msgs
        val responseIndex = msgs.lastIndex

        gateway.sendMessage(text, images.ifEmpty { null }).collect { event ->
            handleSessionEvent(event, responseIndex)
        }
    }

    /** 通过 GatewayContract 获取 AgentSession 发送消息 */
    private suspend fun sendMessageViaSession(text: String) {
        val gateway = gatewayContractProvider?.invoke()
        val session = gateway?.getAgentSession()
        if (session == null) {
            val msgs = _messages.value.toMutableList()
            msgs.add(ChatMessage(role = "assistant", content = "服务未就绪，请稍候或检查设置"))
            _messages.value = msgs
            _isLoading.value = false
            return
        }

        val responseId = java.util.UUID.randomUUID().toString()
        val msgs = _messages.value.toMutableList()
        msgs.add(ChatMessage(id = responseId, role = "assistant", content = ""))
        _messages.value = msgs
        val responseIndex = msgs.lastIndex

        session.handleMessageStream(text).collect { event ->
            handleSessionEvent(event, responseIndex)
        }
    }

    /** 统一处理 SessionEvent */
    private suspend fun handleSessionEvent(event: SessionEvent, responseIndex: Int) {
        when (event) {
            is SessionEvent.Token -> {
                val updated = _messages.value.toMutableList()
                val current = updated[responseIndex]
                updated[responseIndex] = current.copy(content = current.content + event.text)
                _messages.value = updated
            }
            is SessionEvent.ToolExecuting -> {
                val updated = _messages.value.toMutableList()
                val current = updated[responseIndex]
                updated[responseIndex] = current.copy(
                    content = current.content + "\n[调用工具: ${event.name}...]\n"
                )
                _messages.value = updated
            }
            is SessionEvent.ToolResult -> { }
            is SessionEvent.ReflectionStart -> {
                val updated = _messages.value.toMutableList()
                val current = updated[responseIndex]
                updated[responseIndex] = current.copy(
                    content = current.content + "\n[🔄 反思中: ${event.role}...]\n"
                )
                _messages.value = updated
            }
            is SessionEvent.ReflectionComplete -> { }
            is SessionEvent.Complete -> {
                val parsedResponse = parseAgentResponse(event.fullText)
                val deliverable = responseRouter?.route(parsedResponse)
                    ?: Deliverable.PlainText(parsedResponse.fallbackText)

                _lastDeliverable.value = deliverable
                _lastRichContent.value = when (deliverable) {
                    is Deliverable.RichText -> deliverable.content
                    is Deliverable.Mixed -> deliverable.rich
                    else -> null
                }

                val displayText = when (deliverable) {
                    is Deliverable.PlainText -> deliverable.text
                    is Deliverable.Voice -> deliverable.text
                    is Deliverable.RichText -> parsedResponse.fallbackText
                    is Deliverable.Mixed -> deliverable.rich?.let { parsedResponse.fallbackText }
                        ?: parsedResponse.fallbackText
                }

                val updated = _messages.value.toMutableList()
                updated[responseIndex] = updated[responseIndex].copy(content = displayText)
                _messages.value = updated
                _isLoading.value = false
                LogManager.shared.log("INFO", "Chat", "Assistant: ${event.fullText.take(100)}")
            }
            is SessionEvent.Error -> {
                val updated = _messages.value.toMutableList()
                updated[responseIndex] = updated[responseIndex].copy(
                    content = updated[responseIndex].content.ifEmpty { "错误: ${event.message}" }
                )
                _messages.value = updated
                _isLoading.value = false
                LogManager.shared.log("ERROR", "Chat", "Error: ${event.message}")
            }
        }
    }

    /**
     * 处理语音输入，返回需要朗读的文本
     */
    suspend fun handleVoiceInput(text: String): String {
        val gateway = gatewayContractProvider?.invoke()
        val session = gateway?.getAgentSession()
        if (session == null) return "请先在设置中配置 API Key"

        // 添加用户消息
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(ChatMessage(role = "user", content = text))
        _messages.value = currentMessages

        val responseId = java.util.UUID.randomUUID().toString()
        val msgs = _messages.value.toMutableList()
        msgs.add(ChatMessage(id = responseId, role = "assistant", content = ""))
        _messages.value = msgs

        var fullResponse = ""
        session.handleMessageStream(text).collect { event ->
            when (event) {
                is SessionEvent.Token -> fullResponse += event.text
                is SessionEvent.Complete -> fullResponse = event.fullText
                else -> {}
            }
        }

        // Parse and route the response for voice session
        val parsedResponse = parseAgentResponse(fullResponse)
        val deliverable = responseRouter?.route(parsedResponse)
            ?: Deliverable.PlainText(parsedResponse.fallbackText)
        _lastDeliverable.value = deliverable

        // For voice sessions, return the voice text if available
        val speakText = when (deliverable) {
            is Deliverable.Voice -> deliverable.text
            is Deliverable.Mixed -> deliverable.voice ?: parsedResponse.fallbackText
            is Deliverable.PlainText -> deliverable.text
            is Deliverable.RichText -> parsedResponse.fallbackText
        }

        // 更新最终响应
        val finalMessages = _messages.value.toMutableList()
        finalMessages[msgs.lastIndex] = finalMessages[msgs.lastIndex].copy(content = speakText)
        _messages.value = finalMessages

        return speakText.ifEmpty { "抱歉，我没有理解您的问题" }
    }

    // ==================== 配置更新 ====================

    /**
     * 更新模型配置 — 委托给 GatewayContract.reconfigureModel()
     */
    fun updateConfig(provider: String, apiKey: String, modelName: String, baseUrl: String = "") {
        viewModelScope.launch {
            try {
                val gateway = gatewayContractProvider?.invoke()
                if (gateway == null) {
                    Log.e(TAG, "updateConfig: GatewayContract not available")
                    return@launch
                }

                val config = ModelConfig(
                    provider = ModelProvider.valueOf(provider),
                    apiKey = apiKey,
                    modelName = modelName,
                    baseUrl = baseUrl
                )
                val success = gateway.reconfigureModel(config)
                if (success) {
                    LogManager.shared.log("INFO", "ChatViewModel", "配置已更新 (provider: $provider)")
                } else {
                    Log.e(TAG, "updateConfig: reconfigureModel failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "配置更新失败", e)
            }
        }
    }

    /**
     * 清空聊天历史 — 委托给 GatewayContract
     */
    fun clearHistory() {
        val gateway = gatewayContractProvider?.invoke()
        gateway?.clearHistory()
        _messages.value = emptyList()
    }

    // ==================== Session 管理方法 ====================

    /** 收集 Session 列表 Flow — 通过 GatewayContract */
    private fun collectSessionList() {
        viewModelScope.launch {
            val gateway = gatewayContractProvider?.invoke()
            gateway?.getSessionListFlow()?.collect { sessions ->
                _allSessions.value = sessions
                if (_currentSessionId.value == null) {
                    gateway.getCurrentSessionId()?.let {
                        _currentSessionId.value = it
                    }
                }
            }
        }
    }

    /** 创建新会话 — 通过 GatewayContract */
    fun createNewSession() {
        viewModelScope.launch {
            try {
                val gateway = gatewayContractProvider?.invoke()
                gateway?.createNewSession()?.let { session ->
                    _currentSessionId.value = session.sessionId
                    clearHistory()
                    Log.d(TAG, "Created new session: ${session.sessionId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create new session", e)
            }
        }
    }

    /** 切换到指定会话 — 通过 GatewayContract */
    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            try {
                clearHistory()

                val gateway = gatewayContractProvider?.invoke()
                gateway?.switchToSession(sessionId)?.getOrNull()?.let { session ->
                    _currentSessionId.value = session.sessionId
                    loadHistoryMessages(sessionId)
                    Log.d(TAG, "Switched to session: ${session.name ?: "新对话"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch session", e)
            }
        }
    }

    /** 从 GatewayContract 加载历史消息到 UI */
    private suspend fun loadHistoryMessages(sessionId: String) {
        val gateway = gatewayContractProvider?.invoke()
        val entities = gateway?.loadSessionMessages(sessionId, limit = 50) ?: run {
            Log.w(TAG, "loadHistoryMessages: gateway not available")
            return
        }

        val chatMessages = entities.map { entity ->
            ChatMessage(
                id = entity.id.toString(),
                role = entity.role.name.lowercase(),
                content = entity.content,
                timestamp = entity.timestamp
            )
        }

        _messages.value = chatMessages
        Log.d(TAG, "Loaded ${chatMessages.size} history messages for session $sessionId")
    }

    /** 重命名会话 — 通过 GatewayContract */
    fun renameSession(sessionId: String, newName: String) {
        viewModelScope.launch {
            try {
                val gateway = gatewayContractProvider?.invoke()
                val success = gateway?.renameSession(sessionId, newName) ?: false
                if (success) {
                    Log.d(TAG, "Renamed session $sessionId to '$newName'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /** 删除会话 — 通过 GatewayContract */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val gateway = gatewayContractProvider?.invoke()
                gateway?.deleteSession(sessionId)

                // 如果删除的是当前会话，自动切换到第一个可用会话
                if (_currentSessionId.value == sessionId) {
                    val remaining = _allSessions.value.filter { it.sessionId != sessionId }
                    if (remaining.isNotEmpty()) {
                        switchToSession(remaining.first().sessionId)
                    } else {
                        createNewSession()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /** 获取会话的消息数 — 通过 GatewayContract */
    suspend fun getMessageCount(sessionId: String): Int {
        return try {
            val gateway = gatewayContractProvider?.invoke()
            gateway?.getMessageCount(sessionId) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get message count for session $sessionId", e)
            0
        }
    }

    // ==================== 测试注入 ====================

    /**
     * 从广播接收器注入 A2UI 测试数据
     */
    fun testInjectA2UI(a2uiJson: String) {
        if (a2uiJson.isBlank()) {
            Log.w(TAG, "[INJECT A2UI TEST] Empty JSON, skipping")
            return
        }
        Log.d(TAG, "[INJECT A2UI TEST] Received JSON: ${a2uiJson.take(100)}")
        val a2uiWrapped = "[A2UI]\n$a2uiJson\n[/A2UI]"
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(ChatMessage(role = "assistant", content = a2uiWrapped))
        _messages.value = currentMessages
        Log.d(TAG, "[INJECT A2UI TEST] Message added to list")
    }

    /**
     * 加载 Mock 测试数据
     */
    private fun loadMockData() {
        _messages.value = MockDataProvider.getAllScenarios()
    }

    // ==================== AgentResponse 解析 ====================

    /**
     * Parse LLM response text into an AgentResponse.
     *
     * Delegates to [AgentResponseParser] for pure-logic testability.
     */
    fun parseAgentResponse(text: String): AgentResponse = agentResponseParser.parse(text)

    override fun onCleared() {
        super.onCleared()
        // Lifecycle management of model/memory components is handled by GatewayManager
    }
}
