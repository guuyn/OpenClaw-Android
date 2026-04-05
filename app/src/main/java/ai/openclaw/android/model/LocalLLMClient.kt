package ai.openclaw.android.model

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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

    private val _state = MutableStateFlow(LoadState.IDLE)
    val state: StateFlow<LoadState> = _state

    companion object {
        private const val TAG = "LocalLLMClient"
        private const val MODEL_DIR = "models"
        private const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        private const val MODEL_DIR_SD = "/sdcard/Download"

        // Sampling defaults (Double for LiteRT-LM SamplerConfig)
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
    }

    // ==================== Lifecycle ====================

    /**
     * Initialize the LiteRT-LM engine.
     * Loads the .litertlm model and prepares for inference.
     * Must be called on a background thread/coroutine.
     *
     * @return true if initialization succeeded
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == LoadState.LOADED) {
            Log.w(TAG, "Already initialized")
            return@withContext true
        }

        try {
            _state.value = LoadState.LOADING

            val modelFile = findModelFile()
            if (modelFile == null) {
                Log.w(TAG, "Model not found in ${context.filesDir}/$MODEL_DIR/")
                _state.value = LoadState.ERROR
                return@withContext false
            }

            Log.i(TAG, "Loading model: ${modelFile.name} (${formatSize(modelFile.length())})")

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = 2048,
                cacheDir = context.cacheDir.path,
            )

            engine = Engine(engineConfig).also { it.initialize() }

            _state.value = LoadState.LOADED
            Log.i(TAG, "LiteRT-LM engine initialized successfully")
            LogManager.shared.log("INFO", TAG, "Model loaded: ${modelFile.name}")
            true
        } catch (e: Exception) {
            _state.value = LoadState.ERROR
            Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
            LogManager.shared.log("ERROR", TAG, "Init failed: ${e.message}")
            false
        }
    }

    /**
     * Release all resources
     */
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

            val result = withContext(Dispatchers.IO) {
                eng.createConversation(buildConversationConfig(messages, tools)).use { conversation ->
                    val lastContent = messages.last().content
                    val response = conversation.sendMessage(lastContent)

                    // Check for tool calls
                    val toolCalls = response.toolCalls
                    if (!toolCalls.isNullOrEmpty()) {
                        Pair(response.contents?.toString() ?: "", convertToolCalls(toolCalls))
                    } else {
                        Pair(response.toString(), null)
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

            // Check for tool calls in the final message
            val toolCalls = responseMessage?.toolCalls
            val openClawToolCalls = if (!toolCalls.isNullOrEmpty()) {
                convertToolCalls(toolCalls)
            } else null

            emit(ChatEvent.Complete(wrapResponse(fullText.toString(), openClawToolCalls)))
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            emit(ChatEvent.Error(e.message ?: "Generation failed"))
        }
    }.flowOn(Dispatchers.IO)

    override fun configure(provider: ModelProvider, apiKey: String, model: String) {
        // Local model doesn't need API key
    }

    // ==================== Conversation Setup ====================

    /**
     * Build ConversationConfig from the message history.
     *
     * LiteRT-LM handles Gemma 4 prompt templating internally.
     * We extract system instruction, convert messages, and pass tools.
     */
    private fun buildConversationConfig(
        messages: List<Message>,
        tools: List<Tool>? = null
    ): ConversationConfig {
        val systemInstruction = messages.firstOrNull { it.role == "system" }?.content

        // Convert conversation history (exclude system + last message sent via sendMessage)
        val filteredMessages = messages.filter { it.role != "system" }.dropLast(1)
        val initialMessages = filteredMessages.map { msg -> convertMessage(msg, filteredMessages) }

        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = DEFAULT_TEMPERATURE,
            seed = 0,
        )

        val systemContents = systemInstruction?.let { Contents.of(it) }

        // Convert OpenClaw tools to LiteRT-LM ToolProvider list
        val litertTools = convertTools(tools)

        return ConversationConfig(
            systemInstruction = systemContents,
            initialMessages = initialMessages,
            tools = litertTools,
            samplerConfig = samplerConfig,
        )
    }

    /**
     * Convert an OpenClaw Message to a LiteRT-LM Message.
     * @param allMessages full message list (needed to resolve tool name for tool-role messages)
     */
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

    /**
     * Convert OpenClaw Tool list to LiteRT-LM ToolProvider list using OpenApiTool.
     */
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

                override fun execute(args: String): String {
                    // Tool execution is handled by AgentSession, not by LiteRT-LM
                    return "{}"
                }
            }
            tool(openApiTool)
        }
    }

    /**
     * Convert LiteRT-LM ToolCalls to OpenClaw ToolCalls.
     */
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

    private fun findModelFile(): File? {
        // 1. App private storage (preferred)
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (modelDir.exists()) {
            val primary = File(modelDir, MODEL_FILE)
            if (primary.exists()) return primary
            // Case-insensitive fallback
            modelDir.listFiles()?.firstOrNull {
                it.name.equals(MODEL_FILE, ignoreCase = true) || it.name.endsWith(".litertlm")
            }?.let { return it }
        }

        // 2. /sdcard/Download (fallback for manual copy)
        val sdcardDir = File(MODEL_DIR_SD)
        if (sdcardDir.exists()) {
            val sdcardFile = File(sdcardDir, MODEL_FILE)
            if (sdcardFile.exists()) return sdcardFile
            // Case-insensitive fallback
            sdcardDir.listFiles()?.firstOrNull {
                it.name.equals(MODEL_FILE, ignoreCase = true) || it.name.endsWith(".litertlm")
            }?.let { return it }
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

    fun isModelDownloaded(): Boolean = findModelFile() != null

    fun getModelSizeMB(): Long = findModelFile()?.let { it.length() / (1024 * 1024) } ?: 0

    fun getState(): LoadState = _state.value
}
