# Current Status — 2026-07-03 — Tool-Call 2013 修复 & History 同步 Bug

## ✅ 本次成果

| Commit | 内容 | 变更 |
|--------|------|------|
| `e73e04a` | T-B03 主体 (Fix A 完整 body 写入 + Fix B atomic block trim) + Save 持久化 + UI 日志复制功能收官 | 5 文件, +145/-35 |
| `7abbf84` | AgentSession `history` 列表与 `state.history` 同步修复（更深层 bug） | 1 文件, +13/-0 |

两 commit 已 push 到 `origin/dev` (e6bc3ed → e73e04a → 7abbf84)。

---

## 🐛 核心 Bug：History 列表与 State 不同步（**根因**）

### 现象

**tool_call_id 2013 错误**：`tool result's tool id(call_function_xxx) not found`

真机实证（旧 APK，修复前）：

```
12:50:46 Round 1 start → historySize=1
12:50:48 Tool calls →     historySize=2
12:50:49 Tools done →     historySize=3
12:50:49 Round 2 start →  historySize=3
12:50:58 Final answer →   historySize=4
12:53:56 Round 1 start →  historySize=4   ← ⚠️ 第二次对话进入时只 4 条！
```

**预期 5 条**（缺 1 条 `assistant(tool_calls)`），导致请求 [2] 的 messages 数组里：

```
[0] system
[1] user "Beijing weather"
[2] tool (tool_call_id=nqw2azyq1tmy_1)   ← 前一条是 user，不是 assistant(tool_calls)！
[3] assistant(content "<think>...")
[4] user "Shanghai weather"
```

tool message 找不到对应的 assistant(tool_calls)，这就是 2013 错误的直接触发场景。

### 根因

`AgentSession.kt:777`（修复前）：

```kotlin
for (toolCall in toolCalls) {
    emit(SessionEvent.ToolExecuting(toolCall.function.name))
    val result = executeToolCall(toolCall)
    history.add(Message(role = "tool", ...))    // ← 只 add tool
    state = state.copy(history = state.history + Message(role = "tool", ...))
    // ↑ state.history 正确累积 assistant(tool_calls) + tool
    // ↑ 但 history 列表漏 add assistant(tool_calls)
}
```

**`state.history` 用 `state.copy(history = state.history + ...)` 正确累积了 assistant(tool_calls)，但 mutable `history` 列表只在 762 行通过 state 更新影响，并未同步 append assistant(tool_calls) 消息。**

下游 `trimHistoryByTokens()` 和同步入口 `history.toList()` 都依赖 mutable `history` 列表，导致后续 round 构建 messages 时缺失 assistant(tool_calls)。

### 修复

`AgentSession.kt:778-779` 加一行：

```kotlin
state = state.copy(
    history = state.history + Message(role = "assistant", content = "", toolCalls = toolCalls),
    currentToolCalls = toolCalls
)
history.add(Message(role = "assistant", content = "", toolCalls = toolCalls))  // [FIX] sync history with state
```

---

## ✅ T-B03 双修（症状层）

### Fix A：完整 body 写入运行日志（诊断用）

**位置**: `model/OpenAIClient.kt:124-130`

```kotlin
val fullBody = bodyBuilder.toString()
val bodyPreview = if (fullBody.length > 2000) {
    fullBody.take(2000) + "...[truncated,total=${fullBody.length}]"
} else fullBody
LogManager.shared.log("DEBUG", TAG, "[${if (stream) "Stream" else "Chat"} req body] $bodyPreview")
```

**作用**: 每次 LLM 请求把完整 body（前 2000 字符）写入运行日志面板，用户能直接在 app 设置页查看。

### Fix B：Atomic Block 保护

**位置**: `agent/AgentSession.kt:953-984`

```kotlin
private fun trimHistoryByTokens() {
    val effectiveMaxTokens = getMaxContextTokens()
    if (estimateTokens(history) <= effectiveMaxTokens) return
    if (history.size <= 2) return

    var trimStart = 0
    while (trimStart < history.size - 2 &&
           estimateTokens(history.subList(trimStart, history.size)) > effectiveMaxTokens) {
        val msg = history[trimStart]
        if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
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
```

**作用**: trim 时把 `assistant(tool_calls) + N×tool(tool_call_id)` 作为不可分割的 atomic block 整体跳过，避免裁剪破坏配对关系。

---

## 🐛 附带 Bug：Save Configuration 不写 SharedPreferences

### 现象

`MainActivity.kt` `onSaveConfig` 只调用 `reconfigureModel(...)`，**没有调 `ConfigManager.setModel*()` 持久化新值**。结果：
- in-memory state 是新的
- SharedPreferences 里的值还是旧（或者 default）
- app 重启后丢失用户配置

### 修复

`MainActivity.kt:707-720` 在 `reconfigureModel` 之前加：

```kotlin
try {
    ConfigManager.setModelApiKey(modelApiKey)
    ConfigManager.setModelName(modelName)
    ConfigManager.setModelBaseUrl(modelBaseUrl)
    ConfigManager.setModelProvider(modelProvider)
    LogManager.shared.log("INFO", "MainActivity", "Persisted config to SharedPreferences: ...")
} catch (e: Exception) { ... }
```

附带效果：诊断过程中发现的真正问题（配置丢失）一并修复。

---

