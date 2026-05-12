# A2UI 协议兼容 + 语音播报 架构审查

> 审查日期: 2026-05-02  
> 审查范围: OpenClaw-Android 项目中 A2UI 渲染管线 + 语音播报系统  
> 涉及文件: ChatScreen.kt, MessageBubble.kt, A2UIComposeRenderer.kt, A2UICards.kt, A2UICardModels.kt, VoiceInteractionManager.kt, ResponseRouter.kt, AgentResponseParser.kt, A2UIRenderer.kt, A2UIMessage.kt

---

## 一、现状分析

### 1.1 整体架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        OpenClaw-Android 架构                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────┐  │
│  │  LLM / Agent │───▶│ ChatViewModel│───▶│    ChatScreen        │  │
│  │  (服务端)     │    │  (状态管理)   │    │    (UI 层)           │  │
│  └──────────────┘    └──────────────┘    └──────────────────────┘  │
│                            │                      │                │
│                   ┌────────▼────────┐      ┌──────▼──────┐        │
│                   │ AgentResponse   │      │ MessageBubble│        │
│                   │ Parser + Router │      │ (气泡渲染)    │        │
│                   └─────────────────┘      └──────┬──────┘        │
│                            │                      │                │
│                   ┌────────▼────────┐      ┌──────▼──────┐        │
│                   │   Deliverable   │      │ A2UI 渲染   │        │
│                   │ (Voice/Text/    │      │ (协议解析)   │        │
│                   │  Rich/Mixed)    │      └─────────────┘        │
│                   └────────┬────────┘                              │
│                            │                                      │
│                   ┌────────▼────────┐                             │
│                   │ VoiceInteraction│                             │
│                   │ Manager (TTS)   │                             │
│                   └─────────────────┘                             │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 A2UI 协议数据流

```
LLM 响应文本
    │
    ▼
ChatViewModel.handleSessionEvent(SessionEvent.Complete)
    │
    ├── 解析: AgentResponseParser.parse(fullText)
    │   ├── 提取 JSON: {"type","voice_text","rich_content","fallback_text"}
    │   └── 返回 AgentResponse
    │
    ├── 路由: ResponseRouter.route(AgentResponse)
    │   ├── 根据 DeviceCapabilities 决策
    │   └── 返回 Deliverable (PlainText/Voice/RichText/Mixed)
    │
    └── 更新 UI:
        ├── _lastDeliverable → ChatScreen 监听 → 触发 TTS
        ├── _lastRichContent → 传递给 MessageBubble
        └── message.content = displayText (fallbackText 或 deliverable.text)
              │
              ▼
        MessageBubble (ChatScreen.kt 内定义)
              │
              ├── A2UICardParser.parse(message.content)
              │   ├── 提取 [A2UI]...[/A2UI] 标签内的 JSON
              │   ├── 检测协议类型:
              │   │   ├── 标准协议 (createSurface/updateComponents) → StandardProtocol
              │   │   ├── v2 格式 (type+data+actions) → A2UICard
              │   │   └── v1 旧格式 → A2UICard (parseV1 fallback)
              │   └── 返回 List<MessageSegment>
              │
              └── 渲染每个 segment:
                  ├── Text → 普通 Text 组件
                  ├── A2UICard → A2UICardRouter → 14 种硬编码卡片
                  │   (WeatherCard, SearchResultCard, TranslationCard...)
                  └── StandardProtocol → A2UIRendererWithErrorBoundary
                                         → A2UIComposeRenderer
                                         → A2UIRenderer.processMessage()
                                         → ComponentRegistry.render()
```

### 1.3 语音播报数据流

```
用户操作                    数据流                       TTS 引擎
─────────                ──────────                  ──────────
长按麦克风 → STT识别 → 确认发送 → LLM处理
                                              │
                                              ▼
                                      AgentResponse.voiceText
                                              │
                                              ▼
                                      ResponseRouter → Deliverable.Voice
                                              │
                                              ▼
                                      ChatScreen.onSpeakText(text)
                                              │
                                              ▼
                                      VoiceInteractionManager.speak(text)
                                              │
                                    ┌─────────┴──────────┐
                                    ▼                    ▼
                              AndroidTTSEngine      SherpaTtsEngine
                              (系统 TTS, 首选)      (离线 TTS, 备选)
```

### 1.4 已知问题清单

#### 问题 P1: 双协议系统并行，渲染路径分裂

项目同时维护 **两套独立的 A2UI 渲染系统**：

