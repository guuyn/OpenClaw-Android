# ScriptEngine LLM Prompt 模板

> 本文档用于指导 LLM 如何生成正确的 JS 脚本和 A2UI 卡片 JSON。
> 可直接作为 `ScriptSkill` 的 `instructions` 字段使用，或作为 instructions 的增强补充。

---

## 概述

ScriptEngine 是 OpenClaw Android 的动态脚本执行模块。当现有 Tool 无法满足复杂的组合任务时，你可以生成 JavaScript 脚本并通过 `execute_script` 工具执行。脚本在沙箱环境中运行（QuickJS/Rhino），通过 Bridge API 与 Android 能力交互。

---

## 1. 何时使用 ScriptEngine

### 决策指南

| 场景 | 推荐方案 | 原因 |
|------|---------|------|
| 简单查询（天气、翻译、搜索） | 使用现有 Skill/Tool | 已有工具更可靠 |
| 单个 API 调用 | 使用现有 Skill/Tool | 已有工具更可靠 |
| **多 API 数据聚合** | ✅ ScriptEngine | 需要组合多个数据源 |
| **数据加工/过滤/排序** | ✅ ScriptEngine | 灵活的数据处理 |
| **动态条件分支** | ✅ ScriptEngine | if/for 逻辑 |
| **自定义卡片展示** | ✅ ScriptEngine | 自由构建 A2UI JSON |
| **批量操作** | ✅ ScriptEngine | 循环处理 |
| 需要读写临时文件 | ✅ ScriptEngine | 沙箱文件系统 |
| 需要调用记忆系统 | ✅ ScriptEngine | memory.recall/store |

**核心原则：现有 Tool 够用 → 用 Tool；需要组合/加工/条件逻辑 → 用 ScriptEngine。**

---

## 2. JS 脚本编写规则

### 2.1 语法规范

**使用 ES5 / ES6 子集**：

```javascript
// ✅ 正确 — 使用 var
var url = "https://api.example.com";
var data = JSON.parse(resp.body);
var items = data.results;

// ❌ 错误 — 不要使用 let / const
let url = "https://api.example.com";    // 禁止
const MAX = 10;                          // 禁止

// ✅ 正确 — 使用 function 关键字
function search(query) {
    // ...
}

// ❌ 错误 — 不要使用箭头函数
const search = (query) => { ... };       // 禁止

// ✅ 正确 — 使用 for 循环
for (var i = 0; i < items.length; i++) {
    results.push(items[i]);
}

// ✅ 正确 — 字符串拼接
var url = "https://api.example.com/" + id;
var msg = "找到 " + count + " 条结果";

// ✅ 正确 — 三元运算符
var title = item.title || "无标题";
```

### 2.2 变量注入机制

脚本中需要的动态值（用户输入、位置信息等）通过 `variables` 参数注入为全局变量：

```javascript
// 调用时通过 variables 参数注入: {"QUERY":"天气","LOCATION":"西安"}
// 脚本中直接使用全局变量名

var url = "https://wttr.in/" + LOCATION + "?format=3";
var query = QUERY;  // 用户输入的搜索词
```

**常用变量名约定**：

| 变量名 | 含义 | 示例值 |
|--------|------|--------|
| `QUERY` | 用户搜索/查询内容 | `"OpenClaw"` |
| `LOCATION` | 地理位置 | `"西安"` |
| `TARGET_LANG` | 目标语言 | `"zh-CN"` |
| `SOURCE_LANG` | 源语言 | `"en"` |
| `TEXT` | 要处理的文本 | `"Hello world"` |
| `CITY` | 城市名 | `"北京"` |

### 2.3 错误处理

所有外部调用（网络请求、文件读取）必须用 try-catch：

