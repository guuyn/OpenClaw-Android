package ai.openclaw.android.agent

import android.util.Log
import ai.openclaw.android.LogManager
import ai.openclaw.android.config.AgentConfig
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.domain.AgentResponse
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.ReflectionConfig
import ai.openclaw.android.domain.ReflectionResult
import ai.openclaw.android.domain.ReflectionRole
import ai.openclaw.android.domain.ReflectionStrategy
import ai.openclaw.android.domain.ReflectionUtils
import kotlinx.coroutines.withTimeoutOrNull
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
        Log.d(TAG, "Reflection config set: strategy=${config.strategy}, timeout=${config.timeoutMs}ms")
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
After receiving tool results, wrap the response using the standard A2UI protocol:
[A2UI]
{
  "version": "v0.10",
  "createSurface": {
    "surfaceId": "unique_surface_id",
    "catalogId": "app_catalog"
  },
  "updateComponents": {
    "surfaceId": "unique_surface_id",
    "components": [
      {
        "id": "root",
        "component": "Card",
        "child": "content_id"
      },
      {
        "id": "content_id",
        "component": "Column",
        "children": {
          "array": ["title_id", "value_id"]
        }
      },
      {
        "id": "title_id",
        "component": "Text",
        "text": {"literalString": "Title"}
      },
      {
        "id": "value_id",
        "component": "Text",
        "text": {"literalString": "Value"}
      }
    ]
  }
}
[/A2UI]

Legacy format is still supported for backward compatibility:
[A2UI]{"type":"weather","data":{"title":"西安 · 天气","city":"西安","condition":"晴","temperature":"20°C","feelsLike":"18°C","humidity":"45%","wind":"南风 3级","forecast":[],"alert":null},"actions":[{"label":"⏰ 降雨提醒","action":"set_rain_reminder","style":"Secondary"}]}[/A2UI]

Available components: Text, Button, Row, Column, TextField, CheckBox, Card, Image, Icon, Divider, Slider, ChoicePicker, List, Tabs, Modal, DateTimeInput, Video, AudioPlayer, Surface, Spacer, ProgressBar, Switch, Dropdown, StockCard, CandlestickChart, LineChart, GaugeChart, MiniGauge, HeatmapChart, RadarChart, BubbleChart, StreamingLineChart, InteractiveLineChart.

## Card Output Guidance
When tool results arrive, output the response in A2UI standard protocol format. Both legacy and standard protocols are supported:

Legacy format (still supported):
[A2UI]{"type":"weather","data":{"title":"西安 · 天气","city":"西安","condition":"晴","temperature":"20°C","feelsLike":"18°C","humidity":"45%","wind":"南风 3级","forecast":[],"alert":null},"actions":[{"label":"⏰ 降雨提醒","action":"set_rain_reminder","style":"Secondary"}]}[/A2UI]

Standard protocol format (preferred):
[A2UI]
{
  "version": "v0.10",
  "createSurface": {
    "surfaceId": "weather_surface_123",
    "catalogId": "weather_catalog"
  },
  "updateComponents": {
    "surfaceId": "weather_surface_123",
    "components": [
      {
        "id": "root",
        "component": "Card",
        "child": "weather_content"
      },
      {
        "id": "weather_content",
        "component": "Column",
        "children": {
          "array": ["city_title", "temp_display", "condition_desc", "details_row"]
        }
      },
      {
        "id": "city_title",
        "component": "Text",
        "text": {"literalString": "西安"},
        "variant": "h3"
      },
      {
        "id": "temp_display",
        "component": "Text",
        "text": {"literalString": "20°C"},
        "variant": "h1"
      },
      {
        "id": "condition_desc",
        "component": "Text",
        "text": {"literalString": "晴"},
        "variant": "body"
      },
      {
        "id": "details_row",
        "component": "Row",
        "children": {
          "array": ["humidity_display", "wind_display"]
        }
      },
      {
        "id": "humidity_display",
        "component": "Text",
        "text": {"literalString": "湿度: 45%"},
        "variant": "caption"
      },
      {
        "id": "wind_display",
        "component": "Text",
        "text": {"literalString": "风向: 南风 3级"},
        "variant": "caption"
      }
    ]
  }
}
[/A2UI]