| 维度 | 旧版卡片系统 (A2UICard) | 标准协议系统 (A2UIComposeRenderer) |
|------|------------------------|-----------------------------------|
| 数据格式 | `{"type":"weather","data":{...},"actions":[...]}` | JSONL: `{"version":"v0.10","createSurface":{...}}` |
| 解析器 | `A2UICardParser.tryParseV2()` / `parseV1()` | `A2UIComposeRenderer.extractA2UIJsons()` |
| 渲染器 | `A2UICardRouter` → 14 种硬编码 Compose 卡片 | `A2UIRenderer` → 通用组件树渲染 |
| 扩展性 | 每种新卡片需手写 Compose 组件 | 协议驱动，服务端定义组件 |
| 状态 | 仍在 ChatScreen.kt 中活跃使用 | 与旧版共存 |

**核心矛盾**: 两套系统通过 `isStandardProtocol()` 分支判断，但：
- `A2UIComposeRenderer` 内部也包含 `convertLegacyCardToProtocol()` — 把旧格式转成标准协议再渲染，导致旧格式卡片走了两条路径
- `A2UICardParser` 和 `A2UIComposeRenderer` 各自实现了 `extractA2UIJsons()` 和 `isStandardProtocol()`，逻辑重复

#### 问题 P2: 协议版本混乱

- `A2UIMessage.kt` 声明支持 `v0.8, v0.9, v0.10`
- `A2UIComposeRenderer.normalizeToJsonL()` 硬编码 `"v0.10"`
- `buildProtocolMessage()` 中 legacy→标准协议转换时，所有输出都是 v0.10
- 没有版本协商或降级机制

#### 问题 P3: surfaceId 管理混乱

- `extractSurfaceId()` 从 JSON 中提取，但 fallback 用 `"chat_${System.currentTimeMillis()}"`
- 每个 MessageBubble 创建独立的 `A2UIRenderer` 实例 (`rememberA2UIRenderer()`)
- 每条消息的 surfaceId 互相隔离，无法跨消息共享状态
- 旧格式转换时注入的 surfaceId 与标准协议中提取的 surfaceId 可能不一致

#### 问题 P4: 语音播报与 A2UI 内容耦合

**关键缺陷**: 用户点击"语音播报"时，`onSpeakText(message.content)` 发送的是 **完整消息内容**，包含 `[A2UI]...[/A2UI]` 标签和原始 JSON。

```kotlin
// ChatScreen.kt — AiMessageBubble 中的语音播报按钮
IconButton(onClick = onSpeakText) {
    Icon(Icons.Default.VolumeUp, "语音播报")
}
// onSpeakText = { onSpeakText?.invoke(message.content) }
// message.content 包含: "今天天气不错 [A2UI]{"createSurface":{...}}[/A2UI]"
// TTS 会朗读 JSON 标签！
```

同时，`AgentResponse.voiceText` 已经提供了干净的语音文本，但：
- `Deliverable.Voice` 只在自动路由时使用
- 手动语音播报按钮直接传 `message.content`，绕过了 `voiceText`
- 没有从消息内容中剥离 A2UI 标签的机制

#### 问题 P5: 代码重复与职责混乱

| 重复/混乱点 | 位置 | 问题 |
|------------|------|------|
| `extractA2UIJsons()` | A2UICardParser.kt + A2UIComposeRenderer.kt + A2UIParseUtils | 三处实现 |
| `isStandardProtocol()` | A2UICardParser.kt + A2UIComposeRenderer.kt | 两处实现，检测逻辑略有差异 |
| `convertLegacyCardToProtocol()` | A2UIComposeRenderer.kt (private) + A2UIParseUtils (public) | 两处实现 |
| `buildProtocolMessage()` | A2UIComposeRenderer.kt + A2UIParseUtils | 两处实现 |
| `A2UICardRouterLegacy()` | A2UICards.kt | 旧版路由，已废弃但未清理 |
| `tryBuildA2UIMessage()` | ChatScreen.kt | @Deprecated 但保留在代码中 |
| `EnhancedMessageBubble` | MessageBubble.kt | 与 ChatScreen.kt 中的 `MessageBubble` 功能重叠 |

#### 问题 P6: 错误处理不一致

