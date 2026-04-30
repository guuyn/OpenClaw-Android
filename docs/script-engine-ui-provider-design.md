# ScriptEngine UiProvider 实现方案

> 版本: 0.1.0 | 日期: 2026-04-30 | 状态: 设计稿

## 架构总览

```
JS 脚本 (ui.renderCard / ui.renderToast / ui.showConfirm)
    │
    ▼
UiBridge (script module)
    │ handle() → runBlocking { provider.xxx() }
    ▼
ScriptUiManager (app module) — UiProvider 实现
    │
    ├── renderCard() → 注入 ChatMessage → ChatViewModel._messages
    ├── renderToast() → Android Toast (Main 线程)
    └── showConfirm() → CompletableDeferred 等待用户选择
         │
         ▼
    ChatScreen 显示确认弹窗 → 用户点击 → _confirmResult.trySetResult()
```

---

## 1. 架构决策：UiProvider 实现在哪个类？

### 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| **ChatViewModel 直接实现** | 简单、少一层 | ViewModel 职责膨胀，耦合 UI 细节 |
| ChatScreen Composable | 离 UI 最近 | Composable 不能持有 CompletableDeferred、不能 suspend |
| **ScriptUiManager (独立类)** ✅ | 职责单一、可测试、线程安全可控 | 多一个类，需要 ViewModel 创建并持有 |

### 结论：`ScriptUiManager` 独立类

- 放在 `app/.../ui/ScriptUiManager.kt`
- 实现 `UiProvider` 接口
- 通过 callback lambda 与 ChatViewModel 通信
- 通过 `CoroutineScope` 管理生命周期
- 持有 `_confirmDeferral` 用于 showConfirm 的同步等待

```
ChatViewModel
  ├── ScriptUiManager (UiProvider)
  │     ├── _confirmDeferral: CompletableDeferred<Boolean?>
  │     └── _cardIdCounter: AtomicInteger
  │     ├── onAddMessage: (ChatMessage) -> Unit  ← 注入消息到 ViewModel
  │     └── scope: CoroutineScope                 ← viewModelScope
  │
  └── ScriptSkill.setUiProvider(scriptUiManager)
```

---

## 2. 核心实现代码

### 2.1 ScriptUiManager — UiProvider 实现

文件：`app/src/main/java/ai/openclaw/android/ui/ScriptUiManager.kt`

```kotlin
package ai.openclaw.android.ui

import ai.openclaw.android.ChatMessage
import ai.openclaw.script.bridge.UiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptEngine 的 UiProvider 实现。
 *
 * 职责:
 * - renderCard: 将卡片 JSON 包装为 [A2UI]...[/A2UI] 消息注入聊天流
 * - renderToast: 使用 Android Toast 显示简短提示
 * - showConfirm: 通过 CompletableDeferred 同步等待用户选择
 */
class ScriptUiManager(
    private val scope: CoroutineScope,
    private val onAddMessage: suspend (ChatMessage) -> Unit,
    private val onShowConfirm: suspend (title: String, message: String) -> Boolean?,
) : UiProvider {

    private val cardIdCounter = AtomicInteger(0)

    /**
     * 渲染 A2UI 卡片 — 将卡片 JSON 注入聊天流
     *
     * 流程:
     * 1. 生成唯一 cardId
     * 2. 将卡片 JSON 包装为 [A2UI]...[/A2UI] 格式
     * 3. 通过 onAddMessage 注入到 ChatViewModel 的消息列表
     * 4. ChatScreen 的 A2UICardParser → A2UICardRouter 自动渲染
     */
    override suspend fun renderCard(cardJson: String): String {
        val cardId = "msg_card_${cardIdCounter.incrementAndGet()}"

        // 包装为标准 A2UI 标签格式
        val a2uiContent = "[A2UI]\n$cardJson\n[/A2UI]"

        // 注入到聊天消息流
        onAddMessage(
            ChatMessage(
                id = cardId,
                role = "assistant",
                content = a2uiContent,
            )
        )

        return """{"cardId":"$cardId"}"""
    }

    /**
     * 显示 Toast 提示
     *
     * 切换到 Main 线程调用 Android Toast，避免线程异常。
     */
    override suspend fun renderToast(message: String): String {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                android.app.Application().applicationContext, // 需要从构造函数传入 Context
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return """{"status":"ok"}"""
    }

    /**
     * 显示确认弹窗并等待用户选择
     *
     * 使用 CompletableDeferred 将异步 UI 交互转为同步 suspend 调用。
     * 调用 onShowConfirm callback → ChatScreen 显示 AlertDialog →
     * 用户点击 → CompletableDeferred.complete() → 本函数返回
     */
    override suspend fun showConfirm(title: String, message: String): String {
        val result = onShowConfirm(title, message)
        return when (result) {
            true -> """{"result":"confirm"}"""
            false -> """{"result":"cancel"}"""
            null -> """{"result":"cancel"}""" // 超时或取消默认 cancel
        }
    }
}
```