```javascript
try {
    var resp = http.get("https://api.example.com/data");
    if (resp.status === 200) {
        var data = JSON.parse(resp.body);
        // 处理数据...
    } else {
        // 处理 HTTP 错误
        var result = { success: false, error: "HTTP " + resp.status };
    }
} catch (e) {
    // 处理网络异常或解析错误
    var result = { success: false, error: e.message || String(e) };
}

// 脚本最后一行必须返回结果（JSON.stringify）
JSON.stringify(result);
```

### 2.4 返回值格式

脚本的返回值必须是 `JSON.stringify()` 序列化的 JSON 字符串：

```javascript
// ✅ 正确
JSON.stringify({ success: true, data: results });
JSON.stringify({ success: false, error: "连接失败" });

// ❌ 错误 — 返回非 JSON 字符串
"操作完成";
return "ok";
```

**返回结构约定**：

```javascript
{
    "success": true/false,     // 必需：执行是否成功
    "data": ...,               // 成功时：结果数据
    "error": "错误描述",        // 失败时：错误信息
    "cardRendered": true       // 可选：已通过 ui.renderCard 渲染了卡片
}
```

---

## 3. Bridge API 使用示例

### 3.1 文件操作（fs）

> 需启用 `capabilities: "fs"`
> 所有路径相对于沙箱目录，支持子目录

#### fs.readFile(path) — 读取文件

```javascript
var result = fs.readFile("config.json");
if (result.error) {
    // 文件不存在或读取失败
    JSON.stringify({ success: false, error: result.error });
} else {
    var config = JSON.parse(result.content);
    JSON.stringify({ success: true, config: config });
}
```

**返回**：`{ content: "...", path: "..." }` 或 `{ error: "..." }`

#### fs.writeFile(path, content) — 写入文件

```javascript
var data = JSON.stringify({ query: QUERY, timestamp: Date.now() });
var result = fs.writeFile("search_history.json", data);

if (result.success) {
    JSON.stringify({ success: true, bytes: result.bytes });
} else {
    JSON.stringify({ success: false, error: result.error });
}
```

**返回**：`{ success: true, bytes: 42 }` 或 `{ error: "..." }`

#### fs.list(dir) — 列出目录

```javascript
var result = fs.list(".");
var files = result.entries;  // [{name, isDirectory, size}, ...]

var fileNames = [];
for (var i = 0; i < files.length; i++) {
    if (!files[i].isDirectory) {
        fileNames.push(files[i].name);
    }
}
JSON.stringify({ success: true, files: fileNames });
```

**返回**：`{ entries: [{ name: "file.txt", isDirectory: false, size: 1024 }, ...] }`

#### fs.exists(path) — 检查文件/目录是否存在

```javascript
var result = fs.exists("notes.txt");
if (result.exists) {
    var content = fs.readFile("notes.txt");
    JSON.stringify({ success: true, content: content.content });
} else {
    JSON.stringify({ success: true, exists: false });
}
```

**返回**：`{ exists: true }` 或 `{ exists: false }`

### 3.2 HTTP 请求（http）

> 需启用 `capabilities: "http"`
> 默认超时 10 秒

#### http.get(url) — GET 请求

```javascript
var url = "https://api.example.com/weather?city=" + encodeURIComponent(LOCATION);
var resp = http.get(url);

if (resp.status === 200) {
    var data = JSON.parse(resp.body);
    JSON.stringify({ success: true, data: data });
} else {
    JSON.stringify({ success: false, error: "HTTP " + resp.status });
}
```

**返回**：`{ status: 200, body: "..." }` 或 `{ error: "..." }`

#### http.post(url, body) — POST 请求

```javascript
var url = "https://api.example.com/translate";
var payload = JSON.stringify({
    text: TEXT,
    source: SOURCE_LANG,
    target: TARGET_LANG
});

var resp = http.post(url, payload);

if (resp.status === 200) {
    var result = JSON.parse(resp.body);
    JSON.stringify({ success: true, translation: result.translatedText });
} else {
    JSON.stringify({ success: false, error: "HTTP " + resp.status });
}
```

**返回**：`{ status: 200, body: "..." }` 或 `{ error: "..." }`