- `A2UIRendererWithErrorBoundary` 只做前置校验（检查标签是否闭合），不捕获 Compose 渲染异常
- `A2UIComposeRenderer` 内部用 `runCatching` 包裹 `processMessage`，失败时显示 `A2UIFallbackCard`
- `A2UICardRouter` 对未知类型返回 `FallbackCard`，但 null-safe 检查依赖 `?.let` 链
- 没有统一的 A2UI 错误上报或监控机制

#### 问题 P7: MessageBubble 文件分裂

- `ChatScreen.kt` 内定义了 `MessageBubble`、`UserMessageBubble`、`AiMessageBubble`
- `MessageBubble.kt` 定义了 `EnhancedMessageBubble`
- 两套实现功能重叠但渲染逻辑不同：
  - ChatScreen 版本使用 `A2UIRendererWithErrorBoundary`
  - MessageBubble.kt 版本直接使用 `A2UIComposeRenderer`
- 实际使用的是 ChatScreen.kt 中的版本，MessageBubble.kt 可能是遗留代码

---

## 二、设计原则

### 2.1 应该保留的

1. **A2UIRenderer 核心引擎** (`android_compose` 模块) — 设计良好，支持完整标准协议，有状态管理、错误处理、数据绑定
2. **ResponseRouter + Deliverable 模式** — 职责清晰，根据设备能力路由响应格式
3. **VoiceInteractionManager** — 统一 STT/TTS 入口，引擎可切换，状态机清晰
4. **AgentResponse 结构化响应** — `voiceText`/`richContent`/`fallbackText` 三分离设计合理
5. **A2UICard 类型安全访问器** — `asWeatherCard()` 等模式匹配在需要时仍有价值

### 2.2 应该重构的

1. **统一解析入口** — 消除三处 `extractA2UIJsons`，单一 `A2UIParser` 对象
2. **清理渲染路径** — 旧格式卡片要么完全迁移到标准协议，要么作为独立渲染器，不应在标准协议渲染器内做转换
3. **语音播报剥离 A2UI 标签** — `onSpeakText` 应使用 `voiceText` 或清理后的纯文本
4. **合并 MessageBubble 实现** — 保留一套，删除 `EnhancedMessageBubble` 或将其能力合并
5. **surfaceId 生命周期管理** — 与消息 ID 绑定，而非时间戳

### 2.3 应该新增的

1. **A2UI 内容清理器** — 从消息文本中剥离 `[A2UI]...[/A2UI]` 标签，生成纯文本用于 TTS
2. **协议版本管理器** — 统一的版本协商和降级策略
3. **A2UI 渲染错误上报** — 统一的错误收集，便于监控

---

## 三、推荐方案

### 3.1 目标架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        目标架构 (重构后)                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────┐  │
│  │  LLM / Agent │───▶│ ChatViewModel│───▶│    ChatScreen        │  │
│  └──────────────┘    └──────┬───────┘    └──────────┬───────────┘  │
│                             │                       │              │
│                    ┌────────▼────────┐       ┌──────▼───────┐     │
│                    │ AgentResponse   │       │ MessageBubble│     │
│                    │ Parser          │       │ (统一实现)    │     │
│                    └────────┬────────┘       └──────┬───────┘     │
│                             │                       │             │
│                    ┌────────▼────────┐       ┌──────▼───────┐     │
│                    │ ResponseRouter  │       │ A2UIParser   │     │
│                    │ → Deliverable   │       │ (单一入口)    │     │
│                    └────────┬────────┘       └──────┬───────┘     │
│                             │                       │             │
│                    ┌────────▼────────┐       ┌──────▼───────┐     │
│                    │ VoiceInteraction│       │ A2UIRenderer │     │
│                    │ Manager         │       │ (标准协议)    │     │
│                    └────────┬────────┘       └──────────────┘     │
│                             │                                    │
│                    ┌────────▼────────┐                            │
│                    │ A2UITextStripper│                            │
│                    │ (剥离标签→纯文本)│                            │
│                    └─────────────────┘                            │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流 (重构后)

```
LLM 响应
    │
    ▼
AgentResponseParser.parse()
    ├── voiceText: "今天晴天，25度"          ← 干净的语音文本
    ├── richContent: null                   ← 结构化内容 (可选)
    └── fallbackText: "今天晴天，25度 [A2UI]{...}[/A2UI]"  ← 完整文本

ResponseRouter.route()
    ├── Deliverable.Voice(voiceText)        ← 自动语音播报用 voiceText
    └── message.content = fallbackText      ← 气泡显示完整内容

MessageBubble
    │
    ├── A2UIParser.parse(content)
    │   ├── TextSegment("今天晴天，25度 ")
    │   └── StandardProtocolSegment({...})
    │
    └── 渲染:
        ├── TextSegment → Text()
        └── StandardProtocolSegment → A2UIRenderer → Compose

手动语音播报 (点击喇叭图标)
    │
    ├── 使用 voiceText (如果 AgentResponse 中有)
    └── 否则: A2UITextStripper.strip(content) → 纯文本 → TTS
```