> **修正**：Toast 需要 Context，构造函数应改为：
```kotlin
class ScriptUiManager(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val onAddMessage: suspend (ChatMessage) -> Unit,
    private val onShowConfirm: suspend (title: String, message: String) -> Boolean?,
) : UiProvider {
    // renderToast 改为:
    override suspend fun renderToast(message: String): String {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        return """{"status":"ok"}"""
    }
}
```

---

### 2.2 ChatViewModel 集成点

**改动位置**: `ChatViewModel.kt`

```kotlin
// === 在 ChatViewModel 中新增 ===

/** ScriptEngine UI 管理器 */
private var scriptUiManager: ScriptUiManager? = null

/** 确认弹窗结果通道 */
private val _confirmRequest = MutableStateFlow<ConfirmRequest?>(null)
val confirmRequest: StateFlow<ConfirmRequest?> = _confirmRequest.asStateFlow()

data class ConfirmRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val deferred: CompletableDeferred<Boolean?>,
)

/** 初始化 ScriptUiManager（在 initialize() 中调用） */
private fun initScriptUiProvider(context: android.content.Context) {
    scriptUiManager = ScriptUiManager(
        context = context,
        scope = viewModelScope,
        onAddMessage = { chatMessage ->
            // 注入消息到聊天流
            val current = _messages.value.toMutableList()
            current.add(chatMessage)
            _messages.value = current
        },
        onShowConfirm = { title, message ->
            // 发起确认请求，等待 ChatScreen 返回结果
            val request = ConfirmRequest(title = title, message = message)
            _confirmRequest.value = request
            // suspend 等待结果
            request.deferred.await()
        }
    )

    // 注入到 ScriptSkill
    val scriptSkill = skillManager.getLoadedSkills()["script"]
        as? ai.openclaw.android.skill.builtin.ScriptSkill
    scriptSkill?.setUiProvider(scriptUiManager)
}
```

**在 `initialize()` 末尾调用**:
```kotlin
// 在 initialize() 的 viewModelScope.launch { ... } 末尾:
initScriptUiProvider(context)
```

---

### 2.3 ChatScreen 集成点

**改动位置**: `ChatScreen.kt`

新增参数:
```kotlin
@Composable
fun ChatScreen(
    // ... 现有参数 ...
    // 新增: 确认弹窗请求
    confirmRequest: ConfirmRequest? = null,
    onConfirmResult: (Boolean?) -> Unit = {},
) {
```

在 Composable 中监听确认请求:
```kotlin
// 在 ChatScreen 函数体中，已有 AlertDialog 模式之后新增:

// ScriptEngine showConfirm 弹窗
if (confirmRequest != null) {
    AlertDialog(
        onDismissRequest = {
            onConfirmResult(false) // dismiss = cancel
        },
        title = { Text(confirmRequest.title) },
        text = { Text(confirmRequest.message) },
        confirmButton = {
            TextButton(onClick = { onConfirmResult(true) }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirmResult(false) }) {
                Text("取消")
            }
        }
    )
}
```

