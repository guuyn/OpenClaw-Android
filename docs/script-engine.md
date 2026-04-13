# ScriptEngine 模块设计文档

> 版本：0.2.0 | 日期：2026-04-13 | 状态：原型阶段（新增 UiBridge）

> **v0.2.0 更新**: 新增 UiBridge，支持 JS 脚本动态渲染 A2UI 卡片。

## 1. 概述

ScriptEngine 是 OpenClaw Android 的动态脚本执行模块，独立为 `:script` Android Library Module。

**核心定位**：运行时动态生成的 Tool。LLM 根据用户任务生成 JS 脚本，沙箱执行后返回结果。

**动态卡片**：通过 `ui.renderCard()` Bridge，JS 脚本可以动态构建并渲染 A2UI 卡片，实现任意复杂的数据展示。

## 2. 架构

```
LLM 生成 JS 脚本
       │
       ▼
ScriptOrchestrator（入口）
       │
       ├── ScriptValidator ──→ 静态校验（禁止 import/require/eval）
       │
       ├── ScriptEngine ──→ Rhino JS 引擎（原型）/ QuickJS JNI（正式）
       │     │
       │     └── CapabilityBridge（JS → Android 能力桥接）
       │           ├── FileBridge     → fs.readFile/writeFile/list/exists
       │           ├── HttpBridge     → http.get/post
       │           ├── MemoryBridge   → memory.recall/store（接口，主工程实现）
       │           └── UiBridge       → ui.renderCard/renderToast/showConfirm（主工程实现）
       │
       └── SandboxPolicy ──→ 超时/内存/路径限制
```

## 3. 模块结构

```
script/src/main/java/ai/openclaw.script/
├── CapabilityBridge.kt    # 桥接接口 + handleMethod 默认实现
├── SandboxPolicy.kt       # 安全策略数据类
├── ScriptResult.kt        # 执行结果数据类
├── ScriptValidator.kt     # 静态校验器
├── ScriptEngine.kt        # JS 执行引擎（Rhino 原型）
├── ScriptOrchestrator.kt  # 编排器：校验 → 注册 Bridge → 执行
└── bridge/
    ├── FileBridge.kt      # 文件操作（沙箱目录隔离）
    ├── HttpBridge.kt      # HTTP 请求（OkHttp）
    ├── MemoryBridge.kt    # 记忆接口 + MemoryProvider 回调
    └── UiBridge.kt        # UI 渲染（A2UI 卡片 + Toast + 确认弹窗）
```

## 4. 核心接口

### 4.1 CapabilityBridge

```kotlin
interface CapabilityBridge {
    val name: String
    fun getJsPrototype(): String
    fun handleMethod(method: String, argsJson: String): String
}
```

### 4.2 ScriptOrchestrator（入口）

```kotlin
class ScriptOrchestrator(context: Context) {
    fun execute(
        script: String,
        capabilities: List<String> = emptyList(),
        customBridges: List<CapabilityBridge> = emptyList()
    ): ScriptResult
}
```

### 4.3 MemoryBridge（回调模式）

```kotlin
class MemoryBridge(private val provider: MemoryProvider) : CapabilityBridge

fun interface MemoryProvider {
    suspend fun execute(method: String, args: String): String
}
```

### 4.4 UiBridge（卡片渲染）

```kotlin
/**
 * UiBridge — JS 脚本渲染 A2UI 卡片的桥接接口
 * 
 * 主工程提供 UiProvider 实现，将卡片 JSON 传递给 ChatScreen 渲染。
 * 脚本只能调用 renderCard，不能直接操作 UI 组件（安全隔离）。
 */
class UiBridge(private val provider: UiProvider) : CapabilityBridge {
    override val name = "ui"
    
    override fun getJsPrototype(): String = """
        var ui = {
            renderCard: function(cardJson) { return uiBridge('renderCard', cardJson); },
            renderToast: function(message) { return uiBridge('renderToast', message); },
            showConfirm: function(title, message) { return uiBridge('showConfirm', JSON.stringify({title: title, message: message})); }
        };
    """
}

/**
 * 主工程需实现的回调接口
 */
interface UiProvider {
    /** 渲染 A2UI 卡片 — 返回卡片 ID 供后续交互 */
    suspend fun renderCard(cardJson: String): String
    
    /** 显示 Toast 提示 — 返回 "ok" */
    suspend fun renderToast(message: String): String
    
    /** 显示确认弹窗 — 返回 "confirm" 或 "cancel" */
    suspend fun showConfirm(title: String, message: String): String
}
```

**与 A2UI 卡片系统的关系**:

```
ScriptEngine JS 脚本
    │
    │  ui.renderCard(JSON.stringify({
    │    type: "weather",
    │    data: { city: "西安", ... },
    │    actions: [{ label: "详情", action: "expand" }]
    │  }))
    │
    ▼
UiBridge → UiProvider.renderCard(cardJson)
    │
    ▼
ChatScreen A2UI 渲染器
    │
    ▼
WeatherCard / SearchResultCard / ... （Compose 组件）
```

**安全约束**:
- ✅ 只能调用 `renderCard`、`renderToast`、`showConfirm` 三个方法
- ✅ 卡片类型必须是预定义的（`weather` / `search_result` / `translation` 等）
- ❌ 不能直接操作 Compose 组件或 View
- ❌ 不能调用 `alert()`、`prompt()` 等 JS 原生弹窗
- ❌ 不能注入 HTML/JSX（只允许 JSON 数据）

## 5. 安全设计

### 5.1 静态校验（ScriptValidator）
- 禁止 `import`、`require`、`eval`、`new Function`
- 禁止访问 `java.`、`android.`、`Packages`、`process`、`global`、`window`、`document`
- 脚本长度上限 50KB

