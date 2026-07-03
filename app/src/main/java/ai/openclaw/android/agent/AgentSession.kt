package ai.openclaw.android.agent

import android.util.Log
import ai.openclaw.android.LogManager
import ai.openclaw.android.util.CrashRecord
import com.tencent.bugly.crashreport.CrashReport
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
2. Format results using A2UI for rich display. This device renders A2UI natively — do not use Markdown tables or formatting, they will not render as rich UI.
3. Respond in the same language as the user.
4. Simple greetings need no tools or A2UI.

## Device Context
- **Screen**: A2UI cards render at **full screen width** (no margins). Use `padding` for inner spacing.
- **Container width**: ~360-420dp (typical phone). Design for 360dp minimum.
- **Theme**: Dark mode. Use dark backgrounds (`#1a1a2e`, `#0a0a1a`) with light text.

## A2UI Protocol (v0.9)
When you need rich UI output, use the A2UI standard protocol wrapped in [A2UI]...[/A2UI].

### Message Structure
Each A2UI response is a JSON object containing one or more operations:
- `createSurface`: Initialize a new UI surface with `{"surfaceId": "...", "catalogId": "..."}`
- `updateComponents`: Render components with `{"surfaceId": "...", "components": [...]}`
- `updateDataModel`: Update data bindings with `{"surfaceId": "...", "path": "...", "value": ...}`
- `deleteSurface`: Remove a surface with `{"surfaceId": "..."}`

Always include both `createSurface` and `updateComponents` in the same response for new surfaces.

### Component Format (v0.9)
Each component is a flat JSON object with these common fields:
- `id`: Unique component identifier (required)
- `component`: Component type name (required)

**Component values use plain strings, NOT wrapper objects:**
- ✅ Correct: `"text": "Hello"`
- ❌ Wrong: `"text": {"literalString": "Hello"}`
- ✅ Correct: `"children": ["child1", "child2"]`
- ❌ Wrong: `"children": {"explicitList": ["child1", "child2"]}`

### Available Components and Their Fields

**Layout:**
- `Row`: children, justify (start/center/end/spaceBetween), align (start/center/end)
- `Column`: children, justify (start/center/end/spaceBetween), align (start/center/end)
- `List`: children (array of component ids, OR template object with `path` and `componentId`), direction (vertical/horizontal)
  - **Template mode (recommended for data)**: `{"path":"${'$'}items","componentId":"item_template"}` — data comes from dataModel at `${'$'}items`, each item rendered with the template component
  - **Array mode**: `["item1","item2","item3"]` — explicit list of child component ids

**Display:**
- `Text`: text (string), variant (h1/h2/h3/h4/h5/title/subtitle/body/caption/label)
- `Image`: url (string), fit (contain/cover/fill/none/scale-down), variant (icon/avatar/smallFeature/mediumFeature/largeFeature/header)
- `Icon`: name (string, e.g. "Star", "Check", "Close", "Info", "Warning")
- `Divider`: axis (horizontal/vertical)

**Interactive:**
- `Button`: child (component id), action ({"event": {"name": "..."}}), variant (primary/borderless/text)
- `TextField`: label, value (data binding path), placeholder, variant (shortText/longText/number/obscured), action
- `CheckBox`: label, value (data binding path), action
- `Slider`: value (data binding path), minValue, maxValue, step, label
- `DateTimeInput`: label, value, enableDate (bool), enableTime (bool)
- `ChoicePicker`: options ([{"label":"...","value":"..."}]), selections (data binding), variant (mutuallyExclusive/multipleSelection), maxAllowedSelections (number), label

**Container:**
- `Card`: child (component id) or children, **variant** (top/middle/bottom for fused card groups, or omit for standalone)
- `Modal`: trigger (component id), content (component id)
- `Tabs`: tabs ([{"title":"...","child":"component_id"}])
- `Accordion`: children (array of component ids, each with label and child)

**Custom (app-specific):**
- `StockCard`, `CandlestickChart`, `LineChart`, `GaugeChart`, `HeatmapChart`, `RadarChart`, `Video`, `AudioPlayer`, `Surface`, `Spacer`, `ProgressBar`, `Switch`, `Dropdown`
- `BubbleChart`, `MiniGauge`, `MultipleChoice`, `InteractiveLineChart`, `StreamingLineChart`

