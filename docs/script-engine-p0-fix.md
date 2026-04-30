# ScriptEngine P0 修复方案

> 版本：0.2.1 | 日期：2026-04-30 | 状态：待实施

## 问题总览

| # | 问题 | 严重度 | 状态 |
|---|------|--------|------|
| P0-1 | QuickJS 依赖版本不存在 | 🔴 阻塞编译 | 待修复 |
| P0-2 | UiBridge 完全缺失 | 🔴 功能缺失 | 待实现 |
| P0-3 | 全局变量注入未实现 | 🔴 示例脚本无法运行 | 待实现 |

---

## P0-1: QuickJS 依赖版本修复

### 问题分析

**当前配置**（`script/build.gradle.kts`）：
```kotlin
implementation("io.github.dokar3:quickjs-kt-android:1.0.4")
```

**Maven Central 实际情况**（已验证）：
- `io.github.dokar3:quickjs-kt`（纯 Kotlin/JVM）→ 最新版本 `1.0.0-alpha13`
- `io.github.dokar3:quickjs-kt-android`（Android AAR）→ 最新版本 `1.0.0-alpha13`
- **版本 `1.0.4` 不存在**，Gradle 解析会失败

**结论**：quickjs-kt 仍处于 alpha 阶段，没有 `1.0.x` 正式版本。当前最新且唯一可用的版本是 `1.0.0-alpha13`。

### 修复方案

将依赖版本改为实际存在的版本：

```kotlin
// script/build.gradle.kts
// QuickJS 引擎（Alpha 版）
implementation("io.github.dokar3:quickjs-kt-android:1.0.0-alpha13")
```

### 风险评估

| 风险项 | 评估 |
|--------|------|
| Alpha 版稳定性 | quickjs-kt 已有 13 个版本迭代，API 基本稳定 |
| Android 兼容性 | `quickjs-kt-android` 专为 Android 编译，含 JNI 库 |
| API 兼容性 | 当前代码使用的 `define{}`、`function`、`evaluate` API 在 alpha13 中已存在 |
| ProGuard | QuickJS JNI 需要 keep 规则（见下方） |

### ProGuard 规则

在 `app/proguard-rules.pro` 中添加（若尚未添加）：

```proguard
# QuickJS
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class * {
    @com.dokar.quickjs.binding.annotation.* <methods>;
}
```

### 替代方案评估（如 quickjs-kt 不可用）

| 方案 | 优势 | 劣势 | 结论 |
|------|------|------|------|
| 纯 Rhino（降级） | 无额外依赖，已验证可用 | 体积大（~2MB），性能差，安全性弱 | ✅ 已作为 fallback 保留 |
| 直接 JNI QuickJS | 完全控制 | 需要自己维护 JNI 代码 | ❌ 工作量过大 |
| Nashorn | JDK 内置 | Android 不可用，已废弃 | ❌ 不可行 |
| quickjs-kt | 轻量（~500KB）、性能好、Kotlin DSL | Alpha 版 | ✅ 推荐方案 |

**推荐**：使用 `quickjs-kt-android:1.0.0-alpha13`，Rhino 保留为 fallback。

---

## P0-2: UiBridge 实现

### 问题分析

设计文档定义了 `UiBridge` 用于 JS 脚本渲染 A2UI 卡片，但代码目录中不存在 `UiBridge.kt` 文件。
`resolveBridges()` 中也缺少 `"ui"` capability 的处理。

### 架构设计

```
JS 脚本 (QuickJS/Rhino)
    │
    │  ui.renderCard(cardJson)
    │  ui.renderToast(message)
    │  ui.showConfirm(title, message)
    ▼
UiBridge (script module)
    │
    │  UiProvider 回调
    ▼
主工程实现 (app module)
    │
    │  实际 UI 渲染逻辑
    ▼
ChatScreen A2UI 渲染器
```

### 实现文件

#### 1. UiBridge.kt（新建）

**位置**: `script/src/main/java/ai/openclaw/script/bridge/UiBridge.kt`

**设计决策**：

| 维度 | 选择 | 原因 |
|------|------|------|
| UiProvider 方法 | `suspend fun` | UI 操作可能需要主线程切换，且 QuickJS 支持 asyncFunction |
| QuickJS 注册 | `asyncFunction` | UiProvider 方法是 suspend 函数，必须用 asyncFunction |
| Rhino 注册 | JS prototype + `__nativeCall` | 与现有 FileBridge/HttpBridge 模式一致 |
| 返回值 | JSON 字符串 | 与所有 Bridge 保持一致 |

#### 2. UiProvider 接口

UiProvider 定义在 `UiBridge.kt` 中（与 MemoryBridge/MemoryProvider 模式一致）：

