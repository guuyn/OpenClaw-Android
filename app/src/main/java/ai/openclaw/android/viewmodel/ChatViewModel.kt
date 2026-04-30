package ai.openclaw.android.viewmodel

import android.content.Context
import android.util.Log
import ai.openclaw.android.ChatMessage
import ai.openclaw.android.ConfigManager
import ai.openclaw.android.GatewayContract
import ai.openclaw.android.LogManager
import ai.openclaw.android.MockDataProvider
import ai.openclaw.android.agent.AgentSession
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
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.FallbackMemoryExtractor
import ai.openclaw.android.domain.memory.HybridSearchEngine
import ai.openclaw.android.domain.memory.LlmMemoryExtractor
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.domain.session.SessionCompressor
import ai.openclaw.android.domain.session.TokenCounter
import ai.openclaw.android.ml.EmbeddingServiceFactory
import ai.openclaw.android.model.AnthropicClient
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.model.OpenAIClient
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
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
 * 管理聊天消息列表、AgentSession 生命周期、消息发送与流式响应
 * 统一通过 GatewayContract（优先）或 AgentSession（回退）发送消息
 */
class ChatViewModel(
    private val skillManager: SkillManager,
    private val permManager: PermissionManager,
    private val database: AppDatabase,
    private val embeddingService: EmbeddingService,
    private val hybridSearchEngine: HybridSearchEngine
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

    // ==================== Session 管理状态 ====================

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

    /** 消息网关（可切换 RealGateway / MockGateway） */
    private var messageGateway: MessageGateway? = null
    private var agentSession: AgentSession? = null
    private var modelClient: ModelClient? = null
    private var localLLMClient: LocalLLMClient? = null
    private var sessionManager: HybridSessionManager? = null
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
            // 恢复 RealGateway
            val contract = contractProvider?.invoke()
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
     * 初始化 AgentSession 和内存子系统
     *
     * 在首次组合时调用，加载配置、创建模型客户端、设置记忆系统
     */
    fun initialize(context: Context) {
        if (_isInitialized.value) return
        viewModelScope.launch {
            try {
                ConfigManager.init(context)

                if (!ConfigManager.hasModelCredentials()) {
                    ConfigManager.setModelApiKey("YOUR_API_KEY_HERE")
                    ConfigManager.setModelName("qwen3.5-plus")
                    Log.d(TAG, "Default API key set for debugging")
                }

                val modelProvider = try {
                    ConfigManager.getModelProvider()
                } catch (_: Exception) {
                    "OPENAI"
                }

                // 初始化模型客户端
                createModelClient(context, modelProvider)

                // 初始化 AgentSession
                agentSession = AgentSession(modelClient!!, skillManager, permManager)
                agentSession?.setToolsWithSkills(emptyList()) { "Accessibility not available" }

                // 初始化设备能力检测与响应路由
                initResponseRouting(context)

                // 初始化 Embedding 服务
                val embedding = EmbeddingServiceFactory.create(context)

                // 初始化记忆子系统
                setupMemorySubsystem(embedding)

                // 初始化 ScriptEngine UI Provider
                initScriptUiProvider(context)

                // 初始化 Session 列表收集
                collectSessionList()

                _isInitialized.value = true
                Log.d(TAG, "初始化完成 (provider: $modelProvider), ${skillManager.getSkillCount()} skills")
            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
                LogManager.shared.log("ERROR", "Chat", "初始化失败: ${e.message}")
            }
        }
    }

    /**
     * 根据配置创建模型客户端
     */
    private suspend fun createModelClient(context: Context, modelProvider: String) {
        val provider = try {
            ModelProvider.valueOf(modelProvider)
        } catch (_: Exception) {
            ModelProvider.OPENAI
        }

        if (provider == ModelProvider.LOCAL) {
            val client = LocalLLMClient(context)
            localLLMClient = client
            val loaded = client.initialize()
            if (!loaded) {
                Log.e(TAG, "本地模型加载失败，回退到云端")
                localLLMClient = null
                val cloudClient = createCloudClient(ModelProvider.OPENAI)
                modelClient = cloudClient
            } else {
                modelClient = client
            }
        } else {
            modelClient = createCloudClient(provider)
        }
    }

    private fun createCloudClient(provider: ModelProvider): ModelClient {
        val baseUrl = ConfigManager.getEffectiveBaseUrl()
        val apiKey = ConfigManager.getModelApiKey()
        val model = ConfigManager.getModelName()

        val client: ModelClient = when (provider) {
            ModelProvider.ANTHROPIC -> AnthropicClient()
            else -> OpenAIClient()
        }
        client.configure(provider, apiKey, model, baseUrl)
        return client
    }

    /**
     * 初始化响应路由：检测设备能力并配置到 AgentSession
     */
    private fun initResponseRouting(context: Context) {
        val capabilities = DeviceCapabilities.fromContext(context)
        responseRouter = ResponseRouter(capabilities)
        agentSession?.setDeviceCapabilities(capabilities)
        Log.d(TAG, "Response routing initialized: profile=${capabilities.profile}")
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

        // 注入到 ScriptSkill
        val scriptSkill = skillManager.getLoadedSkills()["script"]
            as? ai.openclaw.android.skill.builtin.ScriptSkill
        scriptSkill?.setUiProvider(scriptUiManager)

        Log.d(TAG, "ScriptUiProvider initialized")
    }

    /**
     * 设置记忆子系统：MemoryManager → HybridSessionManager → AgentSession
     */
    private suspend fun setupMemorySubsystem(embedding: EmbeddingService) {
        val extractor = if (localLLMClient?.isModelLoaded() == true)
            LlmMemoryExtractor(localLLMClient!!)
        else
            FallbackMemoryExtractor()

        val mm = MemoryManager(
            memoryDao = database.memoryDao(),
            vectorDao = database.memoryVectorDao(),
            embeddingService = embedding,
            extractor = extractor
        )

        val compressor = SessionCompressor(
            llmClient = localLLMClient,
            summaryDao = database.summaryDao()
        )
        val sm = HybridSessionManager(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            summaryDao = database.summaryDao(),
            sessionCompressor = compressor,
            tokenCounter = TokenCounter(),
            memoryManager = mm
        )
        sessionManager = sm
        sm.initialize()

        agentSession?.setSessionManager(sm)
        agentSession?.setMemoryContextProvider {
            sm.getMemoryContext()
        }
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

                // Fallback to AgentSession (no gateway available)
                val session = agentSession
                if (session == null) {
                    val msgs = _messages.value.toMutableList()
                    msgs.add(ChatMessage(role = "assistant", content = "服务未就绪，请稍候或检查设置"))
                    _messages.value = msgs
                    _isLoading.value = false
                    return@launch
                }
                sendMessageViaSession(session, text)
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

    /** 通过 AgentSession 发送消息 */
    private suspend fun sendMessageViaSession(session: AgentSession, text: String) {
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
        val session = agentSession
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
     * 更新模型配置并重新初始化 AgentSession
     */
    fun updateConfig(context: Context, provider: String, apiKey: String, modelName: String, baseUrl: String = "") {
        viewModelScope.launch {
            try {
                ConfigManager.setModelApiKey(apiKey)
                ConfigManager.setModelName(modelName)
                ConfigManager.setModelProvider(provider)
                ConfigManager.setModelBaseUrl(baseUrl)

                // 释放旧的本地模型资源
                localLLMClient?.release()
                localLLMClient = null

                // 重新创建模型客户端
                createModelClient(context, provider)

                // 重新创建 AgentSession
                agentSession = AgentSession(modelClient!!, skillManager, permManager)
                agentSession?.setToolsWithSkills(emptyList()) { "Accessibility not available" }

                // 重新连接记忆子系统
                setupMemorySubsystem(embeddingService)

                LogManager.shared.log("INFO", "ChatViewModel", "配置已更新 (provider: $provider)")
            } catch (e: Exception) {
                Log.e(TAG, "配置更新失败", e)
                LogManager.shared.log("ERROR", "ChatViewModel", "配置更新失败: ${e.message}")
            }
        }
    }

    /**
     * 清空聊天历史
     */
    fun clearHistory() {
        agentSession?.clearHistory()
        _messages.value = emptyList()
    }

    // ==================== Session 管理方法 ====================

    /** 收集 Session 列表 Flow */
    private fun collectSessionList() {
        viewModelScope.launch {
            database.sessionDao().getAllSessions().collect { sessions ->
                _allSessions.value = sessions
                // 自动设置当前会话 ID（如果尚未设置）
                if (_currentSessionId.value == null) {
                    sessionManager?.getCurrentSessionId()?.let {
                        _currentSessionId.value = it
                    }
                }
            }
        }
    }

    /** 创建新会话 */
    fun createNewSession() {
        viewModelScope.launch {
            try {
                sessionManager?.createNamedSession("")?.let { session ->
                    _currentSessionId.value = session.sessionId
                    clearHistory()
                    Log.d(TAG, "Created new session: ${session.sessionId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create new session", e)
            }
        }
    }

    /** 切换到指定会话 */
    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            try {
                // 1. 清空当前状态
                clearHistory()

                // 2. 切换底层会话
                sessionManager?.switchToSession(sessionId)?.getOrNull()?.let { session ->
                    _currentSessionId.value = session.sessionId

                    // 3. 加载历史消息（最近 50 条）
                    loadHistoryMessages(sessionId)

                    Log.d(TAG, "Switched to session: ${session.name ?: "新对话"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch session", e)
            }
        }
    }

    /** 从数据库加载历史消息到 UI */
    private suspend fun loadHistoryMessages(sessionId: String) {
        val entities = database.messageDao()
            .getMessagesBySessionIdWithLimit(sessionId, limit = 50, offset = 0)

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

    /** 重命名会话 */
    fun renameSession(sessionId: String, newName: String) {
        viewModelScope.launch {
            try {
                val session = database.sessionDao().getSessionById(sessionId)
                if (session != null) {
                    val updated = session.copy(name = newName)
                    database.sessionDao().updateSession(updated)
                    Log.d(TAG, "Renamed session $sessionId to '$newName'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /** 删除会话 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                database.sessionDao().deleteSessionById(sessionId)

                // 如果删除的是当前会话，自动切换到第一个可用会话
                if (_currentSessionId.value == sessionId) {
                    val remaining = _allSessions.value.filter { it.sessionId != sessionId }
                    if (remaining.isNotEmpty()) {
                        switchToSession(remaining.first().sessionId)
                    } else {
                        // 没有剩余会话了，创建一个默认的
                        createNewSession()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /** 获取会话的消息数 */
    suspend fun getMessageCount(sessionId: String): Int {
        return try {
            database.messageDao().getMessageCountBySessionId(sessionId)
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
        localLLMClient?.release()
    }
}