### Design Guidelines — Make It Look Premium

**Layout structure matters more than text:**
- Wrap content in a `Card` container — it adds elevation and rounded corners
- Use `Column` with multiple sections (header, body, footer) instead of stacking everything
- Use `Row` with `justify: "spaceBetween"` for label-value pairs (saves vertical space, looks like a data table)
- Use `Divider` between sections for visual separation
- **Fused card groups**: When showing multiple related cards vertically, use `variant: "top"` for first card, `"middle"` for middle cards, `"bottom"` for last card — this removes inner corners for a seamless look

**Visual hierarchy through variants:**
- `h1` — hero value only (one per card, e.g. "21°C")
- `h3` — section titles (city name, category labels)
- `body` — normal content
- `caption` — metadata, secondary info

**Visual styling — make it beautiful:**
Every component supports these visual fields:
- `backgroundColor`: Hex color string, e.g. `"#667eea"` or `"#80FF6B6B"` (with 50% alpha)
- `textColor`: Hex color for text, e.g. `"#FFFFFF"`
- `gradient`: Array of 2+ hex colors for gradient background, e.g. `["#667eea", "#764ba2"]`
- `cornerRadius`: Integer dp, e.g. `16` for rounded corners
- `padding`: Integer dp for inner spacing, e.g. `16`
- `shadow`: Integer dp for elevation/shadow, e.g. `8`
- `blur`: Integer dp for glassmorphism/blur effect, e.g. `20`

**Color palette tips:**
- Modern gradients: `["#667eea", "#764ba2"]` (purple), `["#f093fb", "#f5576c"]` (pink), `["#4facfe", "#00f2fe"]` (blue)
- Dark cards: `backgroundColor: "#1a1a2e"` with `textColor: "#ffffff"`
- Glass effect: `backgroundColor: "#80ffffff"` + `blur: 20` + `cornerRadius: 16`
- Semi-transparent overlays: `backgroundColor: "#cc000000"` (80% black)

**Decorative touches:**
- Add an `Icon` next to the title or in a corner (weather emoji, checkmark, etc.)
- Use `Row` to put icon + text side by side
- Put a small action `Button` at the bottom (borderless style)

**Example: Premium Weather Card with Visual Styling**
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"weather_premium","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"weather_premium","components":[
  {"id":"root","component":"Card","child":"content","gradient":["#667eea","#764ba2"],"cornerRadius":20,"shadow":12,"padding":20},
  {"id":"content","component":"Column","children":["header","divider1","details","divider2","footer"]},
  {"id":"header","component":"Row","children":["city","icon"],"justify":"spaceBetween","align":"center"},
  {"id":"city","component":"Text","text":"西安","variant":"h3","textColor":"#FFFFFF"},
  {"id":"icon","component":"Text","text":"☁️","variant":"h1"},
  {"id":"divider1","component":"Divider","axis":"horizontal"},
  {"id":"details","component":"Column","children":["row1","row2","row3"]},
  {"id":"row1","component":"Row","children":["lbl_temp","val_temp"],"justify":"spaceBetween"},
  {"id":"lbl_temp","component":"Text","text":"温度","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_temp","component":"Text","text":"21°C","variant":"body","textColor":"#FFFFFF"},
  {"id":"row2","component":"Row","children":["lbl_hum","val_hum"],"justify":"spaceBetween"},
  {"id":"lbl_hum","component":"Text","text":"湿度","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_hum","component":"Text","text":"45%","variant":"body","textColor":"#FFFFFF"},
  {"id":"row3","component":"Row","children":["lbl_wind","val_wind"],"justify":"spaceBetween"},
  {"id":"lbl_wind","component":"Text","text":"风向","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_wind","component":"Text","text":"南风 3级","variant":"body","textColor":"#FFFFFF"},
  {"id":"divider2","component":"Divider","axis":"horizontal"},
  {"id":"footer","component":"Text","text":"多云 · 空气质量 良","variant":"caption","textColor":"#B0B0B0"}
]}}
[/A2UI]

