package ai.openclaw.android.viewmodel

import android.content.Context
import android.util.Log
import ai.openclaw.android.ChatMessage
import ai.openclaw.android.ConfigManager
import ai.openclaw.android.LogManager
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.HybridSearchEngine
import ai.openclaw.android.ml.EmbeddingServiceFactory
import ai.openclaw.android.domain.memory.FallbackMemoryExtractor
import ai.openclaw.android.domain.memory.LlmMemoryExtractor
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.domain.session.TokenCounter
import ai.openclaw.android.model.BailianClient
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.model.OpenAIClient
import ai.openclaw.android.model.AnthropicClient
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天 ViewModel
 *
 * 管理聊天消息列表、AgentSession 生命周期、消息发送与流式响应
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

    // ==================== 内部依赖 ====================

    private var agentSession: AgentSession? = null
    private var modelClient: ModelClient? = null
    private var localLLMClient: LocalLLMClient? = null
    private var sessionManager: HybridSessionManager? = null

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

                // 初始化 Embedding 服务
                val embedding = EmbeddingServiceFactory.create(context)

                // 初始化记忆子系统
                setupMemorySubsystem(embedding)

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

        val sm = HybridSessionManager(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            summaryDao = database.summaryDao(),
            llmClient = localLLMClient,
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
     */
    fun sendMessage(text: String) {
        Log.d(TAG, "=== sendMessage called ===")
        LogManager.shared.log("INFO", "Chat", "User: $text")

        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(ChatMessage(role = "user", content = text))
        _messages.value = currentMessages
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val session = agentSession
                if (session == null) {
                    val msgs = _messages.value.toMutableList()
                    msgs.add(ChatMessage(role = "assistant", content = "请先在设置中配置 API Key"))
                    _messages.value = msgs
                    _isLoading.value = false
                    return@launch
                }

                val responseId = java.util.UUID.randomUUID().toString()
                val msgs = _messages.value.toMutableList()
                msgs.add(ChatMessage(id = responseId, role = "assistant", content = ""))
                _messages.value = msgs
                val responseIndex = msgs.lastIndex

                session.handleMessageStream(text).collect { event ->
                    when (event) {
                        is SessionEvent.Token -> {
                            val updated = _messages.value.toMutableList()
                            val current = updated[responseIndex]
                            updated[responseIndex] = current.copy(
                                content = current.content + event.text
                            )
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
                        is SessionEvent.Complete -> {
                            val updated = _messages.value.toMutableList()
                            updated[responseIndex] = updated[responseIndex].copy(
                                content = event.fullText
                            )
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
            } catch (e: Exception) {
                Log.e(TAG, "Chat error: ${e.message}", e)
                val updated = _messages.value.toMutableList()
                updated.add(ChatMessage(role = "assistant", content = "错误: ${e.message}"))
                _messages.value = updated
                _isLoading.value = false
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

        // 更新最终响应
        val finalMessages = _messages.value.toMutableList()
        finalMessages[msgs.lastIndex] = finalMessages[msgs.lastIndex].copy(content = fullResponse)
        _messages.value = finalMessages

        return fullResponse.ifEmpty { "抱歉，我没有理解您的问题" }
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

    override fun onCleared() {
        super.onCleared()
        localLLMClient?.release()
    }
}