### 5.2 运行时沙箱（ScriptEngine）
- Rhino `initSafeStandardObjects()` 初始化
- 删除 `Packages`、`java`、`javax`、`org`、`com` 等全局对象
- 单次执行超时 10s（可配置）
- 每次执行创建独立 Context + 独立线程，执行完销毁

### 5.3 文件沙箱（FileBridge）
- 所有文件操作限制在 `script_sandbox/` 目录
- 路径穿越检测：`canonicalPath` 校验
- `../` 被拦截

## 6. JS API 参考

```javascript
// 文件操作
fs.readFile("notes.txt")       // → {content: "...", path: "notes.txt"}
fs.writeFile("out.json", data) // → {success: true, bytes: 42}
fs.list(".")                   // → {entries: [{name, isDirectory, size}]}
fs.exists("config.json")      // → {exists: true}

// HTTP 请求
http.get("https://api.example.com/data")
  // → {status: 200, body: "..."}
http.post(url, JSON.stringify(payload))
  // → {status: 200, body: "..."}

// 记忆系统（需要主工程提供 MemoryProvider）
memory.recall("上次的话题", 5)
  // → {results: [{content, similarity, type}]}
memory.store("今天学了新东西")
  // → {success: true}

// UI 渲染（需要主工程提供 UiProvider）
ui.renderCard(JSON.stringify({
  type: "weather",
  data: { city: "西安", current: { temperature: "14", condition: "多云" } },
  actions: [{ label: "详情", action: "expand" }]
}))
// → {cardId: "msg_card_weather_123"}

ui.renderToast("操作成功")
// → {status: "ok"}

ui.showConfirm("确认删除", "此操作不可撤销")
// → {result: "confirm"} 或 {result: "cancel"}
```

## 6.1 动态卡片示例

**场景**: 用户问"对比一下西安和北京的天气"

```javascript
// LLM 生成的脚本
var xiAn = JSON.parse(http.get("https://api.weather.com/xian").body);
var beiJing = JSON.parse(http.get("https://api.weather.com/beijing").body);

var card = {
  type: "info",
  data: {
    title: "天气对比",
    content: xiAn.city + " " + xiAn.current.temp + "°C vs " +
             beiJing.city + " " + beiJing.current.temp + "°C"
  },
  actions: [{ label: "查看详情", action: "expand" }]
};

ui.renderCard(JSON.stringify(card));
```

## 7. 测试覆盖

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| ScriptValidatorTest | 22 | 正常脚本 + 空脚本 + 长度限制 + 18种危险模式 |
| ScriptEngineTest | 16 | 算术/字符串/数组/JSON + 控制流 + Bridge + 安全 + 超时 |
| FileBridgeTest | 16 | 读写文件 + 目录 + 存在性 + 路径穿越 + JSON工具 |
| ScriptResultTest | 6 | 数据类工厂方法 |
| SandboxPolicyTest | 2 | 默认值 + 自定义值 |

## 8. 依赖

| 依赖 | 用途 | 版本 |
|------|------|------|
| Rhino | JS 执行引擎（原型） | 1.7.15 |
| OkHttp | HTTP Bridge | 4.12.0 |
| kotlinx-coroutines | 异步支持 | 1.10.1 |

**正式版替换**：Rhino → QuickJS JNI（体积从 ~2MB 降至 ~500KB，性能提升 10x+）

## 9. 主工程集成

### 9.1 依赖

`app/build.gradle.kts` 添加：
```kotlin
implementation(project(":script"))
```

### 9.2 ScriptSkill

文件：`app/.../skill/builtin/ScriptSkill.kt`

- 实现 `Skill` 接口，注册到 `SkillManager`
- 提供一个 `execute_script` 工具
- LLM 通过调用 `script_execute_script` 触发脚本执行
- `ScriptOrchestrator` 在 `initialize()` 时创建

### 9.3 SkillManager 注册

```kotlin
registerSkill(ScriptSkill())
```

### 9.4 LLM 工具名

`script_execute_script`

参数：
- `script` (string, required) — JS 脚本代码
- `capabilities` (string, optional, default "fs,http") — 能力列表

---

## 10. 后续计划

### P0
- [ ] 替换 Rhino 为 QuickJS JNI
- [ ] **UiBridge 实现**（A2UI 卡片渲染 Bridge）
- [ ] 脚本缓存/复用机制

### P1
- [ ] LLM Prompt 模板（教 LLM 生成正确的 JS 脚本 + 卡片 JSON）
- [ ] 添加更多 Bridge（CalendarBridge、ContactBridge）
- [ ] 卡片交互回调（按钮点击 → 脚本继续执行）

### P2
- [ ] 脚本市场（用户分享/下载常用脚本）
- [ ] 脚本版本管理 + 回滚
- [ ] 性能分析（脚本执行耗时、内存占用）

---

## 10. 与 A2UI 卡片系统的集成

> **详见**: [A2UI 手机卡片系统 v2.0](./a2ui-card-system-v2.md)

ScriptEngine 是**动态卡片生成器**，与静态 Skill 卡片形成互补：

| 维度 | 静态 Skill 卡片 | ScriptEngine 动态卡片 |
|------|---------------|---------------------|
| **来源** | Kotlin 硬编码 | LLM 生成 JS 脚本 |
| **灵活性** | 固定格式 | 任意组合数据 |
| **复杂度** | 简单查询 | 多 API 聚合、数据加工 |
| **示例** | 查询单个城市天气 | 对比 3 个城市天气 + 生成图表 |
| **渲染** | 同一种渲染器 | 同一种渲染器 |

**渲染通路统一**：无论是 Skill 返回的卡片还是 ScriptEngine 生成的卡片，最终都走同一个 A2UI 渲染器，UI 层无感知。