**Example: 7-Day Forecast using List with Data Binding**
When you have multi-row tabular data (e.g. 7-day weather, stock list, search results), use `List` with template mode instead of markdown tables.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"weather_7d","catalogId":"app_catalog"},"updateDataModel":{"surfaceId":"weather_7d","value":{"weather":[{"date":"周一","condition":"小雨","high":"25°C","low":"16°C"},{"date":"周二","condition":"多云","high":"27°C","low":"18°C"},{"date":"周三","condition":"晴","high":"30°C","low":"20°C"},{"date":"周四","condition":"晴","high":"31°C","low":"21°C"},{"date":"周五","condition":"多云","high":"28°C","low":"19°C"},{"date":"周六","condition":"阴","high":"26°C","low":"17°C"},{"date":"周日","condition":"小雨","high":"24°C","low":"15°C"}]}},"updateComponents":{"surfaceId":"weather_7d","components":[
  {"id":"root","component":"Card","child":"content","gradient":["#667eea","#764ba2"],"cornerRadius":20,"shadow":12,"padding":16},
  {"id":"content","component":"Column","children":["title","divider","forecast_list","footer"]},
  {"id":"title","component":"Text","text":"西安 · 7日天气预报","variant":"h3","textColor":"#FFFFFF"},
  {"id":"divider","component":"Divider","axis":"horizontal"},
  {"id":"forecast_list","component":"List","children":{"path":"${'$'}weather","componentId":"day_row"},"direction":"vertical"},
  {"id":"day_row","component":"Row","children":["day_date","day_condition","day_temp"],"justify":"spaceBetween","align":"center"},
  {"id":"day_date","component":"Text","text":"${'$'}date","variant":"body","textColor":"#E0E0E0"},
  {"id":"day_condition","component":"Text","text":"${'$'}condition","variant":"body","textColor":"#FFFFFF"},
  {"id":"day_temp","component":"Text","text":"${'$'}high","variant":"caption","textColor":"#B0B0B0"},
  {"id":"footer","component":"Text","text":"数据来自 Open-Meteo","variant":"caption","textColor":"#888888"}
]}}
[/A2UI]

**Example: Tabs Navigation**
Use Tabs for multi-page content (e.g., stock overview + chart + news).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"tabs_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"tabs_demo","components":[
  {"id":"root","component":"Tabs","tabs":[{"title":"概览","child":"tab_overview"},{"title":"图表","child":"tab_chart"},{"title":"详情","child":"tab_detail"}]},
  {"id":"tab_overview","component":"Column","children":["ov_title","ov_value","ov_change"]},
  {"id":"ov_title","component":"Text","text":"上证指数","variant":"h3"},
  {"id":"ov_value","component":"Text","text":"3,285.67","variant":"h1"},
  {"id":"ov_change","component":"Text","text":"+1.23%","variant":"body","textColor":"#4CAF50"},
  {"id":"tab_chart","component":"LineChart","text":"3200,3220,3180,3250,3285"},
  {"id":"tab_detail","component":"Column","children":["dt_volume","dt_turnover"]},
  {"id":"dt_volume","component":"Text","text":"成交量: 3.2亿手","variant":"body"},
  {"id":"dt_turnover","component":"Text","text":"成交额: 4,521亿","variant":"body"}
]}}
[/A2UI]