---

### 2.4 MainActivity 层桥接

**改动位置**: `MainActivity.kt`（或 Compose 宿主层）

ViewModel 的 `confirmRequest` 需要通过 `onConfirmResult` 回调通知回来：

```kotlin
// MainActivity.kt 或 Compose 宿主中:

val confirmRequest by viewModel.confirmRequest.collectAsState()

LaunchedEffect(confirmRequest) {
    if (confirmRequest != null) {
        val request = confirmRequest!!
        // 这里通过 onConfirmResult 回调回传给 ViewModel
        // ChatScreen 的 onConfirmResult 调用后:
        // viewModel.resolveConfirm(request.id, result)
    }
}

// ViewModel 新增:
fun resolveConfirm(requestId: String, result: Boolean?) {
    // 这里需要在 ConfirmRequest 中携带 id 来匹配
}
```

**更简洁的方案**：直接在 `onShowConfirm` callback 中用 `CompletableDeferred` 完成，不需要额外的 resolve 方法。
因为 `onShowConfirm` 本身是 suspend 函数，它在 `_confirmRequest.value = request` 后立即 `request.deferred.await()`。
只需要 ChatScreen 在用户点击时调用 `request.deferred.complete(true/false)` 即可。

但 Composable 无法直接修改 `ConfirmRequest.deferred`（它是 val），所以需要通过回调：

```kotlin
// ViewModel 中:
fun handleConfirmResult(result: Boolean?) {
    // 这个函数被 ChatScreen 调用
    // 需要拿到当前 pending 的 deferred
}
```

**最佳方案**：用一个单独的 `_confirmResultChannel` Channel 或 `MutableSharedFlow`:

```kotlin
// ViewModel:
private val _confirmResultChannel = Channel<Boolean?>(Channel.RENDEZVOUS)

// 在 onShowConfirm callback 中:
onShowConfirm = { title, message ->
    val request = ConfirmRequest(title = title, message = message)
    _confirmRequest.value = request
    _confirmResultChannel.receive() // suspend 等待
}

fun submitConfirmResult(result: Boolean?) {
    _confirmResultChannel.trySend(result)
}
```

这样 ChatScreen 只需要在用户点击时调用 `viewModel.submitConfirmResult(true)` 即可。

---

### 2.5 GatewayManager 集成 ScriptSkill

**改动位置**: `GatewayManager.kt`

当前 ScriptSkill 已经在 `initializeSkills()` 中注册：
```kotlin
registerSkill(ai.openclaw.android.skill.builtin.ScriptSkill())
```

**不需要修改 GatewayManager**。`ScriptUiProvider` 的注入在 `ChatViewModel` 层完成（通过 `ScriptSkill.setUiProvider()`），
因为 ChatViewModel 已经持有 `skillManager` 的引用，可以通过它获取 ScriptSkill 实例。

> 注意：ScriptOrchestrator 在 ScriptSkill.initialize() 时创建，而 setUiProvider 可以在 initialize 之前或之后调用。
> ScriptSkill 代码已处理这个时序问题（见 ScriptSkill.kt 第 35-37 行）：
> ```kotlin
> fun setUiProvider(provider: UiProvider?) {
>     uiProvider = provider
>     orchestrator?.setUiProvider(provider ?: return)
> }
> ```

---

## 3. 数据流详解

### 3.1 renderCard 完整链路

