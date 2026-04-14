package ai.openclaw.android

import android.util.Log
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.domain.memory.FallbackMemoryExtractor
import ai.openclaw.android.domain.memory.LlmMemoryExtractor
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.domain.session.TokenCounter
import ai.openclaw.android.ml.TfLiteEmbeddingService
import ai.openclaw.android.model.BailianClient
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.builtin.WeatherSkill
import ai.openclaw.android.skill.builtin.MultiSearchSkill
import ai.openclaw.android.skill.builtin.TranslateSkill
import ai.openclaw.android.skill.builtin.ReminderSkill
import ai.openclaw.android.skill.builtin.CalendarSkill
import ai.openclaw.android.skill.builtin.LocationSkill
import ai.openclaw.android.skill.builtin.ContactSkill
import ai.openclaw.android.skill.builtin.SMSSkill
import ai.openclaw.android.skill.builtin.NotificationSkill
import ai.openclaw.android.skill.builtin.GenerateSkillTool
import ai.openclaw.android.skill.builtin.GenerateSkillSkill
import ai.openclaw.android.skill.DynamicSkillManager
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.android.feishu.FeishuClient
import ai.openclaw.android.feishu.OkHttpFeishuClient
import ai.openclaw.android.feishu.FeishuEvent
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * GatewayManager - Manages all Gateway components
 * Implements GatewayContract to provide a clean API for Activity consumption
 */
class GatewayManager(private val service: GatewayService) : GatewayContract {

    companion object {
        private const val TAG = "GatewayManager"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Components (kept private)
    private var modelClient: ModelClient? = null
    private var localLLMClient: LocalLLMClient? = null
    private var agentSession: AgentSession? = null
    private var accessibilityBridge: AccessibilityBridge? = null
    private var skillManager: SkillManager? = null
    private var feishuClient: FeishuClient? = null
    private var dynamicSkillManager: DynamicSkillManager? = null

    // Memory subsystem (moved from Activity)
    private var database: AppDatabase? = null
    private var embeddingService: TfLiteEmbeddingService? = null
    private var memoryManager: MemoryManager? = null
    private var sessionManager: HybridSessionManager? = null

    // State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override fun getConnectionState(): StateFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // ========== GatewayContract implementation ==========

    override fun isReady(): Boolean = agentSession != null

    override fun getModelLoadState(): LocalLLMClient.LoadState? = localLLMClient?.getState()

    override fun sendMessage(text: String): Flow<SessionEvent> =
        agentSession?.handleMessageStream(text)
            ?: flow { emit(SessionEvent.Error("AgentSession not ready")) }

    override suspend fun reconfigureModel(config: ModelConfig): Boolean {
        Log.d(TAG, "Reconfiguring model: provider=${config.provider}")

        // 1. Release old model
        localLLMClient?.release()
        localLLMClient = null

        // 2. Update config
        ConfigManager.setModelProvider(config.provider.name)
        ConfigManager.setModelApiKey(config.apiKey)
        ConfigManager.setModelName(config.modelName)

        // 3. Create new model client
        modelClient = if (config.provider == ModelProvider.LOCAL) {
            val client = LocalLLMClient(service)
            localLLMClient = client
            val loaded = client.initialize()
            if (!loaded) {
                Log.e(TAG, "Failed to load local model, falling back to BAILIAN")
                localLLMClient = null
                createCloudClient()
            } else {
                client
            }
        } else {
            createCloudClient(config.provider)
        }

        // 3.5. Ensure SkillManager is initialized (may be null if called before start())
        if (skillManager == null) {
            Log.d(TAG, "SkillManager not initialized, initializing now")
            accessibilityBridge = AccessibilityBridge()
            skillManager = SkillManager(service).apply {
                registerSkill(ai.openclaw.android.skill.builtin.WeatherSkill())
                registerSkill(ai.openclaw.android.skill.builtin.MultiSearchSkill())
                registerSkill(ai.openclaw.android.skill.builtin.TranslateSkill())
                registerSkill(ai.openclaw.android.skill.builtin.ReminderSkill(service))
                registerSkill(ai.openclaw.android.skill.builtin.CalendarSkill(service))
                registerSkill(ai.openclaw.android.skill.builtin.LocationSkill(service))
                registerSkill(ai.openclaw.android.skill.builtin.ContactSkill(service))
                registerSkill(ai.openclaw.android.skill.builtin.SMSSkill(service))
                registerSkill(NotificationSkill(service))
            }
        }

        // 3.6. Inject MemoryManager into ScriptSkill if available
        if (memoryManager != null) {
            val scriptSkill = skillManager?.getLoadedSkills()?.get("script") as? ai.openclaw.android.skill.builtin.ScriptSkill
            scriptSkill?.setMemoryManager(memoryManager)
        }

        // 4. Rebuild AgentSession (keeping same SkillManager and tools)
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!,
            maxContextTokens = 4000
        ).apply {
            setToolsWithSkills(
                accessibilityTools = accessibilityBridge?.getTools() ?: emptyList(),
                executor = { toolCall ->
                    accessibilityBridge?.execute(toolCall) ?: "Accessibility not available"
                }
            )
        }

        // 5. Rewire memory subsystem
        wireMemoryToSession()

        Log.d(TAG, "Model reconfigured successfully")
        return agentSession != null
    }

    override fun getAvailableSkills(): List<SkillInfo> =
        skillManager?.getLoadedSkills()?.map { (id, skill) ->
            SkillInfo(id, skill.name, skill.description)
        } ?: emptyList()