### 3.3 记忆系统（memory）

> 需启用 `capabilities: "memory"`
> 由主工程的 MemoryManager 提供实现

#### memory.recall(query, limit) — 回忆

```javascript
var result = memory.recall("上次讨论的项目", 3);
var items = result.results;  // [{content, type, similarity}, ...]

var summaries = [];
for (var i = 0; i < items.length; i++) {
    summaries.push(items[i].content);
}
JSON.stringify({ success: true, memories: summaries });
```

**参数**：
- `query` (string) — 搜索查询
- `limit` (number, 默认 5) — 返回数量上限

**返回**：`{ results: [{ content: "...", type: "manual", similarity: 0.85 }, ...] }` 或 `{ error: "..." }`

#### memory.store(content) — 存储

```javascript
var note = "用户偏好：喜欢简洁的天气卡片，不需要详细预报";
var result = memory.store(note);

if (result.success) {
    JSON.stringify({ success: true, message: "已存储到记忆" });
} else {
    JSON.stringify({ success: false, error: result.error });
}
```

**返回**：`{ success: true }` 或 `{ error: "..." }`

### 3.4 UI 渲染（ui）

> 需启用 `capabilities: "ui"`
> 由主工程的 UiProvider 提供渲染能力

#### ui.renderCard(cardJson) — 渲染 A2UI 卡片

```javascript
var card = {
    type: "info",
    data: {
        title: "脚本执行结果",
        content: "成功处理了 42 条数据"
    },
    actions: [
        { "label": "📋 复制", "action": "copy" }
    ]
};

var result = ui.renderCard(JSON.stringify(card));
// result → { cardId: "msg_card_xxx" }

JSON.stringify({ success: true, cardId: result.cardId });
```

**参数**：A2UI 卡片 JSON 字符串（见第 4 节的卡片模板）
**返回**：`{ cardId: "msg_card_xxx" }` 或 `{ error: "..." }`

#### ui.renderToast(message) — 显示 Toast 提示

```javascript
var result = ui.renderToast("数据已保存到本地");
// result → { status: "ok" }

JSON.stringify({ success: true });
```

**返回**：`{ status: "ok" }` 或 `{ error: "..." }`

#### ui.showConfirm(title, message) — 显示确认弹窗

```javascript
var result = ui.showConfirm("确认删除", "此操作将删除所有缓存数据，不可恢复");

if (result.result === "confirm") {
    // 用户点击了确认
    JSON.stringify({ success: true, confirmed: true });
} else {
    // 用户取消了
    JSON.stringify({ success: true, confirmed: false });
}
```

**返回**：`{ result: "confirm" }` 或 `{ result: "cancel" }`

---

## 4. A2UI 卡片 JSON 模板

### 4.1 卡片通用结构

**所有卡片都遵循统一的四段式结构**：

```jsonc
{
    "type": "卡片类型",         // 必需：weather / search_result / translation / ...
    "layout": "布局变体",       // 可选：与 type 相同的值即可
    "data": {                  // 必需：卡片数据
        "title": "标题",        // 卡片标题（建议包含）
        // ... 类型特定字段
    },
    "actions": [               // 可选：操作按钮
        { "label": "按钮文字", "action": "操作标识", "style": "primary|secondary" }
    ]
}
```

### 4.2 WeatherCard — 天气卡片

```jsonc
{
    "type": "weather",
    "layout": "weather",
    "data": {
        "title": "西安 · 天气",
        "city": "西安",
        "current": {
            "icon": "cloudy",          // sunny / cloudy / rainy / snowy / stormy / foggy
            "condition": "多云",
            "temperature": "14",       // 字符串
            "feelsLike": "12",
            "humidity": "45",
            "wind": "东南风 3级"
        },
        "forecast": [
            { "day": "周二", "icon": "rainy", "condition": "小雨", "high": "16", "low": "11" },
            { "day": "周三", "icon": "sunny", "condition": "晴", "high": "18", "low": "10" }
        ],
        "alert": "今天傍晚有阵雨，建议携带雨具"  // 可选
    },
    "actions": [
        { "label": "⏰ 降雨提醒", "action": "set_rain_reminder" },
        { "label": "📅 7天预报", "action": "expand_forecast" },
        { "label": "📤 分享", "action": "share_weather" }
    ]
}
```