```
JS 脚本调用:
  ui.renderCard(JSON.stringify({
    type: "weather",
    data: { city: "西安", temperature: "14", condition: "多云" }
  }))
  │
  ▼ Rhino/QuickJS 执行
  │
  ▼ UiBridge.handle("ui.renderCard", '{"cardJson":"{...}"}')
  │  runBlocking { provider.renderCard(cardJson) }
  │
  ▼ ScriptUiManager.renderCard(cardJson)
  │  1. cardId = "msg_card_1"
  │  2. content = "[A2UI]\n{...}\n[/A2UI]"
  │  3. onAddMessage(ChatMessage(id=cardId, role="assistant", content=content))
  │     → ChatViewModel._messages.value += newMessage
  │  4. return {"cardId":"msg_card_1"}
  │
  ▼ ChatScreen Compose 重组
     messages StateFlow 更新 → LazyColumn 重建最后一项
     MessageBubble → AiMessageBubble →
     A2UICardParser.parse(content) → [MessageSegment.A2UICard(card)]
     → A2UICardRouter(card) → WeatherCard(Compose)
```

### 3.2 showConfirm 完整链路

```
JS 脚本调用:
  var result = ui.showConfirm("确认操作", "此操作不可撤销")
  │
  ▼ UiBridge.handle("ui.showConfirm", '{"title":"确认操作","message":"..."}')
  │  runBlocking { provider.showConfirm(title, message) }  ← 阻塞在这里
  │
  ▼ ScriptUiManager.showConfirm(title, message)
  │  1. request = ConfirmRequest(title, message, deferred=CompletableDeferred())
  │  2. _confirmRequest.value = request          ← ChatScreen 收到 StateFlow 更新
  │  3. _confirmResultChannel.receive()          ← suspend 等待结果
  │
  ▼ ChatScreen 检测到 confirmRequest != null
     显示 AlertDialog { title: "确认操作", text: "此操作不可撤销", 确认/取消按钮 }
     │
     ▼ 用户点击 "确认"
        ChatScreen 调用: onConfirmResult(true)
        → ViewModel.submitConfirmResult(true)
        → _confirmResultChannel.send(true)
        │
        ▼ ScriptUiManager 的 receive() 返回 true
           return """{"result":"confirm"}"""
           │
           ▼ UiBridge 返回给 JS 引擎
              JS 收到 {result: "confirm"}
```

### 3.3 renderToast

```
JS 脚本调用:
  ui.renderToast("操作成功")
  │
  ▼ UiBridge.handle → provider.renderToast("操作成功")
  │
  ▼ ScriptUiManager.renderToast(message)
  │  withContext(Dispatchers.Main) {
  │      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  │  }
  │  return """{"status":"ok"}"""
```

---

## 4. 线程安全分析

| 组件 | 运行线程 | 说明 |
|------|----------|------|
| UiBridge.handle() | ScriptEngine 后台线程 | Rhino/QuickJS 执行线程，已用 runBlocking 包裹 |
| ScriptUiManager.renderCard() | ScriptEngine 后台线程 | 通过 `onAddMessage` 写 ViewModel，需要在 Main 线程更新 StateFlow |
| ScriptUiManager.renderToast() | → Main 线程 | `withContext(Dispatchers.Main)` 切换 |
| ScriptUiManager.showConfirm() | ScriptEngine 后台线程 | suspend 等待，不阻塞其他协程 |
| ChatViewModel 更新 _messages | Main 线程 | StateFlow 需要在 Main 线程发射 |
| ChatScreen Compose 重组 | Main 线程 | Compose 调度 |

**关键修正**：`onAddMessage` 回调中更新 `_messages.value` 需要在 Main 线程：

```kotlin
onAddMessage = { chatMessage ->
    // 确保在 Main 线程更新 StateFlow
    if (Looper.myLooper() == Looper.getMainLooper()) {
        val current = _messages.value.toMutableList()
        current.add(chatMessage)
        _messages.value = current
    } else {
        // 从后台线程来，用 Handler post 到 Main
        android.os.Handler(Looper.getMainLooper()).post {
            val current = _messages.value.toMutableList()
            current.add(chatMessage)
            _messages.value = current
        }
    }
}
```

**或者更优雅的方案**——使用 `MainScope` 或直接让 onAddMessage 是 suspend 函数并在内部切换：

