# MultiSearchSkill Redesign

## 背景

当前 `MultiSearchSkill` 存在以下问题：

1. **手写 JSON 解析** — 用字符串匹配 (`indexOf`, `Regex`) 解析 SearXNG 响应，脆弱且难维护
2. **硬编码实例** — 仅 2 个 SearXNG 实例，failover 逻辑写在 Kotlin 中
3. **未利用已有库** — 项目已有 `kotlinx-serialization-json`，但搜索技能完全没用
4. **纯文本结果** — 搜索结果只返回文本，未利用 A2UI 富 UI 渲染能力

## 目标

- 搜索逻辑脚本化（JS），支持热更新
- 利用 `JSON.parse` 在 JS 中完成解析，消除手写解析器
- 支持多 SearXNG 实例自动 failover
- 搜索结果通过 A2UI 协议渲染为可点击卡片
- 同时服务 Agent 自主搜索和用户主动搜索两种场景

## 架构

```
MultiSearchSkill (Kotlin 壳)
  ├── initialize(): 加载 assets/scripts/search.js + 创建 ScriptOrchestrator
  ├── SearchTool.execute():
  │     ├── 注入参数: var QUERY = "xxx"
  │     ├── orchestrator.execute(script, ["http"])
  │     ├── 用 kotlinx-serialization-json 解析 JS 返回
  │     └── 用 [A2UI] 标签包装搜索结果
  └── cleanup(): 释放 orchestrator

search.js (JavaScript 搜索逻辑)
  ├── SearXNG 实例列表（JS 数组，易于修改）
  ├── 遍历实例列表，自动 failover
  ├── JSON.parse 解析 SearXNG JSON 响应
  └── 返回 {success, results: [{title, snippet, url}]}
```

## search.js 脚本

脚本存放在 `app/src/main/assets/scripts/search.js`，由 `ScriptOrchestrator` 在沙箱中执行，通过 `HttpBridge` 发起 HTTP 请求。

```javascript
var INSTANCES = [
  "https://searx.work",
  "https://searxng.no-logs.com",
  "https://search.bus-hit.me"
];

function search(query) {
  for (var i = 0; i < INSTANCES.length; i++) {
    var url = INSTANCES[i] + "/search?q=" +
              encodeURIComponent(query) + "&format=json";
    var resp = http.get(url);

    if (resp.status === 200) {
      try {
        var data = JSON.parse(resp.body);
        var results = [];
        var items = data.results || [];
        for (var j = 0; j < Math.min(items.length, 5); j++) {
          results.push({
            title: items[j].title || "",
            snippet: items[j].content || "",
            url: items[j].url || ""
          });
        }
        if (results.length > 0) {
          return JSON.stringify({success: true, results: results});
        }
      } catch (e) {
        // 当前实例解析失败，继续尝试下一个
      }
    }
  }
  return JSON.stringify({success: false, error: "所有搜索实例均不可用"});
}

search(QUERY);
```

## MultiSearchSkill Kotlin 改造

### 删除的代码

- `parseSearXNGResponse()` — 手写 JSON 解析方法
- `extractJsonValue()` — 正则提取 JSON 字段
- `shortenUrl()` — URL 截断方法
- `SearchResult` data class — 不再需要 Kotlin 侧的数据类
- `setHttpClient()` — 改由 ScriptOrchestrator 的 HttpBridge 处理

### 新的 Kotlin 代码结构