    // ========== Lifecycle ==========

    fun start() {
        Log.d(TAG, "Starting Gateway...")

        if (!ConfigManager.isConfigured()) {
            _connectionState.value = ConnectionState.Error("Configuration incomplete")
            return
        }

        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                initializeComponents()
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Gateway started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Gateway: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping Gateway...")

        localLLMClient?.release()
        localLLMClient = null

        skillManager?.getLoadedSkills()?.values?.forEach { skill ->
            try {
                skill.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup skill: ${e.message}")
            }
        }
        skillManager = null

        agentSession = null
        accessibilityBridge = null

        sessionManager = null
        memoryManager = null
        dynamicSkillManager?.cleanup()
        dynamicSkillManager = null

        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Gateway stopped")
    }

    fun cleanup() {
        stop()
        serviceScope.cancel()
    }

    // ========== Internal ==========

    private suspend fun initializeComponents() {
        Log.d(TAG, "Initializing components...")

        // Initialize ModelClient based on provider
        val providerName = ConfigManager.getModelProvider()
        val provider = try {
            ModelProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown provider: $providerName, falling back to BAILIAN")
            ModelProvider.BAILIAN
        }

        modelClient = if (provider == ModelProvider.LOCAL) {
            Log.i(TAG, "Using LOCAL on-device model (Gemma 4 E4B)")
            val client = LocalLLMClient(service)
            localLLMClient = client
            val loaded = client.initialize()
            if (!loaded) {
                Log.e(TAG, "Failed to load local model, falling back to BAILIAN")
                localLLMClient = null
                createCloudClient()
            } else {
                client
            }
        } else {
            Log.i(TAG, "Using cloud model: $provider / ${ConfigManager.getModelName()}")
            createCloudClient(provider)
        }

        // Initialize AccessibilityBridge
        accessibilityBridge = AccessibilityBridge()

        // Initialize SkillManager and register skills
        skillManager = SkillManager(service).apply {
            registerSkill(WeatherSkill())
            registerSkill(MultiSearchSkill())
            registerSkill(TranslateSkill())
            registerSkill(ReminderSkill(service))
            registerSkill(CalendarSkill(service))
            registerSkill(LocationSkill(service))
            registerSkill(ContactSkill(service))
            registerSkill(SMSSkill(service))
            registerSkill(NotificationSkill(service))
        }

        // Initialize database first (needed by DynamicSkillManager)
        database = AppDatabase.getInstance(service)

        // Initialize DynamicSkillManager and register generate_skill tool
        dynamicSkillManager = DynamicSkillManager(
            context = service,
            dynamicSkillDao = database!!.dynamicSkillDao(),
            skillManager = skillManager!!,
            orchestrator = ai.openclaw.script.ScriptOrchestrator(service),
            preferenceManager = ai.openclaw.android.skill.UserPreferenceManager(service),
            onUserConfirmation = { _, _ ->
                // TODO: 实际项目中需要弹出确认对话框
                // 目前默认 ALWAYS_APPROVE（开发阶段）
                ApprovalDecision.ALWAYS_APPROVE
            }
        )
        dynamicSkillManager!!.loadAllSaved()

        val generateSkillTool = GenerateSkillTool(dynamicSkillManager!!)
        val generateSkillSkill = GenerateSkillSkill(generateSkillTool)
        skillManager!!.registerSkill(generateSkillSkill)

        // Initialize AgentSession with SkillManager
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!,
            maxContextTokens = 4000
        ).apply {
            setToolsWithSkills(
                accessibilityTools = accessibilityBridge!!.getTools(),
                executor = { toolCall ->
                    accessibilityBridge!!.execute(toolCall)
                }
            )
        }

        // Initialize memory subsystem
        embeddingService = TfLiteEmbeddingService(service)
        embeddingService!!.initialize()

        wireMemoryToSession()

        // Initialize FeishuClient
        val httpClient = OkHttpClient()
        feishuClient = OkHttpFeishuClient(httpClient).apply {
            connect(ConfigManager.getFeishuAppId(), ConfigManager.getFeishuAppSecret())
            setEventListener { event -> handleFeishuEvent(event) }
        }

        Log.d(TAG, "Components initialized")
    }

    private suspend fun wireMemoryToSession() {
        val db = database ?: return
        val emb = embeddingService ?: return

        val extractor = if (localLLMClient?.isModelLoaded() == true)
            LlmMemoryExtractor(localLLMClient!!)
        else
            FallbackMemoryExtractor()

        val mm = MemoryManager(
            memoryDao = db.memoryDao(),
            vectorDao = db.memoryVectorDao(),
            embeddingService = emb,
            extractor = extractor
        )
        memoryManager = mm

        val sm = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
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

        // 注入 MemoryManager 到 ScriptSkill
        val scriptSkill = skillManager?.getLoadedSkills()?.get("script") as? ai.openclaw.android.skill.builtin.ScriptSkill
        scriptSkill?.setMemoryManager(mm)
    }

    private fun handleFeishuEvent(event: FeishuEvent) {
        if (event.type == "im.message.receive_v1") {
            val message = event.event?.message
            if (message != null) {
                serviceScope.launch {
                    agentSession?.handleMessage(message.content)
                }
            }
        }
    }

    private fun createCloudClient(provider: ModelProvider = ModelProvider.BAILIAN): ModelClient {
        return BailianClient().apply {
            configure(
                provider = provider,
                apiKey = ConfigManager.getModelApiKey(),
                model = ConfigManager.getModelName()
            )
        }
    }
}