```kotlin
onAddMessage = { chatMessage ->
    withContext(Dispatchers.Main) {
        val current = _messages.value.toMutableList()
        current.add(chatMessage)
        _messages.value = current
    }
}
```

因为 `ScriptUiManager.renderCard()` 本身是 `suspend` 函数，所以 `onAddMessage` 也可以是 `suspend`，
可以直接用 `withContext(Dispatchers.Main)`。

---

## 5. 初始化时序

```
App 启动
  │
  ▼ GatewayService.onCreate()
  │
  ▼ GatewayManager.initializeSkills()
  │     → registerSkill(ScriptSkill())
  │       → ScriptSkill 实例创建 (orchestrator = null, uiProvider = null)
  │
  ▼ GatewayManager.initialize() 继续...
  │     → ScriptSkill.initialize(context)
  │       → orchestrator = ScriptOrchestrator(context)
  │       → uiProvider?.let { orchestrator?.setUiProvider(it) }  // 此时 uiProvider=null
  │
  ▼ ChatViewModel.initialize(context)  ← 用户首次打开聊天界面
  │     → ... 常规初始化 ...
  │     → initScriptUiProvider(context)
  │       → scriptUiManager = ScriptUiManager(...)
  │       → ScriptSkill.setUiProvider(scriptUiManager)  ← 关键注入点
  │         → uiProvider = scriptUiManager
  │         → orchestrator?.setUiProvider(scriptUiManager)  ✅ orchestrator 已存在
  │
  ▼ ScriptEngine 就绪，ui 能力可用
```

**关键点**：`ScriptSkill.setUiProvider()` 可以在 `initialize()` 之后调用，代码已处理此情况。

---

## 6. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `app/.../ui/ScriptUiManager.kt` | **新建** | UiProvider 实现类 |
| `app/.../viewmodel/ChatViewModel.kt` | **修改** | 新增 `initScriptUiProvider()`、`_confirmRequest`、`submitConfirmResult()` |
| `app/.../ChatScreen.kt` | **修改** | 新增 `confirmRequest` 参数 + AlertDialog |
| `app/.../skill/builtin/ScriptSkill.kt` | **不变** | 已支持 setUiProvider() |
| `script/.../bridge/UiBridge.kt` | **不变** | 接口已定义完整 |
| `script/.../ScriptOrchestrator.kt` | **确认** | 需要确认有 `setUiProvider()` 方法 |

---

## 7. 边界情况处理

### 7.1 超时
`showConfirm` 如果用户长时间不操作，ScriptEngine 的 10s 超时会自动终止。
但 `CompletableDeferred` 需要超时保护：

```kotlin
override suspend fun showConfirm(title: String, message: String): String {
    val result = withTimeoutOrNull(8000) {
        onShowConfirm(title, message)
    }
    return when (result) {
        true -> """{"result":"confirm"}"""
        else -> """{"result":"cancel"}"""  // 超时默认 cancel
    }
}
```

### 7.2 快速连续调用
如果 JS 脚本连续调用两次 `showConfirm`：
- 第一次 `onShowConfirm` suspend 等待中
- 第二次 `_confirmRequest.value = request2` 会覆盖 request1
- 第一次的 `deferred` 永远不会 complete → 超时保护兜底

**改进方案**：用队列而非覆盖：

```kotlin
private val _confirmQueue = Channel<ConfirmRequest>(Channel.UNLIMITED)
private val _confirmResultQueue = Channel<Boolean?>(Channel.UNLIMITED)

// 消费端:
suspend fun processConfirmQueue() {
    while (true) {
        val request = _confirmQueue.receive()
        _confirmRequest.value = request
        val result = _confirmResultQueue.receive()
        request.deferred.complete(result)
    }
}
```

但对于实际场景（JS 脚本顺序执行），同一时间只有一个 showConfirm 等待，覆盖方案在超时保护下可接受。