### 3.3 实施路径

#### Phase 1: 统一解析层 (P0)

```
新增: app/src/main/java/ai/openclaw/android/ui/A2UIParser.kt
```

```kotlin
/**
 * 统一的 A2UI 解析入口。
 * 消除 A2UICardParser、A2UIComposeRenderer、A2UIParseUtils 三处重复逻辑。
 */
object A2UIParser {

    /** 从文本中提取所有 [A2UI]...[/A2UI] 标签内的 JSON */
    fun extractA2UITags(content: String): List<A2UIExtract> { ... }

    /** 将原始文本解析为 MessageSegment 列表 */
    fun parseSegments(content: String): List<MessageSegment> { ... }

    /** 从消息内容中剥离 A2UI 标签，返回纯文本 (用于 TTS) */
    fun stripA2UITags(content: String): String { ... }

    /** 检测 JSON 是否为标准 A2UI 协议格式 */
    fun isStandardProtocol(json: String): Boolean { ... }

    /** 检测 JSON 是否为旧版卡片格式 */
    fun isLegacyCard(json: String): Boolean { ... }
}

data class A2UIExtract(
    val preText: String,          // 标签前的文本
    val json: String,             // 标签内的 JSON
    val protocolType: ProtocolType // STANDARD | LEGACY | UNKNOWN
)

enum class ProtocolType { STANDARD, LEGACY, UNKNOWN }
```

**变更**:
- 删除 `A2UICardParser` 中的重复逻辑，委托给 `A2UIParser`
- 删除 `A2UIComposeRenderer` 中的 `extractA2UIJsons`、`isStandardProtocol`、`convertLegacyCardToProtocol`
- 删除 `A2UIParseUtils` (测试工具可保留，但委托给 `A2UIParser`)

#### Phase 2: 语音播报修复 (P0)

```kotlin
// ChatScreen.kt — 修改语音播报回调
// 当前: onSpeakText?.invoke(message.content)  // ❌ 包含 [A2UI] 标签
// 修复: onSpeakText?.invoke(strippedText)     // ✅ 纯文本
```

**方案 A (推荐)**: 在 `ChatMessage` 中存储 `voiceText` 字段

```kotlin
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val images: List<ImageContent>? = null,
    val voiceText: String? = null  // ← 新增: 干净的语音播报文本
)
```

ChatViewModel 在 `SessionEvent.Complete` 时设置:
```kotlin
val parsedResponse = parseAgentResponse(event.fullText)
val voiceText = parsedResponse.voiceText 
    ?: A2UIParser.stripA2UITags(event.fullText)  // fallback

updated[responseIndex] = current.copy(
    content = displayText,
    voiceText = voiceText
)
```

MessageBubble 语音播报按钮:
```kotlin
IconButton(onClick = { onSpeakText?.invoke(message.voiceText ?: A2UIParser.stripA2UITags(message.content)) }) {
    Icon(Icons.Default.VolumeUp, "语音播报")
}
```

**方案 B (快速修复)**: 如果不想改数据模型，直接在 UI 层剥离

```kotlin
// 在 MessageBubble 中
val speakableText = remember(message.content) { A2UIParser.stripA2UITags(message.content) }
IconButton(onClick = { onSpeakText?.invoke(speakableText) }) { ... }
```

#### Phase 3: 清理渲染路径 (P1)

**选择一: 废弃旧版卡片系统**

如果 LLM 端已全面切换到标准协议:
1. 删除 `A2UICardRouter`、`A2UICards.kt` 中的 14 种卡片
2. 删除 `A2UICardModels.kt` 中的卡片数据类
3. `A2UIParser.parseSegments()` 只返回 `Text` 和 `StandardProtocol`
4. 旧格式 JSON 直接走 `convertLegacyCardToProtocol()` 转成标准协议渲染

**选择二: 保留双系统但隔离**