```kotlin
class MultiSearchSkill : Skill {
    override val id = "search"
    override val name = "多引擎搜索"
    override val description = "使用 SearXNG 搜索互联网信息"
    override val version = "2.0.0"

    override val instructions = """
# Multi-Search Skill
使用 SearXNG 进行搜索，无需 API Key。
## 用法
- 用户要求搜索信息时，调用 search 工具
- 返回搜索结果摘要与卡片展示
"""

    private var orchestrator: ScriptOrchestrator? = null
    private var scriptContent: String? = null

    override val tools: List<SkillTool> = listOf(SearchTool())

    private inner class SearchTool : SkillTool {
        override val name = "search"
        override val description = "搜索互联网信息"
        override val parameters = mapOf(
            "query" to SkillParam("string", "搜索关键词", true)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val query = params["query"] as? String
            if (query.isNullOrBlank()) {
                return SkillResult(false, "", "缺少 query 参数")
            }

            val orch = orchestrator ?: return SkillResult(false, "", "引擎未初始化")
            val script = scriptContent ?: return SkillResult(false, "", "脚本未加载")

            val fullScript = "var QUERY = ${Json.encodeToString(query)};\n$script"
            val result = orch.execute(fullScript, listOf("http"))

            if (!result.success) {
                return SkillResult(false, "", result.error ?: "执行失败")
            }

            // 用 kotlinx-serialization 解析 JS 返回的 JSON
            val json = Json.parseToJsonElement(result.output).jsonObject
            val success = json["success"]?.jsonPrimitive?.boolean ?: false

            if (!success) {
                val error = json["error"]?.jsonPrimitive?.content ?: "搜索失败"
                return SkillResult(false, "", error)
            }

            // 构建纯文本摘要（供 LLM 理解）
            val resultsArray = json["results"]?.jsonArray ?: emptyList()
            val textSummary = buildTextSummary(query, resultsArray)

            // 构建 A2UI 卡片（供 UI 渲染）
            val a2ui = buildA2UICard(query, resultsArray)

            return SkillResult(true, "$textSummary\n\n$a2ui")
        }

        private fun buildTextSummary(query: String, results: List<JsonElement>): String {
            val sb = StringBuilder("搜索 \"$query\" 的结果:\n\n")
            results.forEachIndexed { i, elem ->
                val obj = elem.jsonObject
                sb.append("${i + 1}. ${obj["title"]?.jsonPrimitive?.content ?: ""}\n")
                val snippet = obj["snippet"]?.jsonPrimitive?.content ?: ""
                if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                sb.append("\n")
            }
            return sb.toString()
        }

        private fun buildA2UICard(query: String, results: List<JsonElement>): String {
            val data = buildJsonObject {
                put("query", query)
                putArray("results") {
                    results.forEach { elem ->
                        val obj = elem.jsonObject
                        add(buildJsonObject {
                            put("title", obj["title"]?.jsonPrimitive?.content ?: "")
                            put("snippet", obj["snippet"]?.jsonPrimitive?.content ?: "")
                            put("url", obj["url"]?.jsonPrimitive?.content ?: "")
                        })
                    }
                }
            }
            return "[A2UI]\n{\"type\":\"search\",\"data\":$data}\n[/A2UI]"
        }
    }

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
        scriptContent = context.applicationContext.assets
            .open("scripts/search.js")
            .bufferedReader()
            .use { it.readText() }
    }

    override fun cleanup() {
        orchestrator = null
        scriptContent = null
    }
}
```

## A2UI 搜索结果渲染

### A2UI 消息格式

```json
{
  "type": "search",
  "data": {
    "query": "Kotlin",
    "results": [
      {
        "title": "Kotlin Programming Language",
        "snippet": "A modern programming language...",
        "url": "https://kotlinlang.org"
      }
    ]
  }
}
```

嵌入 `[A2UI]...[/A2UI]` 标签中，由 `ChatScreen` 解析并渲染为卡片列表。

### 渲染组件

需要注册 `search` 类型的 A2UI 组件渲染器，使用 `Card` + `Text` 组合展示搜索结果卡片。每条结果包含：
- 标题（粗体文本）
- 摘要（普通文本）
- 来源域名（caption 样式，可点击打开 URL）

## 双场景支持

### Agent 自主搜索
- LLM 调用 `search_search` 工具
- 获取纯文本摘要部分用于推理
- A2UI 标签被 AgentSession 透传给 ChatScreen

### 用户主动搜索
- 用户说"帮我搜一下..."
- AgentSession 调用搜索工具
- ChatScreen 解析 A2UI 标签渲染卡片
- 用户可点击卡片中的链接

## 涉及文件

| 操作 | 文件 |
|------|------|
| 重写 | `app/src/main/java/ai/openclaw/android/skill/builtin/MultiSearchSkill.kt` |
| 新建 | `app/src/main/assets/scripts/search.js` |
| 可能修改 | A2UI 组件注册（如需新增 `search` 类型渲染器） |

## 不在范围内

- 搜索历史/缓存
- 图片搜索
- 自定义搜索引擎选择 UI
- 搜索结果分页加载
