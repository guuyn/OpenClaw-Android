package ai.openclaw.android.skill.builtin

import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.bridge.MemoryBridge
import ai.openclaw.script.bridge.MemoryProvider
import ai.openclaw.script.bridge.UiProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/**
 * ScriptEngine 技能 — 将 :script 模块接入 Skill 体系
 *
 * 提供一个 execute_script 工具，LLM 生成 JS 脚本后调用此工具执行。
 */
class ScriptSkill(
    memoryManager: MemoryManager? = null
) : Skill {

    private var orchestrator: ScriptOrchestrator? = null
    private var memoryManager: MemoryManager? = memoryManager

    /** UI Provider，由主工程注入 */
    private var uiProvider: UiProvider? = null

    /**
     * 延迟注入 MemoryManager（在 wireMemoryToSession 之后调用）
     */
    fun setMemoryManager(manager: MemoryManager?) {
        memoryManager = manager
    }

    /**
     * 注入 UiProvider（由主工程在初始化时调用）
     */
    fun setUiProvider(provider: UiProvider?) {
        uiProvider = provider
        orchestrator?.setUiProvider(provider ?: return)
    }

    override val id = "script"
    override val name = "Script Engine"
    override val description = "动态执行 JS 脚本，扩展 Agent 能力"
    override val version = "0.1.0"
    override val instructions = """
# ScriptEngine — 动态脚本执行

当现有 Tool 无法满足复杂的组合任务时，生成 JavaScript 脚本并通过 `execute_script` 执行。脚本在沙箱环境（QuickJS/Rhino）中运行，通过 Bridge API 与 Android 能力交互。

## 何时使用
- ✅ 多 API 数据聚合、数据加工/过滤/排序、动态条件分支、自定义卡片展示、批量操作、读写临时文件、调用记忆系统
- ❌ 简单查询（天气、翻译、搜索）、单个 API 调用 → 用现有 Skill/Tool
- 核心原则：现有 Tool 够用 → 用 Tool；需要组合/加工/条件逻辑 → 用 ScriptEngine

## JS 脚本编写规则

### 语法规范（ES5/ES6 子集）
- ✅ 使用 `var` 声明变量 ❌ 禁止 `let` / `const`
- ✅ 使用 `function` 关键字声明函数 ❌ 禁止箭头函数 `=>`
- ✅ 使用 `for` 循环，字符串拼接用 `+`
- ✅ 三元运算符：`var title = item.title || "无标题"`
- ❌ 不支持 `async/await`、`Promise`（Bridge 调用是同步的）

### 变量注入
通过 `variables` 参数注入为全局变量：`{"QUERY":"天气","LOCATION":"西安"}`
脚本中直接使用：`var url = "https://wttr.in/" + LOCATION;`
常用变量：`QUERY`（搜索词）、`LOCATION`（位置）、`CITY`（城市）、`TEXT`（文本）、`TARGET_LANG`/`SOURCE_LANG`（语言）

### 错误处理
所有外部调用必须用 try-catch：
```javascript
try {
    var resp = http.get("https://api.example.com/data");
    if (resp.status === 200) {
        var data = JSON.parse(resp.body);
        // 处理...
    }
} catch (e) {
    var result = { success: false, error: e.message || String(e) };
}
```

### 返回值格式
脚本最后一行必须返回 `JSON.stringify()` 序列化的 JSON：
```javascript
JSON.stringify({ success: true, data: results });
JSON.stringify({ success: false, error: "连接失败" });
```
返回结构：`{ success: true/false, data?: ..., error?: string, cardRendered?: boolean }`

## Bridge API

### fs（需 capabilities: "fs"）
- `fs.readFile(path)` → `{ content: "...", path: "..." }` | `{ error: "..." }`
- `fs.writeFile(path, content)` → `{ success: true, bytes: 42 }` | `{ error: "..." }`
- `fs.list(dir)` → `{ entries: [{ name, isDirectory, size }] }`
- `fs.exists(path)` → `{ exists: true/false }`
- 路径相对于沙箱目录，不支持 `../` 路径穿越

### http（需 capabilities: "http"，默认超时 10s）
- `http.get(url)` → `{ status: 200, body: "..." }` | `{ error: "..." }`
- `http.post(url, body)` → `{ status: 200, body: "..." }` | `{ error: "..." }`

### memory（需 capabilities: "memory"）
- `memory.recall(query, limit)` → `{ results: [{ content, type, similarity }] }` | `{ error: "..." }`
- `memory.store(content)` → `{ success: true }` | `{ error: "..." }`

### ui（需 capabilities: "ui"）
- `ui.renderCard(cardJson)` → `{ cardId: "msg_card_xxx" }` | `{ error: "..." }`
- `ui.renderToast(message)` → `{ status: "ok" }`
- `ui.showConfirm(title, message)` → `{ result: "confirm"|"cancel" }`

## A2UI 卡片模板

### 通用结构
```json
{
    "type": "卡片类型",
    "data": { "title": "标题", /* 类型特定字段 */ },
    "actions": [{ "label": "按钮文字", "action": "操作标识", "style": "primary|secondary" }]
}
```

### WeatherCard
```json
{ "type": "weather", "data": { "title": "西安 · 天气", "city": "西安", "current": { "icon": "sunny|cloudy|rainy|snowy|stormy|foggy", "condition": "多云", "temperature": "14", "feelsLike": "12", "humidity": "45", "wind": "东南风 3级" }, "forecast": [{ "day": "周二", "icon": "rainy", "condition": "小雨", "high": "16", "low": "11" }], "alert": "建议携带雨具" }, "actions": [{ "label": "📤 分享", "action": "share_weather" }] }
```

### SearchResultCard
```json
{ "type": "search_result", "data": { "title": "搜索结果：关键词", "query": "关键词", "items": [{ "title": "标题", "url": "https://...", "snippet": "摘要", "source": "域名" }], "total": 1280 }, "actions": [{ "label": "🌐 打开网页", "action": "open_browser" }] }
```

### TranslationCard
```json
{ "type": "translation", "data": { "title": "翻译", "sourceText": "原文", "sourceLang": "en", "targetText": "译文", "targetLang": "zh-CN", "pronunciation": "拼音" }, "actions": [{ "label": "🔊 朗读", "action": "speak_target" }, { "label": "📋 复制", "action": "copy_translation" }] }
```

### InfoCard
```json
{ "type": "info", "data": { "title": "标题", "icon": "info|lightbulb|tip", "content": "正文内容", "summary": "一句话摘要" }, "actions": [{ "label": "📋 复制", "action": "copy" }] }
```

### ErrorCard
```json
{ "type": "error", "data": { "icon": "warning|error|info", "title": "错误标题", "message": "错误描述", "suggestion": "建议操作" }, "actions": [{ "label": "🔄 重试", "action": "retry" }] }
```

### SummaryCard
```json
{ "type": "summary", "data": { "title": "标题", "icon": "article", "summary": "摘要", "fullContent": "详细内容" }, "actions": [{ "label": "📖 阅读全文", "action": "expand" }] }
```

## 完整示例

### 天气查询
```javascript
var resp = http.get("https://wttr.in/" + encodeURIComponent(LOCATION) + "?format=j1");
var weather = JSON.parse(resp.body);
var current = weather.current_condition[0];
var card = {
    type: "weather", layout: "weather",
    data: {
        title: LOCATION + " · 天气", city: LOCATION,
        current: {
            icon: current.weatherCode < 10 ? "sunny" : "cloudy",
            condition: current.weatherDesc[0].value,
            temperature: current.temp_C, feelsLike: current.FeelsLikeC,
            humidity: current.humidity, wind: current.windspeedKmph + " km/h"
        }
    },
    actions: [{ "label": "📤 分享", "action": "share_weather" }]
};
ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, cardRendered: true });
```

### 搜索并渲染
```javascript
var url = "https://searx.work/search?q=" + encodeURIComponent(QUERY) + "&format=json";
var resp = http.get(url);
var data = JSON.parse(resp.body);
var items = [];
for (var i = 0; i < Math.min(data.results.length, 5); i++) {
    var r = data.results[i];
    items.push({ title: r.title || "", url: r.url || "", snippet: r.content || "", source: r.url ? r.url.split("/")[2] : "" });
}
var card = { type: "search_result", layout: "list", data: { title: "搜索结果：" + QUERY, items: items, total: data.number_of_results || items.length }, actions: [{ "label": "🌐 打开网页", "action": "open_browser" }] };
ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, count: items.length, cardRendered: true });
```

## 调用 execute_script 的完整参数
```json
{
    "script": "// JS 代码",
    "capabilities": "http,ui,memory,fs",
    "variables": "{\"QUERY\":\"搜索词\",\"LOCATION\":\"城市名\"}"
}
```
capabilities 取值：`fs`（文件操作）、`http`（HTTP 请求）、`memory`（记忆系统）、`ui`（UI 渲染）

## 禁止事项 ⚠️
| 禁止项 | 原因 |
|--------|------|
| `import` / `require` | 无模块系统 |
| `eval` / `new Function` | 代码注入风险 |
| `setTimeout` / `setInterval` | 异步不被支持 |
| `__proto__` / `constructor` | 原型污染 |
| `java.*` / `android.*` / `Packages` | 禁止访问 Java |
| `process` / `global` / `globalThis` / `window` / `document` | 无此环境 |
| 脚本 > 50KB | 性能限制 |
| `../` 路径 | 路径穿越 |

**五条铁律**：
1. 始终用 `var`，不用 `let/const`
2. 始终用 `function`，不用箭头函数
3. 始终用 `JSON.stringify()` 返回结果
4. 始终用 `try-catch` 包裹外部调用
5. 始终通过 `ui.renderCard()` 渲染卡片
    """.trimIndent()

    override val tools = listOf(ExecuteScriptTool())

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
        // 注入之前保存的 UiProvider
        uiProvider?.let { orchestrator?.setUiProvider(it) }
    }

    override fun cleanup() {
        orchestrator = null
    }

    inner class ExecuteScriptTool : SkillTool {
        override val name = "execute_script"
        override val description = "执行一段 JavaScript 脚本并返回结果"
        override val parameters = mapOf(
            "script" to SkillParam("string", "要执行的 JavaScript 代码", true),
            "capabilities" to SkillParam("string", "需要的能力列表，逗号分隔（fs,http,memory,ui）", false, "fs,http"),
            "variables" to SkillParam("string", "全局变量 JSON 对象（如 {\"QUERY\":\"天气\",\"LOCATION\":\"西安\"}）", false, "{}")
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val orch = orchestrator
                ?: return SkillResult(false, "", "ScriptEngine not initialized")

            val script = params["script"] as? String
                ?: return SkillResult(false, "", "Missing 'script' parameter")

            val capsStr = params["capabilities"] as? String ?: "fs,http"
            val capabilities = capsStr.split(",").map { it.trim() }

            // 解析全局变量
            val variablesJson = params["variables"] as? String ?: "{}"
            val variables = parseVariables(variablesJson)

            // 构建 MemoryBridge（如果有 MemoryManager）
            val customBridges = buildMemoryBridge()

            val result = orch.execute(script, capabilities, customBridges, variables)
            return SkillResult(result.success, result.output, result.error)
        }

        private fun buildMemoryBridge(): List<ai.openclaw.script.CapabilityBridge> {
            val mm = memoryManager ?: return emptyList()

            val provider = MemoryProvider { method, argsJson ->
                val json = Json { ignoreUnknownKeys = true }
                val args = json.parseToJsonElement(argsJson).jsonObject

                runBlocking {
                    when (method) {
                        "recall" -> {
                            val query = args["query"]?.jsonPrimitive?.content ?: ""
                            val limit = args["limit"]?.jsonPrimitive?.int ?: 5
                            val results = mm.search(query, limit)
                            val resultArray = results.joinToString(",") { r ->
                                val content = r.memory.content
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                """{"content":"$content","type":"${r.memory.memoryType}","similarity":${r.similarity}}"""
                            }
                            """{"results":[$resultArray]}"""
                        }
                        "store" -> {
                            val content = args["content"]?.jsonPrimitive?.content ?: ""
                            if (content.isBlank()) {
                                """{"error":"content is empty"}"""
                            } else {
                                try {
                                    mm.addManual(content)
                                    """{"success":true}"""
                                } catch (e: Exception) {
                                    val msg = e.message?.replace("\"", "\\\"") ?: "Unknown error"
                                    """{"error":"$msg"}"""
                                }
                            }
                        }
                        else -> """{"error":"Unknown method: $method"}"""
                    }
                }
            }

            return listOf(MemoryBridge(provider))
        }

        /**
         * 解析 variables JSON 字符串为 Map<String, Any>
         * 支持基本类型: string, number, boolean
         * null 值会被跳过（不从 Map 中移除 key）
         */
        private fun parseVariables(jsonStr: String): Map<String, Any> {
            if (jsonStr.isBlank() || jsonStr == "{}") return emptyMap()
            return try {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(jsonStr).jsonObject
                root.mapNotNull { (key, v) ->
                    if (v is kotlinx.serialization.json.JsonNull) return@mapNotNull null
                    val raw = v.jsonPrimitive
                    val parsed: Any = if (raw.isString) {
                        raw.content
                    } else {
                        val text = raw.content
                        when {
                            text == "true" -> true
                            text == "false" -> false
                            else -> text.toDoubleOrNull()?.let { n ->
                                if (n == n.toLong().toDouble()) n.toLong() else n
                            } ?: text
                        }
                    }
                    key to parsed
                }.toMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