如果需要同时支持:
1. 将旧版渲染器移到独立模块 `ai.openclaw.android.ui.legacy`
2. `A2UIParser` 只负责分类，不负责转换
3. `MessageBubble` 根据 `MessageSegment` 类型路由到不同渲染器:
   ```kotlin
   when (segment) {
       is Text -> Text(segment.text)
       is LegacyCard -> LegacyCardRenderer(segment.card)  // 独立渲染器
       is StandardProtocol -> A2UIRenderer(segment.json)
   }
   ```

#### Phase 4: 合并 MessageBubble (P1)

1. 保留 `ChatScreen.kt` 中的 `MessageBubble` (当前活跃版本)
2. 将 `MessageBubble.kt` 中的 `EnhancedMessageBubble` 删除或标记为废弃
3. 如果需要增强功能 (渐变背景、AI 头像等)，合并到 `ChatScreen.kt` 的版本中
4. 统一使用 `A2UIRendererWithErrorBoundary` 包裹标准协议渲染

#### Phase 5: surfaceId 生命周期管理 (P2)

```kotlin
// 每条消息绑定一个稳定的 surfaceId
data class MessageRenderState(
    val messageId: String,
    val surfaceId: String,  // = "msg_${messageId}"
    val renderer: A2UIRenderer
)
```

- surfaceId 格式: `"msg_${message.id}"` (稳定、可预测)
- 消息销毁时释放 renderer (通过 `DisposableEffect`)
- 标准协议 JSON 中的 surfaceId 应与消息 ID 一致，否则警告

#### Phase 6: 协议版本管理 (P2)

```kotlin
object A2UIProtocol {
    const val SUPPORTED_VERSIONS = setOf("v0.8", "v0.9", "v0.10")
    const val DEFAULT_VERSION = "v0.10"
    
    fun negotiateVersion(requested: String?): String {
        return if (requested in SUPPORTED_VERSIONS) requested else DEFAULT_VERSION
    }
}
```

- 所有协议输出统一使用协商后的版本
- 旧格式转换时不再硬编码 v0.10，使用 `negotiateVersion()`

---

## 四、优先级排序

### P0 — 必须立即修复 (影响用户体验)

| 优先级 | 任务 | 原因 | 工作量 |
|--------|------|------|--------|
| P0-1 | 语音播报剥离 A2UI 标签 | TTS 朗读 JSON 标签是严重 UX 缺陷 | 小 (1-2h) |
| P0-2 | 统一 `extractA2UIJsons` 入口 | 三处重复逻辑导致维护困难和潜在 bug | 小 (2-3h) |
| P0-3 | 统一 `isStandardProtocol` 检测 | 两处检测逻辑不一致可能导致分类错误 | 小 (1h) |

### P1 — 近期优化 (提升代码质量)

| 优先级 | 任务 | 原因 | 工作量 |
|--------|------|------|--------|
| P1-1 | 清理 `A2UIComposeRenderer` 中的 legacy 转换 | 违反单一职责，标准协议渲染器不应处理旧格式 | 中 (4-6h) |
| P1-2 | 合并 MessageBubble 实现 | 两套实现增加维护成本 | 中 (3-4h) |
| P1-3 | 删除废弃代码 (`tryBuildA2UIMessage`, `A2UICardRouterLegacy`) | 减少代码噪音 | 小 (1h) |
| P1-4 | 统一错误处理 | 当前错误处理分散且不一致 | 中 (3-4h) |

### P2 — 长期改进 (架构升级)

| 优先级 | 任务 | 原因 | 工作量 |
|--------|------|------|--------|
| P2-1 | surfaceId 生命周期管理 | 当前 surfaceId 不稳定，影响状态管理 | 中 (4-6h) |
| P2-2 | 协议版本协商机制 | 硬编码版本不利于未来升级 | 小 (2h) |
| P2-3 | A2UI 渲染错误监控 | 缺乏错误上报，问题发现靠用户反馈 | 中 (4-6h) |
| P2-4 | 旧版卡片系统迁移/废弃 | 双系统并行增加维护成本 | 大 (1-2周) |

---

## 五、关键文件依赖关系