**JS 脚本示例**：

```javascript
var resp = http.get("https://wttr.in/" + encodeURIComponent(LOCATION) + "?format=j1");
var weather = JSON.parse(resp.body);

var current = weather.current_condition[0];
var card = {
    type: "weather",
    layout: "weather",
    data: {
        title: LOCATION + " · 天气",
        city: LOCATION,
        current: {
            icon: current.weatherCode < 10 ? "sunny" : current.weatherCode < 50 ? "cloudy" : "rainy",
            condition: current.weatherDesc[0].value,
            temperature: current.temp_C,
            feelsLike: current.FeelsLikeC,
            humidity: current.humidity,
            wind: current.windspeedKmph + " km/h"
        },
        forecast: []
    },
    actions: [
        { "label": "📤 分享", "action": "share_weather" }
    ]
};

ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, cardRendered: true });
```

### 4.3 SearchResultCard — 搜索结果卡片

```jsonc
{
    "type": "search_result",
    "layout": "list",
    "data": {
        "title": "搜索结果：Android 16 新特性",
        "query": "Android 16 新特性",
        "items": [
            {
                "title": "Android 16 开发者预览版发布",
                "url": "https://developer.android.com/about/versions/16",
                "snippet": "Android 16 引入了...",
                "source": "developer.android.com"
            }
        ],
        "total": 1280,
        "time": "0.32 秒"
    },
    "actions": [
        { "label": "🌐 网页浏览", "action": "open_browser" },
        { "label": "📋 复制摘要", "action": "copy_summary" }
    ]
}
```

**JS 脚本示例**：

```javascript
var url = "https://searx.work/search?q=" + encodeURIComponent(QUERY) + "&format=json";
var resp = http.get(url);
var data = JSON.parse(resp.body);

var items = [];
for (var i = 0; i < Math.min(data.results.length, 5); i++) {
    var r = data.results[i];
    items.push({
        title: r.title || "",
        url: r.url || "",
        snippet: r.content || "",
        source: r.url ? r.url.split("/")[2] : ""
    });
}

var card = {
    type: "search_result",
    layout: "list",
    data: {
        title: "搜索结果：" + QUERY,
        query: QUERY,
        items: items,
        total: data.number_of_results || items.length
    },
    actions: [
        { "label": "🌐 打开网页", "action": "open_browser" }
    ]
};

ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, count: items.length, cardRendered: true });
```

### 4.4 TranslationCard — 翻译卡片

```jsonc
{
    "type": "translation",
    "layout": "translation",
    "data": {
        "title": "翻译",
        "sourceText": "Hello, how are you today?",
        "sourceLang": "en",
        "targetText": "你好，你今天怎么样？",
        "targetLang": "zh-CN",
        "pronunciation": "nǐ hǎo, nǐ jīn tiān zěn me yàng?"
    },
    "actions": [
        { "label": "🔊 朗读", "action": "speak_target" },
        { "label": "📋 复制", "action": "copy_translation" }
    ]
}
```

### 4.5 InfoCard — 通用信息卡片

```jsonc
{
    "type": "info",
    "layout": "info",
    "data": {
        "title": "关于 OpenClaw",
        "icon": "info",              // info / lightbulb / tip
        "content": "OpenClaw 是一个 AI Agent 框架，支持多种模型的动态脚本执行...",
        "summary": "一句话摘要"        // 可选：首行摘要
    },
    "actions": [
        { "label": "📋 复制全文", "action": "copy" },
        { "label": "🔊 朗读", "action": "speak" }
    ]
}
```

**JS 脚本示例**：

