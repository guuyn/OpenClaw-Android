package ai.openclaw.android.agent

import android.util.Log
import ai.openclaw.android.LogManager
import ai.openclaw.android.config.AgentConfig
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.domain.AgentResponse
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.ReflectionConfig
import ai.openclaw.android.domain.ReflectionRole
import ai.openclaw.android.domain.ReflectionStrategy
import ai.openclaw.android.domain.ResponseRouter
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.model.*
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex

/**
 * AgentSession - Manages conversation context and model interactions
 *
 * Supports both synchronous (chat) and streaming (chatStream) modes.
 * Uses native function calling instead of text-based [TOOL_CALL] parsing.
 */
class AgentSession(
    private val modelClient: ModelClient,
    private val skillManager: SkillManager,
    private val permissionManager: PermissionManager? = null,
    private val maxContextTokens: Int = 4000
) {
    // Agent-specific fields (mutable backing, exposed via factory constructor)
    private var _agentConfig: AgentConfig? = null
    // Tool prefixes to allow (e.g. ["weather", "script"]), null = all tools allowed
    private var _allowedToolPrefixes: List<String>? = null
    // Reflection config for multi-round self-improvement
    private var _reflectionConfig: ReflectionConfig? = null

    // Device capabilities for response routing
    private var deviceCapabilities: DeviceCapabilities? = null
    private var responseRouter: ResponseRouter? = null

    /**
     * Set device capabilities for response routing.
     * Call this after initialization to enable LLM format decisions.
     */
    fun setDeviceCapabilities(capabilities: DeviceCapabilities) {
        deviceCapabilities = capabilities
        responseRouter = ResponseRouter(capabilities)
        Log.d(TAG, "Device capabilities set: profile=${capabilities.profile}")
    }

    /**
     * Get the response router (if device capabilities are set).
     * Returns null if capabilities haven't been configured yet.
     */
    fun getResponseRouter(): ResponseRouter? = responseRouter

    /**
     * Factory constructor — creates an AgentSession with agent-specific config.
     * Supports tool filtering and custom system prompt prepending.
     */
    constructor(
        modelClient: ModelClient,
        skillManager: SkillManager,
        agentConfig: AgentConfig,
        permissionManager: PermissionManager? = null,
        maxContextTokens: Int = 4000
    ) : this(modelClient, skillManager, permissionManager, maxContextTokens) {
        // Tool filtering: null means all tools allowed, otherwise store prefixes
        _allowedToolPrefixes = if (agentConfig.tools.contains("all")) null else {
            agentConfig.tools
        }
        _agentConfig = agentConfig
        // Auto-select reflection strategy based on agent config
        _reflectionConfig = ReflectionConfig.defaultFor(agentConfig.reflectionStrategy)
    }

    /**
     * Set reflection config for multi-round self-improvement.
     * Call this to override the auto-selected strategy.
     */
    fun setReflectionConfig(config: ReflectionConfig) {
        _reflectionConfig = config
        Log.d(TAG, "Reflection config set: strategy=${config.strategy}, roles=${config.roles.size}")
    }

    /**
     * Set reflection strategy (shorthand).
     */
    fun setReflectionStrategy(strategy: ReflectionStrategy) {
        _reflectionConfig = ReflectionConfig.defaultFor(strategy)
        Log.d(TAG, "Reflection strategy set: $strategy")
    }
    companion object {
        private const val TAG = "AgentSession"
        private const val MAX_TOOL_ROUNDS = 50

        private const val BASE_SYSTEM_PROMPT = """You are an AI assistant on an Android device with tool access.

## Rules
1. Call tools to get REAL data — never invent facts.
2. After tool returns, format results using A2UI for rich display.
3. Respond in the same language as the user.
4. Simple greetings need no tools or A2UI.

## A2UI Format
After receiving tool results, wrap the response:
[A2UI]
{"type": "<result_type>", "data": {"key": "value", ...}}
[/A2UI]
Supported types: weather, location, reminder, translation, search, generic.
"data" must be a flat object with string values.

## Card Output Guidance
When tool results arrive, ALWAYS output the response in A2UI card format. Choose the most specific card type:

- [A2UI]{"type":"weather","data":{"title":"西安 · 天气","city":"西安","condition":"晴","temperature":"20°C","feelsLike":"18°C","humidity":"45%","wind":"南风 3级","forecast":[],"alert":null},"actions":[{"label":"⏰ 降雨提醒","action":"set_rain_reminder","style":"Secondary"}]}[/A2UI]
- [A2UI]{"type":"translation","data":{"source":"Hello","target":"你好","sourceLang":"en","targetLang":"zh"},"actions":[]}[/A2UI]
- [A2UI]{"type":"search_result","data":{"query":"OpenClaw","results":[{"title":"OpenClaw","url":"https://openclaw.ai"}]},"actions":[]}[/A2UI]

If the result doesn't fit any specific card type, use the generic InfoCard:

[A2UI]{"type":"info","data":{"title":"回复","icon":"info","content":"你的回复内容"},"actions":[{"label":"📋 复制全文","action":"copy","style":"Secondary"}]}[/A2UI]

Available card types: weather, translation, search_result, reminder, calendar, location, action_confirm, contact, sms, app, settings, error, info, summary.

## Dynamic Skills
You can create new skills dynamically using the `dynamic_skill_generator_generate_skill` tool.
When a user asks you to create a game, utility, or new capability, use this tool.
The tool accepts a single `skillJson` parameter with the skill definition.
When asked to create a new capability, use `dynamic_skill_generator_generate_skill` with a complete JSON definition.
The skill definition must include: id, name, description, version, instructions, script, tools[]
Each tool must have: name, description, parameters, entryPoint, idempotent

Example:
{
  "id": "joke_generator",
  "name": "笑话生成",
  "description": "生成随机笑话",
  "version": "1.0.0",
  "instructions": "当用户想要听笑话时使用",
  "script": "const jokes = ['笑话1', '笑话2']; function get_joke() { return JSON.stringify({joke: jokes[Math.floor(Math.random()*jokes.length)]}); }",
  "tools": [{
    "name": "get_joke",
    "description": "获取一个随机笑话",
    "parameters": {},
    "entryPoint": "get_joke",
    "idempotent": true
  }]
}"""
    }

    private val history: MutableList<Message> = mutableListOf()
    private var tools: List<Tool> = emptyList()
    private var toolExecutor: (suspend (ToolCall) -> String)? = null
    private val toolExecutionMutex = Mutex()
    private var accessibilityTools: List<Tool> = emptyList()

    // System prompt — loaded from external file, not hardcoded
    private var systemPrompt: String = ""

    /**
     * Set system prompt (called by GatewayManager after loading from file)
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        Log.d(TAG, "System prompt set (${prompt.length} chars)")
    }

    // Agent config (optional, set by AgentRegistry)
    private var agentConfig: AgentConfig? = null

    /**
     * Set agent config (optional, called by AgentRegistry)
     */
    fun setAgentConfig(config: AgentConfig) {
        agentConfig = config
        Log.d(TAG, "AgentConfig set: ${config.id}, maxTokens=${config.maxContextTokens}")
    }

    /**
     * Get effective max context tokens (from config or default)
     */
    fun getMaxContextTokens(): Int = agentConfig?.maxContextTokens ?: maxContextTokens

    // Memory & persistence hooks (set via setters)
    private var memoryContextProvider: (suspend () -> String?)? = null
    private var sessionManager: HybridSessionManager? = null
    private var memoryContextText: String? = null

    // ==================== Tool Setup ====================

    fun setTools(tools: List<Tool>, executor: suspend (ToolCall) -> String) {
        this.tools = tools
        this.toolExecutor = executor
    }

    fun setToolsWithSkills(accessTools: List<Tool>, executor: suspend (ToolCall) -> String) {
        this.accessibilityTools = accessTools
        val allSkillTools = skillManager.getAllTools().map { toolDef ->
            Tool(
                type = "function",
                function = ToolFunction(
                    name = toolDef.name,
                    description = toolDef.description,
                    parameters = convertSkillParams(toolDef.parameters)
                )
            )
        }
        // Apply tool filtering based on allowed prefixes
        val prefixes = _allowedToolPrefixes
        val skillTools = if (prefixes == null) {
            allSkillTools
        } else {
            allSkillTools.filter { tool ->
                prefixes.any { prefix -> tool.function.name.startsWith("${prefix}_") }
            }
        }
        this.tools = accessTools + skillTools
        this.toolExecutor = executor
        Log.d(TAG, "Loaded ${accessTools.size} accessibility + ${skillTools.size} skill = ${this.tools.size} tools")
    }

    /**
     * 刷新工具列表（当动态技能注册后调用）
     * 重新从 SkillManager 获取最新工具列表，保留已有的 accessibility tools
     */
    fun refreshTools() {
        val currentExecutor = this.toolExecutor
        if (currentExecutor == null) {
            Log.w(TAG, "Cannot refresh tools: toolExecutor is null")
            return
        }
        val allSkillTools = skillManager.getAllTools().map { toolDef ->
            Tool(
                type = "function",
                function = ToolFunction(
                    name = toolDef.name,
                    description = toolDef.description,
                    parameters = convertSkillParams(toolDef.parameters)
                )
            )
        }
        // Apply tool filtering based on allowed prefixes
        val prefixes = _allowedToolPrefixes
        val skillTools = if (prefixes == null) {
            allSkillTools
        } else {
            allSkillTools.filter { tool ->
                prefixes.any { prefix -> tool.function.name.startsWith("${prefix}_") }
            }
        }
        val allTools = accessibilityTools + skillTools
        setTools(allTools, currentExecutor)
        Log.d(TAG, "Tools refreshed: ${allTools.size} total (${skillTools.size} skill tools)")
    }

    // ==================== Memory & Persistence Setup ====================

    fun setMemoryContextProvider(provider: suspend () -> String?) {
        this.memoryContextProvider = provider
    }

    fun setSessionManager(manager: HybridSessionManager) {
        this.sessionManager = manager
    }

    private suspend fun refreshMemoryContext() {
        memoryContextText = try {
            memoryContextProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh memory context", e)
            null
        }
    }

    private suspend fun persistMessage(role: String, content: String) {
        if (content.isBlank()) return
        val sessionMgr = sessionManager ?: return
        try {
            val messageRole = when (role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> return // skip system/tool messages
            }
            sessionMgr.addMessage(messageRole, content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist message", e)
        }
    }

    // ==================== Synchronous API (backward compat) ====================

    suspend fun handleMessage(userMessage: String): String {
        history.add(Message(role = "user", content = userMessage))
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }

        // Agent loop: call model → execute tools → repeat
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val messages = buildMessages()
            val result = modelClient.chat(messages, activeTools)

            if (result.isFailure) {
                Log.e(TAG, "Model call failed: ${result.exceptionOrNull()?.message}")
                return "抱歉，模型调用失败: ${result.exceptionOrNull()?.message}"
            }

            val response = result.getOrThrow()
            val toolCalls = response.toolCalls

            if (toolCalls.isNullOrEmpty()) {
                // No tool calls — final text response
                val content = response.content ?: ""
                history.add(Message(role = "assistant", content = content))
                trimHistoryByTokens()
                persistMessage("assistant", content)
                return content
            }

            // Execute tool calls and add to history
            executeAndRecordToolCalls(toolCalls)
        }

        // Safety: exceeded max rounds, get a final response without tools
        Log.w(TAG, "Max tool rounds reached, forcing final response")
        val messages = buildMessages()
        val result = modelClient.chat(messages, null)
        val content = result.getOrDefault(ModelResponse()).content ?: "操作完成"
        history.add(Message(role = "assistant", content = content))
        trimHistoryByTokens()
        persistMessage("assistant", content)
        return content
    }

    // ==================== Streaming API ====================

    /**
     * Streaming variant — emits tokens and tool events in real-time.
     * The flow completes with a [SessionEvent.Complete] containing the full text.
     */
    fun handleMessageStream(userMessage: String): Flow<SessionEvent> = flow {
        history.add(Message(role = "user", content = userMessage))
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val messages = buildMessages()
            val fullText = StringBuilder()
            var completeResponse: ModelResponse? = null

            // Collect all streaming events
            modelClient.chatStream(messages, activeTools).collect { event ->
                when (event) {
                    is ChatEvent.Token -> {
                        fullText.append(event.text)
                        emit(SessionEvent.Token(event.text))
                    }
                    is ChatEvent.Complete -> {
                        completeResponse = event.response
                    }
                    is ChatEvent.Error -> {
                        emit(SessionEvent.Error(event.message))
                        return@collect
                    }
                    is ChatEvent.ToolCallRequested -> {
                        // Tool call deltas are accumulated in streaming, handled via Complete
                    }
                }
            }

            val response = completeResponse
            if (response == null) {
                // Stream ended without Complete event — emit what we have
                val text = fullText.toString()
                if (text.isNotEmpty()) {
                    history.add(Message(role = "assistant", content = text))
                    trimHistoryByTokens()
                    persistMessage("assistant", text)
                    emit(SessionEvent.Complete(text))
                } else {
                    emit(SessionEvent.Error("No response from model"))
                }
                return@flow
            }

            val toolCalls = response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                // Final text response — apply reflection if configured
                var content = response.content ?: fullText.toString()
                val reflectionConfig = _reflectionConfig
                val lastUserMessage = history.lastOrNull { it.role == "user" }?.content ?: ""

                if (reflectionConfig != null && reflectionConfig.strategy != ReflectionStrategy.NONE && content.isNotBlank()) {
                    Log.d(TAG, "Applying reflection: ${reflectionConfig.strategy} (${reflectionConfig.roles.size} roles)")
                    LogManager.shared.log("INFO", TAG, "[反思] 开始: strategy=${reflectionConfig.strategy}, roles=${reflectionConfig.roles.map { it.name }}")
                    emit(SessionEvent.ReflectionStart(reflectionConfig.roles.firstOrNull()?.name ?: "reflection"))

                    for (role in reflectionConfig.roles) {
                        val reflectionPrompt = role.buildPrompt(lastUserMessage, content)
                        val refinedContent = runReflectionRound(reflectionPrompt)
                        if (refinedContent != null) {
                            content = refinedContent
                            Log.d(TAG, "Reflection round complete for role: ${role.name}")
                            LogManager.shared.log("INFO", TAG, "[反思] 完成: role=${role.name}")
                        } else {
                            Log.w(TAG, "Reflection round failed for role: ${role.name}, keeping previous answer")
                            LogManager.shared.log("WARN", TAG, "[反思] 失败: role=${role.name}, 保留原答案")
                        }
                    }

                    emit(SessionEvent.ReflectionComplete(reflectionConfig.roles.lastOrNull()?.name ?: "reflection"))
                    LogManager.shared.log("INFO", TAG, "[反思] 全部完成, 内容长度=${content.length}")
                }

                history.add(Message(role = "assistant", content = content))
                trimHistoryByTokens()
                persistMessage("assistant", content)
                emit(SessionEvent.Complete(content))
                return@flow
            }

            // Execute tools and continue loop
            // Add assistant message with tool_calls to history (required by API)
            history.add(Message(
                role = "assistant",
                content = "",
                toolCalls = toolCalls
            ))
            for (toolCall in toolCalls) {
                emit(SessionEvent.ToolExecuting(toolCall.function.name))
                val result = executeToolCall(toolCall)
                history.add(Message(
                    role = "tool",
                    content = result,
                    toolCallId = toolCall.id
                ))
                emit(SessionEvent.ToolResult(toolCall.function.name, result))
            }
        }

        emit(SessionEvent.Error("Exceeded max tool rounds"))
    }.flowOn(Dispatchers.Default)

    // ==================== Tool Execution ====================

    private suspend fun executeAndRecordToolCalls(toolCalls: List<ToolCall>) {
        // Add assistant message with tool_calls to history
        // (required for proper multi-turn function calling)
        history.add(Message(
            role = "assistant",
            content = "",
            toolCalls = toolCalls
        ))

        for (toolCall in toolCalls) {
            val toolName = toolCall.function.name
            Log.d(TAG, "Tool call: $toolName, args: ${toolCall.function.arguments}")

            val result = executeToolCall(toolCall)
            history.add(Message(
                role = "tool",
                content = result,
                toolCallId = toolCall.id
            ))
        }
    }

    private suspend fun executeToolCall(toolCall: ToolCall): String {
        return toolExecutionMutex.withLock {
            withContext(Dispatchers.IO) {
                val toolName = toolCall.function.name

                if (toolName.contains("_") && toolName.split("_").size >= 2) {
                    // Skill tool — find matching skill by longest prefix
                    val params = parseToolCallParams(toolCall)
                    val skillId = skillManager.getLoadedSkills().keys
                        .filter { toolName.startsWith("${it}_") }
                        .maxByOrNull { it.length }
                        ?: toolName.substringBefore('_')

                    val permCheck = skillManager.checkSkillPermissions(skillId)
                    if (!permCheck.first) {
                        // Try runtime permission request
                        val permMgr = permissionManager
                        if (permMgr != null) {
                            val requiredPerms = PermissionManager.getPermissionsForSkill(skillId)
                                ?: emptyArray()
                            val displayName = PermissionManager.getSkillDisplayName(skillId)
                            val granted = withContext(Dispatchers.Main) {
                                permMgr.requestPermission(requiredPerms, skillId, displayName)
                            }
                            if (!granted) {
                                return@withContext "需要权限: ${permCheck.second}。请在设置中授权。"
                            }
                        } else {
                            return@withContext "需要权限: ${permCheck.second}。请在设置中授权。"
                        }
                    }

                    val skillResult = skillManager.executeTool(toolName, params)
                    if (skillResult.success) {
                        Log.d(TAG, "Tool $toolName success: ${skillResult.output}")
                        skillResult.output
                    } else {
                        Log.e(TAG, "Tool $toolName failed: ${skillResult.error}")
                        skillResult.error ?: "Skill error"
                    }
                } else {
                    // Accessibility tool
                    Log.d(TAG, "Executing accessibility tool: $toolName")
                    // Fix: Add null check before invoking toolExecutor
                    if (toolExecutor != null) {
                        toolExecutor!!.invoke(toolCall)
                    } else {
                        "Tool executor not set"
                    }
                }
            }
        }
    }

    // ==================== History Management ====================

    private fun buildMessages(): List<Message> {
        // Build base system prompt
        val basePrompt = _agentConfig?.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { customPrompt -> "$customPrompt\n\n---\n$BASE_SYSTEM_PROMPT" }
            ?: BASE_SYSTEM_PROMPT

        // Prepend device capabilities section if available
        val systemPrompt = deviceCapabilities?.let { caps ->
            "${caps.toPromptSection()}\n\n---\n$basePrompt"
        } ?: basePrompt

        return mutableListOf<Message>().apply {
            add(Message(role = "system", content = systemPrompt))
            memoryContextText?.let { context ->
                add(Message(role = "system", content = "用户的重要记忆：\n$context"))
            }
            addAll(history)
        }
    }

    /**
     * Token-aware history trimming.
     * Estimates ~1.3 tokens per CJK character, ~0.25 tokens per ASCII character.
     */
    private fun trimHistoryByTokens() {
        val effectiveMaxTokens = getMaxContextTokens()
        while (estimateTokens(history) > effectiveMaxTokens && history.size > 2) {
            history.removeAt(0)
        }
    }

    /**
     * Estimate token count: CJK ~1.3 tokens/char, ASCII ~4 chars/token
     */
    private fun estimateTokens(messages: List<Message>): Int {
        return messages.sumOf { msg ->
            val cjkCount = msg.content.count { it.code > 0x7F }
            val asciiCount = msg.content.length - cjkCount
            (cjkCount * 1.3 + asciiCount * 0.25).toInt()
        }
    }

    fun clearHistory() {
        history.clear()
    }

    fun getHistory(): List<Message> = history.toList()

    // ==================== Helpers ====================

    private fun convertSkillParams(params: Map<String, SkillParam>): ToolParameters {
        val properties = mutableMapOf<String, ToolProperty>()
        val required = mutableListOf<String>()
        for ((name, param) in params) {
            properties[name] = ToolProperty(type = param.type, description = param.description)
            if (param.required) required.add(name)
        }
        return ToolParameters(type = "object", properties = properties, required = required)
    }

    private fun parseToolCallParams(toolCall: ToolCall): Map<String, Any> {
        return try {
            JSONObject(toolCall.function.arguments).let { json ->
                val map = mutableMapOf<String, Any>()
                for (key in json.keys()) map[key] = json.get(key)
                map
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool params: ${e.message}")
            emptyMap()
        }
    }

    // ==================== Reflection (Multi-round Self-Improvement) ====================

    /**
     * Run a single reflection round: send reflection prompt to model and get refined answer.
     * Returns the refined text, or null if the model failed.
     */
    private suspend fun runReflectionRound(reflectionPrompt: String): String? {
        return try {
            // For reflection, we don't need tools — just pure reasoning
            val reflectionMessages = buildMessagesForReflection(reflectionPrompt)
            val fullText = StringBuilder()
            var completeResponse: ModelResponse? = null

            modelClient.chatStream(reflectionMessages, null).collect { event ->
                when (event) {
                    is ChatEvent.Token -> fullText.append(event.text)
                    is ChatEvent.Complete -> completeResponse = event.response
                    is ChatEvent.Error -> {
                        Log.w(TAG, "Reflection stream error: ${event.message}")
                        return@collect
                    }
                    else -> {}
                }
            }

            completeResponse?.content ?: fullText.toString().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Reflection round failed: ${e.message}", e)
            null
        }
    }

    /**
     * Build messages for reflection: use current history + reflection prompt.
     * The reflection prompt is added as a user message on top of existing history.
     */
    private fun buildMessagesForReflection(reflectionPrompt: String): List<Message> {
        val basePrompt = _agentConfig?.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { customPrompt -> "$customPrompt\n\n---\n$BASE_SYSTEM_PROMPT" }
            ?: BASE_SYSTEM_PROMPT

        val systemPrompt = deviceCapabilities?.let { caps ->
            "${caps.toPromptSection()}\n\n---\n$basePrompt"
        } ?: basePrompt

        return mutableListOf<Message>().apply {
            add(Message(role = "system", content = systemPrompt))
            memoryContextText?.let { context ->
                add(Message(role = "system", content = "用户的重要记忆：\n$context"))
            }
            // Include conversation history so the model has full context
            addAll(history)
            // Add reflection prompt as the last user message
            add(Message(role = "user", content = reflectionPrompt))
        }
    }
}

// ==================== Session Events (for streaming) ====================

sealed class SessionEvent {
    data class Token(val text: String) : SessionEvent()
    data class ToolExecuting(val name: String) : SessionEvent()
    data class ToolResult(val name: String, val result: String) : SessionEvent()
    data class Complete(val fullText: String) : SessionEvent()
    data class Error(val message: String) : SessionEvent()
    /** Reflection phase started */
    data class ReflectionStart(val role: String) : SessionEvent()
    /** Reflection phase completed */
    data class ReflectionComplete(val role: String) : SessionEvent()
}