Available components: Text, Button, Row, Column, TextField, CheckBox, Card, Image, Icon, Divider, Slider, ChoicePicker, List, Tabs, Modal, DateTimeInput, Video, AudioPlayer, Surface, Spacer, ProgressBar, Switch, Dropdown, StockCard, CandlestickChart, LineChart, GaugeChart, MiniGauge, HeatmapChart, RadarChart, BubbleChart, StreamingLineChart, InteractiveLineChart.

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

    suspend fun handleMessage(userMessage: String, images: List<ImageContent>? = null): String {
        history.add(Message(role = "user", content = userMessage, images = images))
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }

        // State machine loop
        var state = AgentState(history = history.toList())
        var error: String? = null

        for (r in 1..MAX_TOOL_ROUNDS) {
            // Step 1: Call LLM (includes building messages)
            val callResult = callLLMStep(state, activeTools)
            if (callResult.first != null) {
                error = callResult.first!!
                break
            }
            state = callResult.second
            Log.d(TAG, "[State] Round $r → ${state.dump()}")

            // Step 2: Check if final answer
            if (!state.needsToolExecution) {
                break
            }

            // Step 3: Execute tools
            state = executeToolsStep(state)
            Log.d(TAG, "[State] After tools → ${state.dump()}")
        }

        // Handle error
        if (error != null) {
            Log.e(TAG, "[State] ERROR → $error")
            return error
        }

        // Force final response if max rounds exceeded
        if (!state.isFinalAnswer) {
            Log.w(TAG, "[State] Max rounds exceeded, forcing final response")
            val messages = buildMessagesInternal(state.history)
            val result = modelClient.chat(messages, null)
            val content = result.getOrDefault(ModelResponse()).content ?: "操作完成"
            state = state.copy(
                history = state.history + Message(role = "assistant", content = content),
                finalContent = content,
                currentToolCalls = null,
                round = state.round + 1
            )
        }

        // Sync state history back to mutable history
        val content = state.finalContent ?: ""
        history.clear()
        history.addAll(state.history)
        trimHistoryByTokens()
        persistMessage("assistant", content)
        return content
    }

    // ==================== State Machine Steps ====================

    /** Call LLM with current state's tools */
    private suspend fun callLLMStep(state: AgentState, activeTools: List<Tool>?): Pair<String?, AgentState> {
        val messages = buildMessagesFromState(state, true)
        val result = modelClient.chat(messages, activeTools)

        if (result.isFailure) {
            val errorMsg = "抱歉，模型调用失败: ${result.exceptionOrNull()?.message}"
            Log.e(TAG, "[State] ${state.dump()} → Model call failed")
            return errorMsg to state
        }

        val response = result.getOrThrow()
        val toolCalls = response.toolCalls

        return if (toolCalls.isNullOrEmpty()) {
            // Final answer
            val content = response.content ?: ""
            val newHistory = state.history + Message(role = "assistant", content = content)
            null to state.copy(
                history = newHistory,
                currentToolCalls = null,
                finalContent = content
            )
        } else {
            // Need tool execution
            val newHistory = state.history + Message(
                role = "assistant",
                content = "",
                toolCalls = toolCalls
            )
            null to state.copy(
                history = newHistory,
                currentToolCalls = toolCalls
            )
        }
    }

    /** Execute pending tool calls and add results to history */
    private suspend fun executeToolsStep(state: AgentState): AgentState {
        val toolCalls = state.currentToolCalls ?: return state
        var newHistory = state.history

        for (toolCall in toolCalls) {
            val toolName = toolCall.function.name
            Log.d(TAG, "[Tool] Executing $toolName, args: ${toolCall.function.arguments}")

            val result = executeToolCall(toolCall)
            Log.d(TAG, "[Tool] $toolName → ${result.take(100)}")

            newHistory += Message(
                role = "tool",
                content = result,
                toolCallId = toolCall.id
            )
        }

        return state.copy(
            history = newHistory,
            currentToolCalls = null
        )
    }

    /** Build messages list from AgentState for LLM call */
    private fun buildMessagesFromState(state: AgentState, includeSystemPrompt: Boolean): List<Message> {
        return if (includeSystemPrompt) {
            buildMessagesInternal(state.history)
        } else {
            state.history
        }
    }

    // ==================== Streaming API ====================

    /**
     * Streaming variant — emits tokens and tool events in real-time.
     * The flow completes with a [SessionEvent.Complete] containing the full text.
     * Uses AgentState for state machine tracking.
     */
    fun handleMessageStream(userMessage: String, images: List<ImageContent>? = null): Flow<SessionEvent> = flow {
        history.add(Message(role = "user", content = userMessage, images = images))
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }

        var state = AgentState(history = history.toList())
        var finalContent: String? = null
        var hasError = false

        for (r in 1..MAX_TOOL_ROUNDS) {
            // Step 1: Build messages
            state = state.copy(round = r)
            Log.d(TAG, "[State] Round $r start → ${state.dump()}")

            // Step 2: Call LLM (streaming)
            val messages = buildMessagesFromState(state, true)
            val fullText = StringBuilder()
            var completeResponse: ModelResponse? = null

            modelClient.chatStream(messages, activeTools).collect { event ->
                when (event) {
                    is ChatEvent.Token -> {
                        fullText.append(event.text)
                        emit(SessionEvent.Token(event.text))
                    }
                    is ChatEvent.Complete -> completeResponse = event.response
                    is ChatEvent.Error -> {
                        emit(SessionEvent.Error(event.message))
                        hasError = true
                        return@collect
                    }
                    is ChatEvent.ToolCallRequested -> {}
                }
            }

            if (hasError) return@flow

            val response = completeResponse
            if (response == null) {
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
                state = state.copy(
                    history = state.history + Message(role = "assistant", content = content),
                    currentToolCalls = null,
                    finalContent = content
                )
                Log.d(TAG, "[State] Final answer → ${state.dump()}")

                // Apply reflection
                content = applyReflection(state, content) { event -> emit(event) }
                finalContent = content
                state = state.copy(
                    history = state.history.dropLast(1) + Message(role = "assistant", content = content),
                    finalContent = content,
                    reflectionApplied = true
                )

                history.add(Message(role = "assistant", content = content))
                trimHistoryByTokens()
                persistMessage("assistant", content)
                emit(SessionEvent.Complete(content))
                return@flow
            }

            // Tool calls — update state and execute
            state = state.copy(
                history = state.history + Message(
                    role = "assistant",
                    content = "",
                    toolCalls = toolCalls
                ),
                currentToolCalls = toolCalls
            )
            Log.d(TAG, "[State] Tool calls → ${state.dump()}")

            // Execute tools
            for (toolCall in toolCalls) {
                emit(SessionEvent.ToolExecuting(toolCall.function.name))
                val result = executeToolCall(toolCall)
                history.add(Message(
                    role = "tool",
                    content = result,
                    toolCallId = toolCall.id
                ))
                state = state.copy(
                    history = state.history + Message(
                        role = "tool",
                        content = result,
                        toolCallId = toolCall.id
                    )
                )
                emit(SessionEvent.ToolResult(toolCall.function.name, result))
            }
            // Clear tool calls from state (they've been executed)
            state = state.copy(currentToolCalls = null)
            Log.d(TAG, "[State] Tools done → ${state.dump()}")
        }

        emit(SessionEvent.Error("Exceeded max tool rounds. Last state: ${state.dump()}"))
    }.flowOn(Dispatchers.Default)

    /** Apply reflection to final content, emit events via callback */
    private suspend fun applyReflection(
        state: AgentState,
        content: String,
        emitEvent: suspend (SessionEvent) -> Unit
    ): String {
        val reflectionConfig = _reflectionConfig
        val lastUserMessage = state.history.lastOrNull { it.role == "user" }?.content ?: ""

        if (reflectionConfig == null || reflectionConfig.strategy == ReflectionStrategy.NONE || content.isBlank()) {
            return content
        }

        Log.d(TAG, "Applying reflection: ${reflectionConfig.strategy}")
        LogManager.shared.log("INFO", TAG, "[反思] 开始: strategy=${reflectionConfig.strategy}")
        emitEvent(SessionEvent.ReflectionStart("reflection"))

        val reflectionResult = runReflectionWithProtection(
            originalContent = content,
            userMessage = lastUserMessage,
            config = reflectionConfig
        )

        if (reflectionResult.changed) {
            val refined = reflectionResult.refinedContent
            Log.d(TAG, "Reflection applied: changeRate=${String.format("%.2f", reflectionResult.changeRate)}, rounds=${reflectionResult.roundsCompleted}")
            LogManager.shared.log("INFO", TAG, "[反思] 已应用: 变化率=${String.format("%.1f", reflectionResult.changeRate * 100)}%, A2UI=${reflectionResult.a2uiPreserved}")
            emitEvent(SessionEvent.ReflectionComplete("reflection"))
            return refined
        } else {
            Log.d(TAG, "Reflection unchanged: keeping original answer")
            LogManager.shared.log("INFO", TAG, "[反思] 无变化，保留原答案")
            emitEvent(SessionEvent.ReflectionComplete("reflection"))
            return content
        }
    }

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
        toolExecutionMutex.lock()
        return try {
            withContext(Dispatchers.IO) {
                val toolName = toolCall.function.name

                // Check if this is an accessibility tool first
                val isAccessibilityTool = accessibilityTools.any { it.function.name == toolName }
                if (!isAccessibilityTool && toolName.contains("_") && toolName.split("_").size >= 2) {
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
        } finally {
            toolExecutionMutex.unlock()
        }
    }

    // ==================== History Management ====================

    private fun buildMessagesInternal(currentHistory: List<Message>): List<Message> {
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
            addAll(currentHistory)
        }
    }

    /** Legacy buildMessages for backward compat (uses mutable history) */
    private fun buildMessages(): List<Message> = buildMessagesInternal(history)

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

    // ==================== Reflection (Protected) ====================

    /**
     * Run reflection with safety guards:
     * - Timeout protection
     * - Empty content rejection (never overwrite good answer with empty string)
     * - A2UI format preservation check
     * - Early termination if change rate < threshold
     */
    private suspend fun runReflectionWithProtection(
        originalContent: String,
        userMessage: String,
        config: ReflectionConfig
    ): ReflectionResult {
        return try {
            val checkpoint = System.currentTimeMillis()
            val reflectionPrompt = ReflectionRole.CHECKER.buildPrompt(userMessage, originalContent)
            val reflectionMessages = buildLightReflectionMessages(reflectionPrompt)

            val fullText = StringBuilder()
            var completeResponse: ModelResponse? = null

            // Run with timeout using kotlinx.coroutines
            withTimeoutOrNull(config.timeoutMs) {
                modelClient.chatStream(reflectionMessages, null).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> fullText.append(event.text)
                        is ChatEvent.Complete -> completeResponse = event.response
                        is ChatEvent.Error -> return@collect
                        else -> {}
                    }
                }
            }

            val refinedContent = completeResponse?.content ?: fullText.toString()

            // Guard 1: reject empty content
            if (refinedContent.isBlank()) {
                Log.w(TAG, "[反思] 返回空内容，保留原答案")
                return ReflectionResult.unchanged(originalContent)
            }

            // Guard 2: A2UI format preservation
            val a2uiPreserved = if (config.protectA2UI) {
                ReflectionUtils.isA2UIPreserved(originalContent, refinedContent)
            } else true

            if (!a2uiPreserved) {
                Log.w(TAG, "[反思] A2UI 格式被破坏，保留原答案")
                return ReflectionResult.unchanged(originalContent)
            }

            // Guard 3: early termination if change < threshold
            val changeRate = ReflectionUtils.changeRate(originalContent, refinedContent)
            if (changeRate < config.minChangeRate) {
                Log.d(TAG, "[反思] 变化率 ${String.format("%.1f", changeRate * 100)}% < 阈值，早停")
                return ReflectionResult.unchanged(originalContent)
            }

            val elapsed = System.currentTimeMillis() - checkpoint
            Log.d(TAG, "[反思] 完成: ${String.format("%.1f", changeRate * 100)}% 变化, 耗时 ${elapsed}ms")

            ReflectionResult(
                refinedContent = refinedContent,
                changed = true,
                changeRate = changeRate,
                roundsCompleted = 1,
                a2uiPreserved = a2uiPreserved
            )
        } catch (e: Exception) {
            Log.e(TAG, "[反思] 异常: ${e.message}", e)
            ReflectionResult.unchanged(originalContent)
        }
    }

    /**
     * Build lightweight reflection messages: only send the original answer + reflection prompt.
     * Don't include full conversation history to save tokens.
     */
    private fun buildLightReflectionMessages(reflectionPrompt: String): List<Message> {
        val basePrompt = _agentConfig?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: BASE_SYSTEM_PROMPT

        return listOf(
            Message(role = "system", content = basePrompt),
            Message(role = "user", content = reflectionPrompt)
        )
    }
}