```kotlin
interface UiProvider {
    suspend fun renderCard(cardJson: String): String
    suspend fun renderToast(message: String): String
    suspend fun showConfirm(title: String, message: String): String
}
```

#### 3. ScriptOrchestrator 集成

在 `resolveBridges()` 中添加 `"ui"` capability：

```kotlin
"ui" -> {
    val provider = uiProvider
        ?: throw IllegalStateException("UiProvider not set. Call setUiProvider() first.")
    bridges.add(UiBridge(provider))
}
```

新增 `setUiProvider()` 方法供主工程注入。

---

## P0-3: 全局变量注入

### 问题分析

示例脚本使用 `QUERY`、`LOCATION` 等全局变量，但 Orchestrator 没有参数注入机制。

### 方案设计

#### API 变更

```kotlin
// 之前
suspend fun execute(
    script: String,
    capabilities: List<String> = emptyList(),
    customBridges: List<CapabilityBridge> = emptyList()
): ScriptResult

// 之后
suspend fun execute(
    script: String,
    capabilities: List<String> = emptyList(),
    customBridges: List<CapabilityBridge> = emptyList(),
    variables: Map<String, Any> = emptyMap()  // 新增
): ScriptResult
```

#### 变量注入实现

**QuickJS 路径**：在 `evaluate()` 之前，通过 `evaluate()` 注入全局变量：
```kotlin
for ((name, value) in variables) {
    val jsValue = toJsLiteral(value)
    quickJs.evaluate<Unit>("var $name = $jsValue;")
}
```

**Rhino 路径**：在 `initSafeStandardObjects()` 之后注入：
```kotlin
for ((name, value) in variables) {
    val jsValue = toJsLiteral(value)
    cx.evaluateString(scope, "var $name = $jsValue;", "var-$name", 1, null)
}
```

#### 支持的变量类型

| Kotlin 类型 | JS 注入方式 | 示例 |
|-------------|-------------|------|
| `String` | `"value"` | `var QUERY = "天气";` |
| `Int/Long/Double` | `123` | `var LIMIT = 5;` |
| `Boolean` | `true/false` | `var VERBOSE = true;` |
| `null` | `null` | `var EXTRA = null;` |
| `List/Map` | `JSON.parse('...')` | `var CONFIG = JSON.parse('{"key":"val"}');` |

#### ScriptSkill 适配

`ExecuteScriptTool` 参数新增 `variables`（可选）：
```kotlin
override val parameters = mapOf(
    "script" to SkillParam("string", "要执行的 JavaScript 代码", true),
    "capabilities" to SkillParam("string", "需要的能力列表，逗号分隔", false, "fs,http"),
    "variables" to SkillParam("string", "全局变量 JSON 对象", false, "{}")
)
```

---

## 文件变更清单

| 文件 | 操作 | 变更内容 |
|------|------|----------|
| `script/.../bridge/UiBridge.kt` | 🆕 新建 | UiBridge + UiProvider 实现 |
| `script/build.gradle.kts` | ✏️ 修改 | QuickJS 版本 `1.0.4` → `1.0.0-alpha13` |
| `script/.../ScriptEngine.kt` | ✏️ 修改 | 增加 `variables` 参数 + 注入逻辑 |
| `script/.../ScriptOrchestrator.kt` | ✏️ 修改 | 增加 `variables` 参数 + `setUiProvider()` + resolveBridges |
| `app/.../ScriptSkill.kt` | ✏️ 修改 | 增加 `variables` 参数 + UiProvider 集成 |

---

## 实施步骤

1. ✅ 确认 quickjs-kt 版本（已完成：`1.0.0-alpha13`）
2. 🔲 创建 `UiBridge.kt`
3. 🔲 修改 `script/build.gradle.kts`（版本修复）
4. 🔲 修改 `ScriptEngine.kt`（变量注入）
5. 🔲 修改 `ScriptOrchestrator.kt`（变量注入 + UiBridge 集成）
6. 🔲 修改 `ScriptSkill.kt`（变量参数 + UiProvider）
7. 🔲 跑编译验证：`./gradlew :script:compileDebugKotlin`
8. 🔲 跑全量测试：`./gradlew :script:testDebugUnitTest`
9. 🔲 真机验证（如条件允许）

---

## 兼容性说明

- 所有新增参数均有默认值，**现有调用方无需修改**
- `variables: Map<String, Any> = emptyMap()` 向后兼容
- `customBridges` 参数仍可独立使用
- Rhino fallback 路径完全保持原逻辑

## 已知限制

1. **quickjs-kt 为 Alpha 版** — 若遇到 JNI 崩溃，fallback 到 Rhino
2. **UiBridge 在 Rhino 模式下使用 `__nativeCall`** — 同步调用，不支持异步 UI 操作
3. **变量注入不支持 Kotlin 自定义对象** — 复杂对象需序列化为 JSON 字符串