### 7.3 ViewModel 销毁后调用
如果用户在 ScriptEngine 执行过程中退出聊天界面：
- ViewModel 的 `viewModelScope` 会取消
- ScriptEngine 的 runBlocking 不受影响（它在 ScriptEngine 自己的线程）
- `onAddMessage` 回调中的 `withContext(Dispatchers.Main)` 会因 scope 取消而抛 CancellationException
- UiBridge 的 try-catch 会捕获并返回错误

**解决方案**：ScriptUiManager 使用自己的 `CoroutineScope(Dispatchers.Main + SupervisorJob())` 并维护一个 `isDestroyed` 标志，
在 `onCleared()` 时清理。

### 7.4 renderCard 中卡片 JSON 格式错误
JS 可能传入无效的 JSON：
- ScriptUiManager 不解析 JSON，直接包装为 `[A2UI]...[/A2UI]`
- 解析和渲染由 `A2UICardParser` 在 ChatScreen 层处理
- A2UICardParser 已有 try-catch 回退机制
- 如果解析失败，会回退为 FallbackCard 或纯文本显示

---

## 8. 完整代码参考

### ScriptUiManager.kt (完整版)

```kotlin
package ai.openclaw.android.ui

import ai.openclaw.android.ChatMessage
import ai.openclaw.script.bridge.UiProvider
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * ScriptEngine 的 UiProvider 实现，桥接 script module 与 app module UI 层。
 *
 * 线程模型:
 * - renderCard/showConfirm 在 ScriptEngine 后台线程调用 (runBlocking 包裹)
 * - renderCard 内部通过 suspend onAddMessage + withContext(Main) 安全更新 UI
 * - renderToast 通过 withContext(Main) 调用 Android Toast
 * - showConfirm 通过 Channel 与 ChatScreen 同步
 */
class ScriptUiManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAddMessage: suspend (ChatMessage) -> Unit,
    confirmRequestFlow: kotlinx.coroutines.flow.MutableStateFlow<ConfirmRequest?>,
    private val confirmResultChannel: Channel<Boolean?>,
) : UiProvider {

    private val cardIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun renderCard(cardJson: String): String {
        val cardId = "msg_card_${cardIdCounter.incrementAndGet()}"
        val a2uiContent = "[A2UI]\n$cardJson\n[/A2UI]"

        onAddMessage(
            ChatMessage(
                id = cardId,
                role = "assistant",
                content = a2uiContent,
            )
        )

        return """{"cardId":"$cardId"}"""
    }

    override suspend fun renderToast(message: String): String {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return """{"status":"ok"}"""
    }

    override suspend fun showConfirm(title: String, message: String): String {
        val request = ConfirmRequest(
            title = title,
            message = message,
        )
        confirmRequestFlow.value = request

        val result = withTimeoutOrNull(8000L) {
            confirmResultChannel.receive()
        }

        confirmRequestFlow.value = null // 清除请求

        return when (result) {
            true -> """{"result":"confirm"}"""
            else -> """{"result":"cancel"}"""
        }
    }
}

/** 确认弹窗请求 */
data class ConfirmRequest(
    val title: String,
    val message: String,
)
```

### ChatViewModel.kt 新增部分