// ==================== AgentState (immutable, for debugging & logging) ====================

/**
 * AgentState — immutable snapshot of the agent's conversation state.
 * Each tool-calling round produces a new state via copy().
 * 
 * Benefits:
 * - Full state dump on error for quick debugging
 * - No mutable variable sprawl
 * - Easy to trace round-by-round in logs
 */
data class AgentState(
    val history: List<Message> = emptyList(),
    val currentToolCalls: List<ToolCall>? = null,
    val round: Int = 0,
    val a2uiResponse: String? = null,
    val reflectionApplied: Boolean = false,
    val finalContent: String? = null
) {
    val isFinalAnswer: Boolean get() = currentToolCalls == null && finalContent != null
    val needsToolExecution: Boolean get() = !currentToolCalls.isNullOrEmpty()

    /** Full state dump for debugging — call when errors occur */
    fun dump(): String = buildString {
        append("AgentState(")
        append("round=$round, ")
        append("historySize=${history.size}, ")
        append("toolCalls=${currentToolCalls?.map { it.function.name } ?: "null"}, ")
        append("a2ui=${a2uiResponse != null}, ")
        append("reflectionApplied=$reflectionApplied, ")
        append("finalContent=${finalContent?.take(30)}, ")
        append("isFinalAnswer=$isFinalAnswer")
        append(")")
    }
}

/** Tool execution result (internal use, different from SessionEvent.ToolResult) */
data class AgentToolResult(val name: String, val result: String)

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