```javascript
var card = {
    type: "info",
    layout: "info",
    data: {
        title: "脚本执行摘要",
        icon: "lightbulb",
        content: "共处理了 3 个数据源，汇总了 42 条有效信息。"
    },
    actions: [
        { "label": "📋 复制", "action": "copy" }
    ]
};

ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, cardRendered: true });
```

### 4.6 ErrorCard — 错误卡片

```jsonc
{
    "type": "error",
    "layout": "error",
    "data": {
        "icon": "warning",          // warning / error / info
        "title": "无法获取数据",
        "message": "网络连接失败，请检查网络设置后重试",
        "suggestion": "您可以尝试切换网络或稍后再试"
    },
    "actions": [
        { "label": "🔄 重试", "action": "retry" }
    ]
}
```

**JS 脚本示例**：

```javascript
try {
    var resp = http.get("https://api.example.com/data");
    if (resp.status !== 200) {
        throw new Error("HTTP " + resp.status);
    }
    // 正常处理...
} catch (e) {
    var errorCard = {
        type: "error",
        layout: "error",
        data: {
            icon: "error",
            title: "获取数据失败",
            message: e.message || String(e),
            suggestion: "请检查网络连接后重试"
        },
        actions: [
            { "label": "🔄 重试", "action": "retry" }
        ]
    };
    ui.renderCard(JSON.stringify(errorCard));
    JSON.stringify({ success: false, error: e.message, cardRendered: true });
}
```

### 4.7 SummaryCard — 长内容摘要卡片

```jsonc
{
    "type": "summary",
    "layout": "summary",
    "data": {
        "title": "AI 发展趋势分析",
        "icon": "article",
        "summary": "2026年AI发展主要集中在多模态、Agent和本地推理三个方向...",
        "fullContent": "详细内容全文，展开后可见..."
    },
    "actions": [
        { "label": "📖 阅读全文", "action": "expand" },
        { "label": "📋 复制", "action": "copy" }
    ]
}
```

---

## 5. 禁止事项 ⚠️

以下模式会导致脚本被 **ScriptValidator 直接拒绝执行**：

| 禁止项 | 示例 | 原因 |
|--------|------|------|
| **import** | `import x from 'y'` | 无模块系统 |
| **require** | `require('fs')` | 无模块系统 |
| **eval** | `eval("2+2")` | 代码注入风险 |
| **new Function** | `new Function("return 1")` | 代码注入风险 |
| **setTimeout** | `setTimeout(fn, 1000)` | 异步不被支持 |
| **setInterval** | `setInterval(fn, 1000)` | 异步不被支持 |
| **__proto__** | `obj.__proto__` | 原型污染 |
| **constructor** | `obj.constructor[...]` | 原型污染 |
| **java.*** | `java.lang.System` | 禁止访问 Java |
| **android.*** | `android.os.Build` | 禁止访问 Android |
| **Packages** | `Packages.java.util` | 禁止访问 Java 包 |
| **process** | `process.exit()` | 禁止访问进程 |
| **global** | `global.xxx` | 禁止访问全局对象 |
| **globalThis** | `globalThis.xxx` | 禁止访问全局对象 |
| **window** | `window.location` | 无浏览器环境 |
| **document** | `document.getElementById` | 无 DOM |
| **脚本过长** | > 50KB | 性能限制 |

**重要提醒**：
- 使用 `var` 声明变量，不要用 `let` 或 `const`
- 使用 `function` 声明函数，不要用箭头函数 `=>`
- 不支持 `async/await` 或 `Promise`（Bridge 调用是同步的）
- 文件路径不能包含 `../`（路径穿越检测）
- 不要尝试访问 `console.log`（无控制台输出）

---

## 6. 完整示例

### 示例 1：多城市天气对比

**用户需求**：对比西安和北京今天的天气

**LLM 生成脚本**：