```kotlin
// imports 新增:
import ai.openclaw.android.ui.ScriptUiManager
import ai.openclaw.android.ui.ConfirmRequest
import kotlinx.coroutines.channels.Channel

// 类成员新增:
/** ScriptEngine UI 管理器 */
private var scriptUiManager: ScriptUiManager? = null

/** 确认弹窗请求 — ChatScreen 监听此 StateFlow 显示 AlertDialog */
private val _confirmRequest = MutableStateFlow<ConfirmRequest?>(null)
val confirmRequest: StateFlow<ConfirmRequest?> = _confirmRequest.asStateFlow()

/** 确认弹窗结果通道 — ChatScreen 通过 submitConfirmResult() 发送 */
private val confirmResultChannel = Channel<Boolean?>(Channel.RENDEZVOUS)

/** 用户选择确认结果 */
fun submitConfirmResult(result: Boolean?) {
    confirmResultChannel.trySend(result)
}

/** 初始化 ScriptUiProvider */
private fun initScriptUiProvider(context: android.content.Context) {
    scriptUiManager = ScriptUiManager(
        context = context,
        scope = viewModelScope,
        onAddMessage = { chatMessage ->
            withContext(Dispatchers.Main) {
                val current = _messages.value.toMutableList()
                current.add(chatMessage)
                _messages.value = current
            }
        },
        confirmRequestFlow = _confirmRequest,
        confirmResultChannel = confirmResultChannel,
    )

    // 注入到 ScriptSkill
    val scriptSkill = skillManager.getLoadedSkills()["script"]
        as? ai.openclaw.android.skill.builtin.ScriptSkill
    scriptSkill?.setUiProvider(scriptUiManager)

    android.util.Log.d(TAG, "ScriptUiProvider initialized")
}

// 在 initialize() 的 viewModelScope.launch { ... } 末尾添加:
initScriptUiProvider(context)
```

### ChatScreen.kt 修改部分

```kotlin
// 函数签名新增参数:
@Composable
fun ChatScreen(
    // ... 所有现有参数 ...
    confirmRequest: ConfirmRequest? = null,
    onConfirmResult: (Boolean?) -> Unit = {},
) {

// 在函数体内（已有的 AlertDialog 之后）新增:

    // ScriptEngine showConfirm 弹窗
    if (confirmRequest != null) {
        AlertDialog(
            onDismissRequest = { onConfirmResult(false) },
            title = { Text(confirmRequest.title) },
            text = { Text(confirmRequest.message) },
            confirmButton = {
                TextButton(onClick = { onConfirmResult(true) }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmResult(false) }) {
                    Text("取消")
                }
            }
        )
    }
```

### MainActivity 桥接 (Compose 调用处)

```kotlin
// 在 ChatScreen 调用处:
val confirmRequest by viewModel.confirmRequest.collectAsState()

ChatScreen(
    // ... 现有参数 ...
    confirmRequest = confirmRequest,
    onConfirmResult = { result ->
        viewModel.submitConfirmResult(result)
    },
)
```

---

## 9. 测试策略

### 9.1 单元测试 (ScriptUiManager)

```kotlin
@Test
fun `renderCard generates unique cardId`() = runTest {
    val messages = mutableListOf<ChatMessage>()
    val mgr = ScriptUiManager(
        context = ApplicationProvider.getApplicationContext(),
        scope = this,
        onAddMessage = { messages.add(it) },
        confirmRequestFlow = MutableStateFlow(null),
        confirmResultChannel = Channel(),
    )

    val r1 = mgr.renderCard("""{"type":"info","data":{"content":"test"}}""")
    val r2 = mgr.renderCard("""{"type":"info","data":{"content":"test2"}}""")

    assertNotEquals(r1, r2)
    assertEquals(2, messages.size)
    assertTrue(messages[0].content.contains("[A2UI]"))
}
```

### 9.2 集成测试

- 验证 ScriptSkill → UiBridge → ScriptUiManager → ChatViewModel 全链路
- 验证 showConfirm 的 CompletableDeferred 同步机制
- 验证 renderToast 的 Main 线程切换

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| ScriptEngine 执行中 ViewModel 被销毁 | 回调失效 | `withTimeoutOrNull` + CancellationException 处理 |
| showConfirm 超时 | JS 脚本挂起 | 8s 超时 + 默认 cancel |
| 并发 showConfirm | 请求覆盖 | 当前可接受，复杂场景改用队列 |
| 无效卡片 JSON | 渲染失败 | A2UICardParser 已有回退机制 |
| Main 线程阻塞 | ANR | 所有 UI 操作通过 suspend + withContext(Main) |