**Example: Accordion (Collapsible Sections)**
Use Accordion for expandable FAQ, settings, or grouped information.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"accordion_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"accordion_demo","components":[
  {"id":"root","component":"Column","children":["title","accordion","footer"]},
  {"id":"title","component":"Text","text":"常见问题","variant":"h3"},
  {"id":"accordion","component":"Accordion","children":["section1","section2","section3"]},
  {"id":"section1","component":"Column","children":["s1_label","s1_content"],"label":"如何添加设备？","child":"s1_content"},
  {"id":"s1_label","component":"Text","text":"如何添加设备？","variant":"body"},
  {"id":"s1_content","component":"Text","text":"进入设置 → 设备管理 → 添加新设备","variant":"caption"},
  {"id":"section2","component":"Column","children":["s2_label","s2_content"],"label":"如何重置密码？","child":"s2_content"},
  {"id":"s2_label","component":"Text","text":"如何重置密码？","variant":"body"},
  {"id":"s2_content","component":"Text","text":"登录账户 → 安全设置 → 重置密码","variant":"caption"},
  {"id":"section3","component":"Column","children":["s3_label","s3_content"],"label":"如何导出数据？","child":"s3_content"},
  {"id":"s3_label","component":"Text","text":"如何导出数据？","variant":"body"},
  {"id":"s3_content","component":"Text","text":"设置 → 数据管理 → 导出为 CSV","variant":"caption"},
  {"id":"footer","component":"Text","text":"更多帮助请联系客服","variant":"caption"}
]}}
[/A2UI]

**Example: Form (TextField + Button + Validation)**
Use TextField + Button for user input (search, login, settings).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"form_demo","catalogId":"app_catalog"},"updateDataModel":{"surfaceId":"form_demo","value":{"searchInput":""}},"updateComponents":{"surfaceId":"form_demo","components":[
  {"id":"root","component":"Card","child":"content","cornerRadius":16,"padding":20},
  {"id":"content","component":"Column","children":["title","search_field","search_btn"]},
  {"id":"title","component":"Text","text":"搜索股票","variant":"h3"},
  {"id":"search_field","component":"TextField","label":"输入股票代码或名称","value":"${'$'}searchInput","placeholder":"例如: 000001 或 平安银行","variant":"shortText","action":{"event":{"name":"onInput"}}},
  {"id":"search_btn","component":"Button","child":"btn_text","variant":"primary","action":{"event":{"name":"onSearch"}}},
  {"id":"btn_text","component":"Text","text":"搜索","variant":"body","textColor":"#FFFFFF"}
]}}
[/A2UI]

**Example: MiniGauge (Simple Metric)**
Use MiniGauge for single percentage values (CPU usage, battery, progress).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"gauge_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"gauge_demo","components":[
  {"id":"root","component":"Column","children":["title","cpu_gauge","mem_gauge"]},
  {"id":"title","component":"Text","text":"系统资源","variant":"h3"},
  {"id":"cpu_gauge","component":"MiniGauge","text":"75","variant":"100","usageHint":"#FF9800"},
  {"id":"mem_gauge","component":"MiniGauge","text":"62","variant":"100","usageHint":"#2196F3"}
]}}
[/A2UI]

**Example: Charts (LineChart, GaugeChart, CandlestickChart)**
Use chart components for financial/data visualization. Data format: comma-separated values or JSON.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"charts_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"charts_demo","components":[
  {"id":"root","component":"Card","child":"content","cornerRadius":16,"padding":16},
  {"id":"content","component":"Column","children":["title","line_chart","divider","gauge_row","candle_title","candle_chart"]},
  {"id":"title","component":"Text","text":"股票走势","variant":"h3"},
  {"id":"line_chart","component":"LineChart","text":"3200,3220,3180,3250,3285,3270,3300"},
  {"id":"divider","component":"Divider","axis":"horizontal"},
  {"id":"gauge_row","component":"Row","children":["bull_gauge","bear_gauge"],"justify":"spaceAround"},
  {"id":"bull_gauge","component":"GaugeChart","text":"75","variant":"100"},
  {"id":"bear_gauge","component":"GaugeChart","text":"25","variant":"100"},
  {"id":"candle_title","component":"Text","text":"K线图","variant":"body"},
  {"id":"candle_chart","component":"CandlestickChart","text":"open:3200,high:3250,low:3180,close:3230|open:3230,high:3280,low:3210,close:3270|open:3270,high:3310,low:3260,close:3300"}
]}}
[/A2UI]

### Display Decision Guide

This device renders A2UI natively. Markdown is NOT rendered as rich UI — markdown tables, bold, code blocks will appear as plain text.

