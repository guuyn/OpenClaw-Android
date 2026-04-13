# 火山引擎联网搜索集成设计

## 背景

当前 `MultiSearchSkill` 使用 SearXNG 公共实例搜索。用户已申请到火山方舟 (Ark) 平台的 API Key，可以调用其 `web_search` 工具获得更稳定的搜索结果。

## 目标

- 将火山引擎 Ark Responses API 的 `web_search` 作为优先搜索后端
- SearXNG 作为 fallback（当 API Key 未配置或调用失败时）
- 保持脚本化模式（JS 脚本执行搜索逻辑）
- API Key 通过 ConfigManager 加密存储

## 架构

```
MultiSearchSkill.kt (改动: initialize 注入 API Key 变量)
  └── 注入: QUERY, VOLCENGINE_API_KEY, VOLCENGINE_MODEL

search.js (改造: 双后端搜索)
  ├── 优先: 火山引擎 Ark Responses API
  │     POST https://ark.cn-beijing.volces.com/api/v3/responses
  │     tools: [{type: "web_search"}]
  │     解析响应提取搜索结果
  └── Fallback: SearXNG 多实例 (现有逻辑不变)
```

## 火山引擎 Ark API 调用

### 请求格式

```
POST https://ark.cn-beijing.volces.com/api/v3/responses
Authorization: Bearer $VOLCENGINE_API_KEY
Content-Type: application/json

{
  "model": "doubao-seed-1-6-250615",
  "stream": false,
  "tools": [{"type": "web_search"}],
  "input": [
    {
      "role": "user",
      "content": [{"type": "input_text", "text": "搜索关键词"}]
    }
  ]
}
```

### 响应解析

Ark Responses API 返回 JSON，包含 `output` 数组。搜索结果在 `type: "web_search_call"` 事件中，包含搜索到的页面信息。

具体字段需通过实际调试确认。预期结构类似：
```json
{
  "output": [
    {
      "type": "web_search_call",
      "status": "completed",
      "results": [
        {"title": "...", "content": "...", "url": "..."}
      ]
    },
    {
      "type": "message",
      "content": [{"type": "output_text", "text": "搜索总结文本"}]
    }
  ]
}
```

注意：响应结构以实际 API 返回为准，search.js 中需要做防御性解析。

## search.js 改造

```javascript
var INSTANCES = ["https://searx.work", "https://searxng.no-logs.com"];
var MODEL = VOLCENGINE_MODEL || "doubao-seed-1-6-250615";

function searchVolcengine(query) {
    if (!VOLCENGINE_API_KEY) return null;
    var url = "https://ark.cn-beijing.volces.com/api/v3/responses";
    var body = JSON.stringify({
        model: MODEL,
        stream: false,
        tools: [{type: "web_search"}],
        input: [{role: "user", content: [{type: "input_text", text: query}]}]
    });
    try {
        var resp = http.post(url, body);
        if (resp.status === 200 && resp.body) {
            var data = JSON.parse(resp.body);
            var results = [];
            // 解析 output 数组，提取 web_search_call 中的结果
            var output = data.output || [];
            for (var i = 0; i < output.length; i++) {
                if (output[i].type === "web_search_call" && output[i].results) {
                    var items = output[i].results;
                    for (var j = 0; j < Math.min(items.length, 5); j++) {
                        results.push({
                            title: items[j].title || "",
                            snippet: items[j].content || items[j].snippet || "",
                            url: items[j].url || ""
                        });
                    }
                }
            }
            if (results.length > 0) return results;
        }
    } catch (e) {
        // 火山引擎失败，降级到 SearXNG
    }
    return null;
}

function searchSearXNG(query) {
    for (var i = 0; i < INSTANCES.length; i++) {
        var url = INSTANCES[i] + "/search?q=" +
                  encodeURIComponent(query) + "&format=json";
        try {
            var resp = http.get(url);
            if (resp.status === 200 && resp.body) {
                var data = JSON.parse(resp.body);
                var items = data.results || [];
                var results = [];
                for (var j = 0; j < Math.min(items.length, 5); j++) {
                    results.push({
                        title: items[j].title || "",
                        snippet: items[j].content || "",
                        url: items[j].url || ""
                    });
                }
                if (results.length > 0) return results;
            }
        } catch (e) {
            // 当前实例失败，继续
        }
    }
    return null;
}

// 优先火山引擎，失败降级 SearXNG
var results = searchVolcengine(QUERY);
if (!results) results = searchSearXNG(QUERY);

if (results && results.length > 0) {
    JSON.stringify({success: true, results: results});
} else {
    JSON.stringify({success: false, error: "所有搜索实例均不可用"});
}
```

## MultiSearchSkill.kt 改动

只修改 `initialize()` 方法，注入火山引擎相关变量：

```kotlin
override fun initialize(context: SkillContext) {
    orchestrator = ScriptOrchestrator(context.applicationContext)
    scriptContent = context.applicationContext.assets
        .open("scripts/search.js")
        .bufferedReader()
        .use { it.readText() }
}

// SearchTool.execute() 中修改 fullScript 构建:
val volcApiKey = ConfigManager.getVolcengineApiKey()
val volcModel = ConfigManager.getVolcengineModel()
val fullScript = buildString {
    append("var QUERY = ${Json.encodeToString(query)};\n")
    if (volcApiKey.isNotEmpty()) {
        append("var VOLCENGINE_API_KEY = ${Json.encodeToString(volcApiKey)};\n")
    } else {
        append("var VOLCENGINE_API_KEY = \"\";\n")
    }
    if (volcModel.isNotEmpty()) {
        append("var VOLCENGINE_MODEL = ${Json.encodeToString(volcModel)};\n")
    }
    append(script)
}
```

需要新增 import: `ai.openclaw.android.ConfigManager`

## ConfigManager 改动

新增两个配置项：

```kotlin
private const val KEY_VOLCENGINE_API_KEY = "volcengine_api_key"
private const val KEY_VOLCENGINE_MODEL = "volcengine_model"

fun getVolcengineApiKey(): String {
    return secretPrefs.getString(KEY_VOLCENGINE_API_KEY, "") ?: ""
}

fun setVolcengineApiKey(apiKey: String) {
    secretPrefs.edit().putString(KEY_VOLCENGINE_API_KEY, apiKey).apply()
}

fun getVolcengineModel(): String {
    return prefs.getString(KEY_VOLCENGINE_MODEL, "") ?: ""
}

fun setVolcengineModel(model: String) {
    prefs.edit().putString(KEY_VOLCENGINE_MODEL, model).apply()
}
```

## 测试

### MultiSearchSkillTest 更新

- 测试用例中 mock 的 search.js 脚本需要加入 `VOLCENGINE_API_KEY` 变量
- 新增测试: API Key 为空时 fallback 到 SearXNG
- 新增测试: API Key 有值时脚本注入包含 VOLCENGINE_API_KEY

### 手动测试

- 配置火山引擎 API Key 后搜索，验证结果
- 不配置 API Key 时搜索，验证 SearXNG fallback 正常

## 涉及文件

| 操作 | 文件 |
|------|------|
| 修改 | `app/src/main/assets/scripts/search.js` |
| 修改 | `app/src/main/java/ai/openclaw/android/ConfigManager.kt` |
| 修改 | `app/src/main/java/ai/openclaw/android/skill/builtin/MultiSearchSkill.kt` |
| 修改 | `app/src/test/java/ai/openclaw/android/skill/builtin/MultiSearchSkillTest.kt` |

## 不在范围内

- 设置界面 UI（API Key 配置入口）
- 流式搜索结果
- 搜索历史/缓存
- 自定义搜索域限制