```javascript
// 天气对比脚本 — 调用 wttr.in 获取两个城市的天气
// 需注入 variables: {"CITY1":"西安","CITY2":"北京"}

var cities = [CITY1, CITY2];
var results = [];

for (var i = 0; i < cities.length; i++) {
    var city = cities[i];
    var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=j1";

    try {
        var resp = http.get(url);
        if (resp.status === 200) {
            var data = JSON.parse(resp.body);
            var current = data.current_condition[0];
            results.push({
                city: city,
                condition: current.weatherDesc[0].value,
                temperature: current.temp_C,
                feelsLike: current.FeelsLikeC,
                humidity: current.humidity
            });
        } else {
            results.push({ city: city, error: "HTTP " + resp.status });
        }
    } catch (e) {
        results.push({ city: city, error: e.message || String(e) });
    }
}

// 构建对比卡片
var content = "";
for (var j = 0; j < results.length; j++) {
    var r = results[j];
    if (r.error) {
        content += r.city + ": " + r.error + "\n";
    } else {
        content += r.city + ": " + r.condition + " " + r.temperature + "°C (体感 " + r.feelsLike + "°C) 湿度 " + r.humidity + "%\n";
    }
}

var card = {
    type: "info",
    layout: "info",
    data: {
        title: CITY1 + " vs " + CITY2 + " · 天气对比",
        icon: "info",
        content: content
    },
    actions: [
        { "label": "📤 分享", "action": "share" }
    ]
};

ui.renderCard(JSON.stringify(card));
JSON.stringify({ success: true, cities: results, cardRendered: true });
```

**调用参数**：
```json
{
    "script": "<上面生成的脚本>",
    "capabilities": "http,ui",
    "variables": "{\"CITY1\":\"西安\",\"CITY2\":\"北京\"}"
}
```

### 示例 2：搜索并存储结果

**用户需求**：搜索"OpenClaw 最新功能"，把结果存到记忆

**LLM 生成脚本**：

```javascript
// 搜索并存储脚本
// 需注入 variables: {"QUERY":"OpenClaw 最新功能"}

var url = "https://searx.work/search?q=" + encodeURIComponent(QUERY) + "&format=json";

try {
    var resp = http.get(url);

    if (resp.status !== 200) {
        var errCard = {
            type: "error",
            layout: "error",
            data: {
                icon: "error",
                title: "搜索失败",
                message: "HTTP " + resp.status
            },
            actions: [
                { "label": "🔄 重试", "action": "retry" }
            ]
        };
        ui.renderCard(JSON.stringify(errCard));
        JSON.stringify({ success: false, error: "HTTP " + resp.status });
    }

    var data = JSON.parse(resp.body);
    var items = data.results || [];
    var topResults = [];

    for (var i = 0; i < Math.min(items.length, 5); i++) {
        topResults.push({
            title: items[i].title,
            snippet: items[i].content || "",
            url: items[i].url || ""
        });
    }

    // 渲染搜索结果卡片
    var searchCard = {
        type: "search_result",
        layout: "list",
        data: {
            title: "搜索结果：" + QUERY,
            query: QUERY,
            items: topResults,
            total: data.number_of_results || topResults.length
        },
        actions: [
            { "label": "🌐 打开网页", "action": "open_browser" },
            { "label": "📋 复制", "action": "copy" }
        ]
    };

    ui.renderCard(JSON.stringify(searchCard));

    // 将摘要存储到记忆
    var summary = QUERY + " 搜索结果: " + topResults.length + " 条。" +
        "第一条: " + topResults[0].title + " — " + topResults[0].snippet;
    var memResult = memory.store(summary);

    JSON.stringify({
        success: true,
        count: topResults.length,
        stored: memResult.success,
        cardRendered: true
    });

} catch (e) {
    JSON.stringify({ success: false, error: e.message || String(e) });
}
```

**调用参数**：
```json
{
    "script": "<上面生成的脚本>",
    "capabilities": "http,ui,memory",
    "variables": "{\"QUERY\":\"OpenClaw 最新功能\"}"
}
```