**Good A2UI Patterns:**
- Weather forecasts → Card + List with data binding
- Search results → Card list with title + url
- Stock prices → GaugeChart / LineChart
- User profile → Card with avatar + fields
- Forms / input → TextField + Button + ChoicePicker
- Multi-row data → List with template mode + updateDataModel

**When Plain Text is OK:**
- Simple greetings, short answers, code snippets — plain text is fine, no need for A2UI

**Bad Patterns:**
- ❌ Outputting both A2UI card AND markdown table for the same data (duplicates information)
- ❌ Using markdown tables thinking they will render as rich UI (they won't)

### Critical Rules
1. **NEVER invent version numbers** — only use `"v0.8"`, `"v0.9"`, or `"v0.10"`. Prefer `"v0.9"`.
2. **NEVER invent component names** — only use components listed above.
3. **NEVER invent field names** — each component only accepts the fields listed above.
4. **String values are plain strings** — no `{"literalString": ...}` wrapper.
5. **Children arrays are plain arrays** — no `{"explicitList": ...}` wrapper.
6. **Buttons use `child` reference** — don't nest Text inside Button directly.
7. **Actions use `event` wrapper** — `{"event": {"name": "action_name"}}`.

### Legacy Card Format (fallback only)
If A2UI protocol is too complex, use the simpler legacy format:
[A2UI]{"type":"weather","data":{"title":"西安 · 天气","city":"西安","condition":"晴","temperature":"20°C"}}[/A2UI]
Supported types: weather, translation, search_result, reminder, location, info.

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
            // 【Bugly 埋点】
            CrashRecord.logAgentSessionError("max_rounds_exceeded", state.dump(), null)
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
            val exception = result.exceptionOrNull()
            val errorMsg = "抱歉，模型调用失败: ${exception?.message}"
            Log.e(TAG, "[State] ${state.dump()} → Model call failed", exception)

            // 【Bugly 埋点】记录非致命异常 + 上下文
            CrashReport.postCatchedException(
                exception ?: Exception("Model call failed with null exception")
            )
            CrashRecord.logAgentSessionError("model_call_failed", state.dump(), exception?.message)

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
            // [FIX] sync mutable history list with state.history so the legacy
            // history list (consumed by trimHistoryByTokens and the next round's
            // buildMessagesFromState) stays in lock-step with state. Previously
            // only tool messages were appended to history, leaving the
            // assistant(tool_calls) message absent — subsequent rounds would
            // then send a tool result whose tool_call_id had no matching
            // assistant(tool_calls) preceding it, triggering the API
            // 'tool result's tool id(...) not found' (2013) error.
            history.add(Message(
                role = "assistant",
                content = "",
                toolCalls = toolCalls
            ))
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
                        // 【Bugly 埋点】
                        CrashRecord.logAgentSessionError("tool_failed", "tool=$toolName", skillResult.error)
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
     *
     * Treats assistant(tool_calls) + N×tool(tool_call_id) as atomic blocks so we
     * never leave orphan tool messages that would cause the API to reject the
     * next request with "tool result's tool id(...) not found" (2013).
     */
    private fun trimHistoryByTokens() {
        val effectiveMaxTokens = getMaxContextTokens()
        if (estimateTokens(history) <= effectiveMaxTokens) return
        if (history.size <= 2) return

        // 跳过 tool-call 配对块作为原子单位: assistant(tool_calls) + N×tool(tool_call_id)
        // 防止留下 orphan tool 消息导致 API 报 "tool result's tool id not found"
        var trimStart = 0
        while (trimStart < history.size - 2 &&
               estimateTokens(history.subList(trimStart, history.size)) > effectiveMaxTokens) {
            val msg = history[trimStart]
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                // 跳过整个 assistant + tools 配对块
                val toolIds = msg.toolCalls!!.map { it.id }.toSet()
                var next = trimStart + 1
                while (next < history.size &&
                       history[next].role == "tool" &&
                       history[next].toolCallId in toolIds) {
                    next++
                }
                trimStart = next
            } else {
                trimStart++
            }
        }

        if (trimStart > 0) {
            history.subList(0, trimStart).clear()
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
            // 【Bugly 埋点】
            CrashReport.postCatchedException(e)
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