```
app/src/main/java/ai/openclaw/android/
├── ChatScreen.kt                          ← UI 入口，定义 MessageBubble
│   ├── MessageBubble()                    ← 实际使用的消息气泡
│   │   ├── UserMessageBubble()            ← 用户消息 (青蓝渐变)
│   │   └── AiMessageBubble()              ← AI 消息 (暗色+青色边框)
│   ├── A2UIRendererWithErrorBoundary()    ← 标准协议渲染包装器
│   └── VoiceStateIndicator()              ← 语音状态指示器
│
├── ui/
│   ├── A2UIComposeRenderer.kt             ← 标准协议渲染器 (需重构)
│   │   ├── extractA2UIJsons()             ← ❌ 重复实现
│   │   ├── isStandardProtocol()           ← ❌ 重复实现
│   │   ├── normalizeToJsonL()             ← JSON → JSONL 转换
│   │   ├── convertLegacyCardToProtocol()  ← ❌ 旧格式转换 (应移除)
│   │   └── A2UIParseUtils                 ← ❌ 测试工具 (重复)
│   │
│   ├── A2UICardModels.kt                  ← 旧版卡片数据模型
│   │   ├── A2UICard                       ← 旧版卡片类型
│   │   ├── A2UICardParser                 ← ❌ 解析器 (需统一)
│   │   ├── MessageSegment                 ← 消息分段类型
│   │   └── 14 种卡片数据类                ← WeatherCardData 等
│   │
│   ├── A2UICards.kt                       ← 旧版卡片 Compose 组件
│   │   ├── A2UICardRouter()               ← 旧版路由 (14 种卡片)
│   │   ├── A2UICardRouterLegacy()         ← ❌ 废弃路由
│   │   └── 14 种卡片 Composable           ← WeatherCard 等
│   │
│   ├── MessageBubble.kt                   ← ❌ 遗留文件 (EnhancedMessageBubble)
│   └── ScriptUiManager.kt                 ← ScriptEngine UI 管理
│
├── viewmodel/
│   └── ChatViewModel.kt                   ← 状态管理
│       ├── handleSessionEvent()           ← 响应处理核心
│       ├── parseAgentResponse()           ← 响应解析
│       └── testInjectA2UI()               ← 测试注入
│
├── domain/
│   ├── AgentResponse.kt                   ← 结构化响应模型
│   ├── AgentResponseParser.kt             ← 响应解析器
│   ├── ResponseRouter.kt                  ← 响应路由器
│   ├── Deliverable.kt                     ← 交付物模型 (sealed class)
│   └── DeviceCapabilities.kt              ← 设备能力检测
│
└── voice/
    ├── VoiceInteractionManager.kt         ← 语音管理统一入口
    ├── VoiceSession.kt                    ← 语音状态机
    ├── stt/                               ← 语音识别
    │   ├── SpeechToTextEngine.kt
    │   ├── AndroidSpeechRecognizer.kt
    │   └── SherpaSttEngine.kt
    └── tts/                               ← 语音合成
        ├── TextToSpeechEngine.kt
        ├── AndroidTTSEngine.kt
        └── SherpaTtsEngine.kt

android_compose/src/main/java/org/a2ui/compose/
├── rendering/
│   └── A2UIRenderer.kt                    ← ✅ 标准协议渲染引擎 (核心)
├── data/
│   ├── A2UIMessage.kt                     ← ✅ 协议消息模型
│   ├── DataModelProcessor.kt              ← ✅ 数据模型处理器
│   └── DataModelState.kt
├── rendering/
│   └── ComponentRegistry.kt               ← ✅ 组件注册表
└── ... (charts, effects, error, transport 等扩展模块)
```

---

## 六、总结

### 核心问题

1. **双协议系统并行** — 旧版 A2UICard (14 种硬编码卡片) 和标准 A2UI 协议共存，通过分支判断路由，维护成本高
2. **代码重复严重** — `extractA2UIJsons`、`isStandardProtocol`、`convertLegacyCardToProtocol` 各有三处实现
3. **语音播报朗读 JSON** — 手动语音播报直接传入包含 `[A2UI]` 标签的原始内容，TTS 会朗读 JSON 字符串
4. **文件分裂** — `ChatScreen.kt` 和 `MessageBubble.kt` 各有一套 MessageBubble 实现

### 推荐行动

**立即执行 (本周)**:
1. 创建统一的 `A2UIParser` 对象，消除三处重复逻辑
2. 修复语音播报，使用 `voiceText` 或剥离标签后的纯文本
3. 删除废弃代码 (`tryBuildA2UIMessage`, `A2UICardRouterLegacy`)

**近期执行 (本月)**:
4. 清理 `A2UIComposeRenderer` 中的 legacy 转换逻辑
5. 合并 MessageBubble 实现
6. 统一错误处理

**长期规划 (下季度)**:
7. surfaceId 生命周期管理
8. 协议版本协商机制
9. 评估旧版卡片系统的去留

---

*文档结束*
