package ai.openclaw.android

import android.util.Log
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentRegistry
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.agent.SystemPromptLoader
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
import ai.openclaw.android.config.AgentConfig
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.android.feishu.FeishuClient
import ai.openclaw.android.feishu.OkHttpFeishuClient
import ai.openclaw.android.feishu.FeishuEvent
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.domain.agent.AgentConfigManager
import ai.openclaw.android.domain.agent.AgentRouter
import ai.openclaw.android.domain.agent.AgentSessionManager
import ai.openclaw.android.trigger.EventBus
import ai.openclaw.android.trigger.ActionExecutor
import ai.openclaw.android.trigger.scheduler.CronScheduler
import ai.openclaw.android.trigger.models.EventSource
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
    private var agentRegistry: AgentRegistry? = null
    private var accessibilityBridge: AccessibilityBridge? = null
    private var skillManager: SkillManager? = null
    private var feishuClient: FeishuClient? = null
    private var dynamicSkillManager: DynamicSkillManager? = null

    // Multi-agent routing subsystem (Tasks 1-6)
    private var agentConfigManager: AgentConfigManager? = null
    private var agentRouter: AgentRouter? = null
    private var agentSessionManager: AgentSessionManager? = null

    // Trigger subsystem
    // Trigger subsystem
    private var triggerEventBus: EventBus? = null
    private var cronScheduler: CronScheduler? = null

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

    override fun sendMessage(text: String): Flow<SessionEvent> {
        // Multi-agent routing path (primary)
        val router = agentRouter
        val sessionManager = agentSessionManager
        if (router != null && sessionManager != null) {
            val agentId = router.route(text)
            val session = sessionManager.getOrCreate(agentId)
            return session.handleMessageStream(text)
        }
        // Backward compatibility: single-agent fallback
        return agentSession?.handleMessageStream(text)
            ?: flow { emit(SessionEvent.Error("AgentSession not ready")) }
    }

    /**
     * Send message to a specific agent
     */
    fun sendMessageToAgent(agentId: String, text: String): Flow<SessionEvent> =
        agentRegistry?.getSession(agentId)?.handleMessageStream(text)
            ?: flow { emit(SessionEvent.Error("AgentRegistry not ready")) }

    /**
     * List all configured agents
     */
    fun listAgents(): List<AgentConfig> =
        agentRegistry?.listAgents() ?: emptyList()

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
                registerSkill(ai.openclaw.android.skill.builtin.AppLauncherSkill())
                registerSkill(ai.openclaw.android.skill.builtin.SettingsSkill())
                registerSkill(ai.openclaw.android.skill.builtin.FileSkill(service))
                registerSkill(ai.openclaw.android.skill.builtin.ScriptSkill())
                registerSkill(NotificationSkill(service))
            }
        }

        // 3.6. Ensure DynamicSkillManager is initialized (for generate_skill tool)
        if (dynamicSkillManager == null) {
            database = AppDatabase.getInstance(service)
            dynamicSkillManager = DynamicSkillManager(
                context = service,
                dynamicSkillDao = database!!.dynamicSkillDao(),
                skillManager = skillManager!!,
                orchestrator = ai.openclaw.script.ScriptOrchestrator(service),
                preferenceManager = ai.openclaw.android.skill.UserPreferenceManager(service),
                onUserConfirmation = { _, _ ->
                    ApprovalDecision.ALWAYS_APPROVE
                }
            )
            dynamicSkillManager!!.loadAllSaved()
            dynamicSkillManager!!.setToolsChangedListener {
                agentSession?.refreshTools()
            }
            val generateSkillTool = GenerateSkillTool(dynamicSkillManager!!)
            val generateSkillSkill = GenerateSkillSkill(generateSkillTool)
            skillManager!!.registerSkill(generateSkillSkill)
        }

        // 3.7. Inject MemoryManager into ScriptSkill if available
        if (memoryManager != null) {
            val scriptSkill = skillManager?.getLoadedSkills()?.get("script") as? ai.openclaw.android.skill.builtin.ScriptSkill
            scriptSkill?.setMemoryManager(memoryManager)
        }

        // 4. Rebuild AgentSession for backward compatibility
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!,
            maxContextTokens = 4000
        ).apply {
            setSystemPrompt(systemPrompt)
            setToolsWithSkills(
                accessTools = accessibilityBridge?.getTools() ?: emptyList(),
                executor = { toolCall ->
                    accessibilityBridge?.execute(toolCall) ?: "Accessibility not available"
                }
            )
        }

        // 4.5. Evict cached default agent session from AgentSessionManager so the next
        //      multi-agent sendMessage creates a new session with the reconfigured model.
        //      Also update backward-compatible agentSession reference.
        agentSessionManager?.let { manager ->
            val configManager = agentConfigManager
            if (configManager != null) {
                val defaultAgentId = configManager.getDefaultAgent().id
                manager.evict(defaultAgentId)
                val defaultSession = manager.getOrCreate(defaultAgentId)
                agentSession = defaultSession
            }
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

    override fun getAvailableAgents(): List<AgentInfo> {
        val configManager = agentConfigManager ?: return emptyList()
        val defaultAgent = try {
            configManager.getDefaultAgent()
        } catch (e: IllegalStateException) {
            null
        }
        return configManager.getAllAgents().map { agent ->
            AgentInfo(
                id = agent.id,
                name = agent.name,
                isDefault = defaultAgent?.id == agent.id
            )
        }
    }

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
        agentRegistry = null
        accessibilityBridge = null

        agentConfigManager = null
        agentRouter = null
        agentSessionManager?.cleanup()
        agentSessionManager = null

        sessionManager = null
        memoryManager = null
        dynamicSkillManager?.cleanup()
        dynamicSkillManager = null

        // Cleanup trigger subsystem
        cronScheduler?.cancelAll()
        cronScheduler = null
        triggerEventBus = null

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

        // 定时清理（在服务启动时执行一次）
        serviceScope.launch {
            dynamicSkillManager!!.runMaintenance()
            Log.i(TAG, "Dynamic skill maintenance completed")
        }

        dynamicSkillManager!!.setToolsChangedListener {
            agentSession?.refreshTools()
        }

        val generateSkillTool = GenerateSkillTool(dynamicSkillManager!!)
        val generateSkillSkill = GenerateSkillSkill(generateSkillTool)
        skillManager!!.registerSkill(generateSkillSkill)

        // Initialize multi-agent routing subsystem
        agentConfigManager = AgentConfigManager(service)
        agentConfigManager!!.loadFromAssets()
        agentRouter = AgentRouter(agentConfigManager!!)
        agentSessionManager = AgentSessionManager(
            context = service,
            configManager = agentConfigManager!!,
            skillManager = skillManager!!,
            accessibilityBridge = accessibilityBridge,
            permissionManager = null
        )

        // Initialize AgentSession with SkillManager (backward compat)
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!,
            maxContextTokens = 4000
        ).apply {
            setToolsWithSkills(
                accessTools = accessibilityBridge!!.getTools(),
                executor = { toolCall ->
                    accessibilityBridge!!.execute(toolCall)
                }
            },
            skillManager = skillManager!!,
            accessibilityBridge = accessibilityBridge!!
        )

        // Keep backward compat: point agentSession to default agent
        agentSession = agentRegistry!!.getDefaultAgent().let { config ->
            agentRegistry!!.getSession(config.id)
        }

        // Initialize memory subsystem
        embeddingService = TfLiteEmbeddingService(service)
        embeddingService!!.initialize()

        wireMemoryToSession()

        // Wire memory to multi-agent default session and update backward compat reference
        agentSessionManager?.let { manager ->
            val defaultAgentId = agentConfigManager!!.getDefaultAgent().id
            val defaultSession = manager.getOrCreate(defaultAgentId)
            agentSession = defaultSession
        }
        wireMemoryToSession()

        // Initialize FeishuClient
        val httpClient = OkHttpClient()
        feishuClient = OkHttpFeishuClient(httpClient).apply {
            connect(ConfigManager.getFeishuAppId(), ConfigManager.getFeishuAppSecret())
            setEventListener { event -> handleFeishuEvent(event) }
        }

        // Initialize Trigger System
        val actionExecutor = ActionExecutor(
            context = service,
            skillManager = skillManager!!,
            agentSessionFactory = { agentSession }
        )

        EventBus.initialize(
            ruleDao = database!!.triggerRuleDao(),
            logDao = database!!.triggerLogDao(),
            actionExecutor = actionExecutor
        )
        val eventBus = EventBus.instance!!

        cronScheduler = CronScheduler(
            context = service,
            eventBus = eventBus
        )

        // Schedule all CRON rules
        val cronRules = database!!.triggerRuleDao().getEnabled().filter { it.source == EventSource.CRON }
        cronScheduler!!.scheduleAllCronRules(cronRules)

        // Register TriggerRuleSkill
        val triggerRuleSkill = ai.openclaw.android.trigger.skill.TriggerRuleSkill(
            ruleDao = database!!.triggerRuleDao(),
            logDao = database!!.triggerLogDao(),
            cronScheduler = cronScheduler!!
        )
        skillManager!!.registerSkill(triggerRuleSkill)

        Log.i(TAG, "Trigger system initialized: ${cronRules.size} cron rules scheduled")

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

        // Wire memory to backward-compatible agentSession
        agentSession?.setSessionManager(sm)
        agentSession?.setMemoryContextProvider {
            sm.getMemoryContext()
        }

        // Wire memory to multi-agent default session
        agentSessionManager?.let { manager ->
            val configManager = agentConfigManager ?: return@let
            val defaultAgentId = configManager.getDefaultAgent().id
            val defaultSession = manager.getOrCreate(defaultAgentId)
            defaultSession.setSessionManager(sm)
            defaultSession.setMemoryContextProvider {
                sm.getMemoryContext()
            }
            // Update backward-compat reference
            agentSession = defaultSession
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