## ✅ UI 改进：运行日志"复制"按钮 v6 收官

### 改动

- `LogManager.kt`: 加 `getAllAsText()` 方法，导出 `[HH:mm:ss] LEVEL/tag: message` 格式
- `SettingsScreen.kt`: 在"运行日志"卡片标题行右侧加 `TextButton`（仅纯 Text，去 Icon），点击复制全部日志到剪贴板
- `padding(bottom = 96.dp)`: 滚动到底能完整看到日志

### 3 个 Compose 坑（已记录）

1. `SelectionContainer` 不能嵌套 `LazyColumn` — 用 `Column.forEach + verticalScroll`
2. Bottom padding 16dp 不够覆盖 nav bar — 改为 `padding(start=16, end=16, top=16, bottom=96.dp)`
3. `LocalClipboardManager` 在 Compose 1.7+ 已 deprecated — 暂保留 deprecated API（不影响构建）

---

## 📊 真机验证（HUAWEI Mate 20 / HMA-AL00）

### 测试环境

- Model: `MiniMax-M2.7-highspeed`
- Endpoint: `https://api.minimaxi.com/v1`
- Provider: minimax-portal (用户提供的 key)
- Tools: 68 个

### 修复前 vs 修复后

| 验证项 | 修复前 | 修复后 |
|--------|--------|--------|
| 第 1 轮 Round 1 start historySize | 1 | 1 |
| Tool calls historySize | 2 | 2 |
| Tools done historySize | 3 | 3 |
| 第 2 轮 Round 1 start historySize | **4** ❌ | **5** ✅ |
| messages 变化 | 2→4(+2)→5(+1)→2013 | 2→4→6→8 (严格 +2) |
| 2013 错误 | ❌ 出现 | ✅ 零错误 |
| 多轮 Stream request | ❌ 第 3 轮 400 invalid | ✅ 4 轮全成功 |

**完整时间线**（修复后实测）：

```
18:07:12 Round 1 start →  historySize=1
18:07:14 Tool calls →     historySize=2
18:07:15 Tools done →     historySize=3
18:07:15 Round 2 start →  historySize=3
18:07:19 Final answer →   historySize=4
18:07:34 Round 1 start →  historySize=5   ← +1 assistant(tool_calls) 正确累积！
18:07:35 Tool calls →     historySize=6
18:07:36 Tools done →     historySize=7
18:07:36 Round 2 start →  historySize=7
18:07:39 Final answer →   historySize=8
```

**Stream request messages 计数**：

| 时间 | messages | 含义 |
|------|----------|------|
| 18:07:12 | 2 | system + user1 |
| 18:07:15 | 4 | +2: assistant(tool_calls) + tool |
| 18:07:34 | 6 | +2: assistant(content) + user2 |
| 18:07:36 | 8 | +2: assistant(tool_calls) + tool |

**每次严格 +2，零 2013 错误。** ✅

---

## 🧠 关键教训

### 1. 单元测试 ≠ 完整链路验证

- Fix B 的 `trimHistoryByTokens` 单元测试通过（862/862 PASS）
- 但**真机端到端跑**才发现 history 列表同步 bug
- **建议**: 任何涉及状态管理的代码，单元测试覆盖逻辑路径的同时，必须真机跑至少 2 轮 multi-turn + multi-tool-call

### 2. State 与 Mutable List 双轨制是 bug 温床

`AgentSession` 同时维护：
- `state.history: List<Message>` (immutable, data class copy)
- `history: MutableList<Message>` (legacy, 直接 .add)

两轨制容易漏同步。**建议**: 长期看应该统一到 immutable state（删 mutable history list），但短期 fix 已经能解决问题。

### 3. "Save Configuration" 这类 UI 操作应该原子性

原实现只更新内存不持久化，是常见反模式。**建议**: 任何 UI 上的"保存"操作应该 (a) 持久化 + (b) 通知运行时，单一动作统一处理。

### 4. 临时诊断代码（chunk 输出完整 body）不应入 commit

诊断时为了看完整 messages，把 Fix A 的 2000 截断改成 chunk 输出。这部分代码诊断价值高但不应该留在生产代码里。**建议**: 诊断改动还原，截断 2000 字符即可。

---

## 📋 后续待办

- [ ] **多轮 (>20) 真机压测 Fix B 的 trim 路径**：当前未触发 trim（messages 数不大），需要构造大 history 场景
- [ ] 考虑统一 history 列表与 state（删 mutable history list）
- [ ] `LocalClipboardManager` 替换为 `LocalClipboard` (suspend 版本)
- [ ] Icons.Filled.Chat 替换为 Icons.AutoMirrored.Filled.Chat
- [ ] `release variant` 的 `TriggerScreen.kt:342` FlowRow 缺 `@OptIn` 注解 — F-07 release-blocker

---

## 📁 相关文件

| 文件 | 改动 |
|------|------|
| `model/OpenAIClient.kt` | +8 行 — Fix A 完整 body 写入运行日志（2000 截断） |
| `agent/AgentSession.kt` | +45 行 — Fix B atomic block trim (+32) + history 同步修复 (+13) |
| `MainActivity.kt` | +34 行 — Save 持久化修复 |
| `LogManager.kt` | +14 行 — getAllAsText() 方法 |
| `SettingsScreen.kt` | +92 行 — 复制按钮 + padding 调整 |