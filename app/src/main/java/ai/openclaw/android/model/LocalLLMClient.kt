package ai.openclaw.android.model

import android.content.Context
import android.os.Build
import android.util.Log
import ai.openclaw.android.LogManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message as LiteRTMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import android.content.SharedPreferences

/**
 * Local LLM Client using LiteRT-LM framework
 *
 * Runs Gemma 4 E4B on-device with .litertlm format model.
 * LiteRT-LM handles KV-cache, prompt templating, and GPU acceleration internally.
 *
 * Performance: ~18 tok/s (CPU) / ~22 tok/s (GPU) on Snapdragon 8 Gen 3+
 * Model size: ~3.5 GB (mixed 4-bit/8-bit quantization, embedding mmap)
 */
class LocalLLMClient(private val context: Context) : ModelClient {

    enum class LoadState { IDLE, LOADING, LOADED, ERROR }

    private var engine: Engine? = null
    private var _modelFileName: String? = null
    private val sessionMutex = Mutex()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LoadState.IDLE)
    val state: StateFlow<LoadState> = _state

    companion object {
        private const val TAG = "LocalLLMClient"
        private const val MODEL_DIR = "models"
        private const val MODEL_DIR_SD = "/sdcard/Download"
        private const val PREFS_NAME = "local_llm_prefs"
        private const val KEY_GPU_INIT_PENDING = "gpu_init_pending"
        private const val KEY_GPU_CRASH_COUNT = "gpu_crash_count"

        /** Supported models in priority order (first found wins) */
        private val SUPPORTED_MODELS = listOf(
            "gemma-4-E4B-it.litertlm",
            "gemma-4-E2B-it.litertlm",
        )

        // Sampling defaults (Double for LiteRT-LM SamplerConfig)
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95

        // Backend init timeout (ms)
        private const val TIMEOUT_NPU = 60_000L
        private const val TIMEOUT_GPU = 90_000L
        private const val TIMEOUT_CPU = 180_000L

        fun getBackendPriority(): List<Pair<Backend, String>> {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val brand = Build.BRAND.lowercase()

            if (brand.contains("huawei")) {
                LogManager.shared.log("INFO", TAG, "检测到 Huawei，跳过 NPU（兼容性限制）")
                return listOf(Backend.GPU() to "GPU", Backend.CPU() to "CPU")
            }

            // Honor devices: NPU supported (since MagicOS 8+ based on Android 14+)
            if (brand.contains("honor")) {
                LogManager.shared.log("INFO", TAG, "检测到 Honor，尝试 NPU → GPU → CPU")
                return listOf(
                    Backend.NPU() to "NPU",
                    Backend.GPU() to "GPU",
                    Backend.CPU() to "CPU"
                )
            }

            if (hardware.contains("qcom") || hardware.contains("sdm") || hardware.contains("sm") ||
                board.contains("kona") || board.contains("lahaina") || board.contains("taro") ||
                brand.contains("oneplus") || brand.contains("xiaomi")) {
                return listOf(
                    Backend.NPU() to "NPU",
                    Backend.GPU() to "GPU",
                    Backend.CPU() to "CPU"
                )
            }

            return listOf(Backend.GPU() to "GPU", Backend.CPU() to "CPU")
        }

        private fun tryInitEngine(
            modelPath: String,
            cacheDir: String,
            backend: Backend,
            backendName: String,
            prefs: SharedPreferences?,
            onLog: (String) -> Unit
        ): Engine? {
            onLog("尝试初始化: $backendName")
            if (backendName == "GPU" && prefs != null) {
                prefs.edit().putBoolean(KEY_GPU_INIT_PENDING, true).apply()
            }
            val maxTokens = if (modelPath.contains("E2B", ignoreCase = true)) 8192 else 16384
            return try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = backend,
                    audioBackend = Backend.CPU(),
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir,
                )
                Engine(config).also {
                    it.initialize()
                    if (backendName == "GPU" && prefs != null) {
                        prefs.edit()
                            .putBoolean(KEY_GPU_INIT_PENDING, false)
                            .putInt(KEY_GPU_CRASH_COUNT, 0)
                            .apply()
                    }
                }
            } catch (e: Throwable) {
                onLog("❌ $backendName 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
                if (backendName == "GPU" && prefs != null) {
                    prefs.edit().putBoolean(KEY_GPU_INIT_PENDING, false).apply()
                }
                null
            }
        }
    }

    // ==================== Lifecycle ====================

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == LoadState.LOADED) {
            Log.w(TAG, "Already initialized")
            return@withContext true
        }

        try {
            _state.value = LoadState.LOADING
            LogManager.shared.log("INFO", TAG, "=== 开始初始化本地模型 ===")

            // Get user-configured model name from SharedPreferences
            val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
                .getString("model_name", null)
            val modelFile = findModelFile(configuredModelName)
            if (modelFile == null) {
                val msg = "模型文件未找到: ${context.filesDir}/$MODEL_DIR/"
                Log.w(TAG, msg)
                LogManager.shared.log("ERROR", TAG, msg)
                LogManager.shared.log("INFO", TAG, "请确认 gemma-4-E4B-it.litertlm 已放入 /sdcard/Download/ 或应用 filesDir/models/")
                _state.value = LoadState.ERROR
                return@withContext false
            }

            LogManager.shared.log("INFO", TAG, "模型文件: ${modelFile.name} (${formatSize(modelFile.length())})")
            _modelFileName = modelFile.name
            LogManager.shared.log("INFO", TAG, "芯片信息: ${Build.HARDWARE} / ${Build.BOARD} / ${Build.BRAND}")

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            val gpuCrashedLastTime = prefs.getBoolean(KEY_GPU_INIT_PENDING, false)
            if (gpuCrashedLastTime) {
                val crashCount = prefs.getInt(KEY_GPU_CRASH_COUNT, 0) + 1
                prefs.edit()
                    .putBoolean(KEY_GPU_INIT_PENDING, false)
                    .putInt(KEY_GPU_CRASH_COUNT, crashCount)
                    .apply()
                LogManager.shared.log("WARN", TAG, "检测到上次 GPU 初始化导致 native crash（第 ${crashCount} 次），本次跳过 GPU")
            }

            val backends = getBackendPriority().let { list ->
                if (gpuCrashedLastTime) list.filter { it.second != "GPU" } else list
            }
            LogManager.shared.log("INFO", TAG, "Backend 尝试顺序: ${backends.map { it.second }.joinToString(" → ")}")

            for ((i, pair) in backends.withIndex()) {
                val (backend, backendName) = pair
                LogManager.shared.log("INFO", TAG, "[${i + 1}/${backends.size}] $backendName")

                val timeout = when (backendName) {
                    "NPU" -> TIMEOUT_NPU
                    "GPU" -> TIMEOUT_GPU
                    else -> TIMEOUT_CPU
                }

                val result = withTimeoutOrNull(timeout) {
                    tryInitEngine(
                        modelPath = modelFile.absolutePath,
                        cacheDir = context.cacheDir.path,
                        backend = backend,
                        backendName = backendName,
                        prefs = prefs
                    ) { logMsg -> LogManager.shared.log("INFO", TAG, logMsg) }
                }

                if (result == null) {
                    LogManager.shared.log("WARN", TAG, "$backendName 初始化超时 (${timeout / 1000}s)，跳过")
                } else {
                    engine = result
                    _state.value = LoadState.LOADED
                    LogManager.shared.log("INFO", TAG, "✅ 模型初始化成功！Backend: $backendName")
                    return@withContext true
                }
            }

            val msg = "所有 Backend 初始化均失败 (尝试了: ${backends.map { it.second }.joinToString(", ")})"
            Log.e(TAG, msg)
            LogManager.shared.log("ERROR", TAG, msg)
            _state.value = LoadState.ERROR
            false
        } catch (e: Exception) {
            _state.value = LoadState.ERROR
            Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
            LogManager.shared.log("ERROR", TAG, "初始化异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun release() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        }
        engine = null
        _state.value = LoadState.IDLE
        Log.i(TAG, "LiteRT-LM engine released")
    }

    // ==================== ModelClient Interface ====================

    override suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>?
    ): Result<ModelResponse> {
        return try {
            val eng = requireEngine()
            val startTime = System.currentTimeMillis()

            val result = sessionMutex.withLock {
                withContext(Dispatchers.IO) {
                    eng.createConversation(buildConversationConfig(messages, tools)).use { conversation ->
                        val lastContent = messages.last().content
                        val response = conversation.sendMessage(lastContent)

                        val toolCalls = response.toolCalls
                        if (!toolCalls.isNullOrEmpty()) {
                            Pair(response.contents?.toString() ?: "", convertToolCalls(toolCalls))
                        } else {
                            Pair(response.toString(), null)
                        }
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            LogManager.shared.log("INFO", TAG, "Generated response in ${duration}ms")

            Result.success(wrapResponse(result.first, result.second))
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            Result.failure(e)
        }
    }

    override fun chatStream(
        messages: List<Message>,
        tools: List<Tool>?
    ): Flow<ChatEvent> = flow {
        val eng = try {
            requireEngine()
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: "Model not loaded"))
            return@flow
        }

        sessionMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                val lastContent = messages.last().content
                val fullText = StringBuilder()
                var responseMessage: com.google.ai.edge.litertlm.Message? = null

                eng.createConversation(buildConversationConfig(messages, tools)).use { conversation ->
                    conversation.sendMessageAsync(lastContent)
                        .collect { message ->
                            responseMessage = message
                            val token = message.contents?.toString() ?: ""
                            if (token.isNotEmpty()) {
                                fullText.append(token)
                                emit(ChatEvent.Token(token))
                            }
                        }
                }

                val duration = System.currentTimeMillis() - startTime
                LogManager.shared.log("INFO", TAG, "Stream complete in ${duration}ms")

                val toolCalls = responseMessage?.toolCalls
                val openClawToolCalls = if (!toolCalls.isNullOrEmpty()) {
                    convertToolCalls(toolCalls)
                } else null

                emit(ChatEvent.Complete(wrapResponse(fullText.toString(), openClawToolCalls)))
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed", e)
                emit(ChatEvent.Error(e.message ?: "Generation failed"))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun configure(provider: ModelProvider, apiKey: String, model: String, baseUrl: String) {
        // Local model doesn't need API key
    }

    // ==================== Conversation Setup ====================

    private fun buildConversationConfig(
        messages: List<Message>,
        tools: List<Tool>? = null
    ): ConversationConfig {
        val systemInstruction = messages.firstOrNull { it.role == "system" }?.content

        val filteredMessages = messages.filter { it.role != "system" }.dropLast(1)

        val isE2B = _modelFileName?.contains("E2B", ignoreCase = true) == true
        val tokenBudget = if (isE2B) 7680 else 15872
        val truncatedMessages = truncateMessages(filteredMessages, tokenBudget)

        val initialMessages = truncatedMessages.map { msg -> convertMessage(msg, truncatedMessages) }

        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = DEFAULT_TEMPERATURE,
            seed = 0,
        )

        val systemContents = systemInstruction?.let { Contents.of(it) }
        val litertTools = convertTools(tools)

        return ConversationConfig(
            systemInstruction = systemContents,
            initialMessages = initialMessages,
            tools = litertTools,
            samplerConfig = samplerConfig,
        )
    }

    private fun convertMessage(msg: ai.openclaw.android.model.Message, allMessages: List<ai.openclaw.android.model.Message>): com.google.ai.edge.litertlm.Message {
        return when (msg.role) {
            "user" -> com.google.ai.edge.litertlm.Message.user(msg.content)
            "assistant" -> {
                val toolCalls = msg.toolCalls?.map { tc ->
                    val args = try {
                        JSONObject(tc.function.arguments).let { json ->
                            val map = mutableMapOf<String, Any>()
                            for (key in json.keys()) map[key] = json.get(key)
                            map
                        }
                    } catch (_: Exception) { emptyMap<String, Any>() }
                    ToolCall(tc.function.name, args)
                }
                if (toolCalls.isNullOrEmpty()) {
                    com.google.ai.edge.litertlm.Message.model(msg.content)
                } else {
                    com.google.ai.edge.litertlm.Message.model(
                        Contents.of(msg.content),
                        toolCalls,
                        emptyMap()
                    )
                }
            }
            "tool" -> {
                val toolName = resolveToolName(msg, allMessages)
                com.google.ai.edge.litertlm.Message.tool(
                    Contents.of(Content.ToolResponse(toolName, msg.content))
                )
            }
            else -> com.google.ai.edge.litertlm.Message.user(msg.content)
        }
    }

    private fun resolveToolName(toolMsg: Message, allMessages: List<Message>): String {
        val callId = toolMsg.toolCallId ?: return ""
        val idx = allMessages.indexOf(toolMsg)
        if (idx <= 0) return ""
        for (i in (idx - 1) downTo 0) {
            val prev = allMessages[i]
            if (prev.role == "assistant") {
                prev.toolCalls?.firstOrNull { it.id == callId }?.let {
                    return it.function.name
                }
            }
        }
        return ""
    }

    private fun convertTools(tools: List<Tool>?): List<ToolProvider> {
        if (tools.isNullOrEmpty()) return emptyList()

        return tools.map { openClawTool ->
            val openApiTool = object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String {
                    val params = openClawTool.function.parameters
                    val json = JSONObject().apply {
                        put("name", openClawTool.function.name)
                        put("description", openClawTool.function.description)
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                params.properties.forEach { (name, prop) ->
                                    put(name, JSONObject().apply {
                                        put("type", prop.type)
                                        put("description", prop.description)
                                    })
                                }
                            })
                            put("required", org.json.JSONArray(params.required))
                        })
                    }
                    return json.toString()
                }

                override fun execute(args: String): String = "{}"
            }
            tool(openApiTool)
        }
    }

    private fun convertToolCalls(litertToolCalls: List<ToolCall>): List<ai.openclaw.android.model.ToolCall> {
        return litertToolCalls.map { tc ->
            val argsJson = JSONObject(tc.arguments).toString()
            ai.openclaw.android.model.ToolCall(
                id = "local_tc_${System.nanoTime()}",
                type = "function",
                function = ToolCallFunction(
                    name = tc.name,
                    arguments = argsJson
                )
            )
        }
    }

    // ==================== Response Wrapping ====================

    private fun wrapResponse(content: String, toolCalls: List<ai.openclaw.android.model.ToolCall>? = null): ModelResponse {
        val completionTokens = estimateTokens(content)

        return ModelResponse(
            id = "local-${System.currentTimeMillis()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(
                        role = "assistant",
                        content = content.ifEmpty { null },
                        toolCalls = toolCalls
                    ),
                    finishReason = if (toolCalls.isNullOrEmpty()) "stop" else "tool_calls"
                )
            ),
            usage = Usage(
                promptTokens = 0,
                completionTokens = completionTokens,
                totalTokens = completionTokens
            )
        )
    }

    // ==================== Helpers ====================

    private fun requireEngine(): Engine {
        val eng = engine
        if (eng == null || _state.value != LoadState.LOADED) {
            throw IllegalStateException("Engine not loaded. Call initialize() first.")
        }
        return eng
    }

    suspend fun ensureEngineReady(): Boolean {
        if (_state.value == LoadState.LOADED) return true
        if (_state.value == LoadState.ERROR || _state.value == LoadState.IDLE) {
            Log.w(TAG, "Engine in ${_state.value} state, attempting recovery")
            release()
            return initialize()
        }
        return false
    }

    private fun truncateMessages(messages: List<Message>, tokenBudget: Int): List<Message> {
        var totalTokens = 0
        val kept = mutableListOf<Message>()
        for (msg in messages.reversed()) {
            val msgTokens = estimateTokens(msg.content)
            if (totalTokens + msgTokens > tokenBudget) break
            totalTokens += msgTokens
            kept.add(0, msg)
        }
        if (kept.size < messages.size) {
            Log.d(TAG, "Truncated ${messages.size - kept.size} messages (budget: $tokenBudget tokens)")
        }
        return kept
    }

    /**
     * Find model file, prioritizing the user-configured model name.
     *
     * Search order:
     * 1. User-configured model name (from SharedPreferences)
     * 2. SUPPORTED_MODELS priority list (E4B → E2B)
     * 3. Any .litertlm file as last resort
     */
    private fun findModelFile(configuredModelName: String? = null): File? {
        val searchDirs = listOf(
            File(context.filesDir, MODEL_DIR),  // app private storage (preferred)
            File(MODEL_DIR_SD),                   // /sdcard/Download (fallback)
        )

        for (dir in searchDirs) {
            if (!dir.exists()) continue

            // 1. User-configured model name (highest priority)
            if (!configuredModelName.isNullOrBlank()) {
                val configuredFile = File(dir, configuredModelName)
                if (configuredFile.exists()) return configuredFile
                // Case-insensitive fallback for configured name
                dir.listFiles()?.firstOrNull { it.name.equals(configuredModelName, ignoreCase = true) }
                    ?.let { return it }
            }

            // 2. Supported models priority list
            for (name in SUPPORTED_MODELS) {
                val file = File(dir, name)
                if (file.exists()) return file
            }
            // Case-insensitive match
            for (name in SUPPORTED_MODELS) {
                dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { return it }
            }

            // 3. Any .litertlm file as last resort
            dir.listFiles()?.firstOrNull { it.name.endsWith(".litertlm") }
                ?.let { return it }
        }

        return null
    }

    private fun estimateTokens(text: String): Int {
        var tokens = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            tokens++
            if (cp > 0x2E80) tokens += 1
            i += if (cp > 0xFFFF) 2 else 1
        }
        return maxOf(1, tokens / 4)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb > 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    // ==================== Public Info ====================

    fun isModelLoaded(): Boolean = _state.value == LoadState.LOADED

    fun isModelDownloaded(): Boolean {
        val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
            .getString("model_name", null)
        return findModelFile(configuredModelName) != null
    }

    fun getModelSizeMB(): Long {
        val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
            .getString("model_name", null)
        return findModelFile(configuredModelName)?.let { it.length() / (1024 * 1024) } ?: 0
    }

    fun getState(): LoadState = _state.value

    fun resetGpuCrashFlag() {
        prefs.edit()
            .putBoolean(KEY_GPU_INIT_PENDING, false)
            .putInt(KEY_GPU_CRASH_COUNT, 0)
            .apply()
    }

    fun wasGpuSkippedDueToCrash(): Boolean =
        prefs.getInt(KEY_GPU_CRASH_COUNT, 0) > 0
}