### 示例 3：读取配置文件并生成报告

**用户需求**：读取本地的 notes.txt，统计字数并生成摘要

**LLM 生成脚本**：

```javascript
// 文件读取 + 统计脚本

// 检查文件是否存在
var exists = fs.exists("notes.txt");
if (!exists.exists) {
    var errCard = {
        type: "error",
        layout: "error",
        data: {
            icon: "warning",
            title: "文件不存在",
            message": "notes.txt 不存在于沙箱目录中"
        },
        actions: []
    };
    ui.renderCard(JSON.stringify(errCard));
    JSON.stringify({ success: false, error: "文件不存在", cardRendered: true });
}

// 读取文件内容
var fileResult = fs.readFile("notes.txt");
if (fileResult.error) {
    JSON.stringify({ success: false, error: fileResult.error });
}

var content = fileResult.content;
var charCount = content.length;
var lineCount = 0;

// 统计行数
for (var i = 0; i < content.length; i++) {
    if (content[i] === "\n") {
        lineCount++;
    }
}
lineCount++;  // 最后一行可能没有换行符

// 生成报告卡片
var reportCard = {
    type: "info",
    layout: "info",
    data: {
        title": "文件统计报告",
        icon: "lightbulb",
        content": "文件: notes.txt\n字符数: " + charCount + "\n行数: " + lineCount + "\n\n前100字符预览:\n" + content.substring(0, 100) + (content.length > 100 ? "..." : "")
    },
    actions: [
        { "label": "📋 复制全文", "action": "copy" }
    ]
};

ui.renderCard(JSON.stringify(reportCard));
JSON.stringify({
    success: true,
    file: "notes.txt",
    charCount: charCount,
    lineCount: lineCount,
    cardRendered: true
});
```

**调用参数**：
```json
{
    "script": "<上面生成的脚本>",
    "capabilities": "fs,ui",
    "variables": "{}"
}
```

---

## 7. 快速参考卡片

### 调用 `execute_script` 的完整参数格式

```json
{
    "script": "// 你的 JS 脚本代码\nvar result = http.get('https://api.example.com');\nJSON.stringify({ success: true });",
    "capabilities": "http,ui,memory,fs",
    "variables": "{\"QUERY\":\"搜索词\",\"LOCATION\":\"城市名\"}"
}
```

### capabilities 取值

| 值 | 提供的 API | 说明 |
|----|-----------|------|
| `fs` | `fs.readFile/writeFile/list/exists` | 文件操作 |
| `http` | `http.get/post` | HTTP 请求 |
| `memory` | `memory.recall/store` | 记忆系统 |
| `ui` | `ui.renderCard/renderToast/showConfirm` | UI 渲染 |

### 常见 API 返回值速查

```
fs.readFile(path)      → { content: "...", path: "..." } | { error: "..." }
fs.writeFile(p, c)     → { success: true, bytes: 42 }    | { error: "..." }
fs.list(dir)           → { entries: [{name, isDir, size}] }
fs.exists(path)        → { exists: true/false }

http.get(url)          → { status: 200, body: "..." }    | { error: "..." }
http.post(url, body)   → { status: 200, body: "..." }    | { error: "..." }

memory.recall(q, n)    → { results: [{content, type, similarity}] } | { error }
memory.store(c)        → { success: true }               | { error: "..." }

ui.renderCard(json)    → { cardId: "msg_card_xxx" }      | { error: "..." }
ui.renderToast(msg)    → { status: "ok" }                | { error: "..." }
ui.showConfirm(t, m)   → { result: "confirm|cancel" }    | { error: "..." }
```

---

> **最后提醒**：
> 1. 始终用 `var`，不要用 `let/const`
> 2. 始终用 `function`，不要用箭头函数
> 3. 始终用 `JSON.stringify()` 返回结果
> 4. 始终用 `try-catch` 包裹外部调用
> 5. 始终通过 `ui.renderCard()` 渲染卡片，不要直接操作 UI
