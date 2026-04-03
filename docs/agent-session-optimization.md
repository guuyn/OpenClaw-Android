# AgentSession 优化分析（第二轮)

> 更新日期: 2026-04-01
 | 目标: 手机版 OpenClaw 审时交互优化

## 当前架构（优化后)

```
用户输入 → MainScreen.sendMessage()
  → AgentSession.handleMessageStream()
    → 枬 flow { Flow: SessionEvent } 顺序发射 Token/Complete/Error
 事件
    → BailianClient.chatStream()
      → OkHttp SSE 逐行读取
      → Flow<ChatEvent> 输出给 AgentSession
  → Agent Loop:
        - 收到 Complete 事件 → 解析 toolCalls
        - 有工具 → 执行 → 继续循环
        - 无工具 → 输出最终文本
  → UI 实时渲染
```

---

## 已完成的优化

### 1. SSE 流式响应
 ✅ 已实现
**收益: 体感延迟从 10-30s → <1s** | | ---
| **问题**: `BailianClient.chatStream()` 中 `stream = false`，不会实际产生 SSE 流) | **文件**: `BailianClient.kt:109` |

 | **问题**: `GatewayManager` 仍使用 `maxContextLength = 20` 而非 `maxContextTokens`, | **文件**: `GatewayManager.kt:145` |
 | **问题**: `GatewayManager` 仅使用同步 `chat()` 而非 `chatStream()`, 后续需支持 streaming 需要切换| **文件**: `GatewayManager.kt:142-153` |

### 2. 娡型端 Function Calling ✅ 已实现
**收益: 消除正则解析不可靠 + 减少重试开销** | `ChatRequest` 的 `tools` 字段现可正确传递给 API。

 **注意**: `GatewayManager` 传递的参数名仍是旧名称,编译时会按名未匹配错误。 |

 | **问题**: `BailianClient.chatStream()` 中 `responseId` 和 `finishReason` 局部变量在 `flow {}` 中声明但传递给 `emitStreamEvents()` 后未被使用, 该函数内部使用自己的 `chunkId` 和局部 accumulator | | **文件**: `BailianClient.kt:123-136` |

 | **问题**: `response.body?.byteStream()?.bufferedReader()` 读取后 Response 未关闭, 如果协程因网络问题中途失败, 连接泄漏. | **文件**: `BailianClient.kt:164-234` |

### 3. 稡型端 System Prompt | ✅ 已精简
**收益: 减少 ~50% context token 开开销** | 从 ~170 行 → ~15 行 | 仅保留核心指令 + A2UI schema |

### 4. 基于 Token 的 History 管理 | ✅ 已实现
**收益**: 避免超限报错 / 上下文浪费** | 估算 ~1.3 token/CJK 字符, ~0.25 token/ASCII 字符)

### 5. 通用 Agent Loop | ✅ 已实现
**收益: 消除硬编码的 location→weather 陓成链,统一的最大 `MAX_TOOL_ROUNDS = 5` 謽制循环）

---

## 待修复的问题（按优先级排序)

### P0 - 关键问题

#### P0.1 chatStream 未启用流式输出

`BailianClient.kt:109` 设置了 `stream = false`,导致 `chatStream()` 宕整个请求完成后才一次性返回,仍然是非流式.

**文件**: `BailianClient.kt:109` — 攌为 `stream = true`

#### P0.2 GatewayManager 参数名不匹配

`GatewayManager.kt:145` 传递 `maxContextLength = 20`，但 `AgentSession` 构造函数参数已改为 `maxContextTokens`。 这会导致编译错误。

 **文件**: `GatewayManager.kt:145` — 改为 `maxContextTokens = 4000`

#### P0.3 GatewayManager 未切换到 Streaming API
`GatewayManager.kt:142-153` 仍使用 `AgentSession(..., maxContextLength = 20)` 同步初始化,但没有配置 streaming 模式。`handleMessageStream()` 未被使用.

 **文件**: `GatewayManager.kt:142-153` — 重构为支持 streaming

#### P0.4 BailianClient 资源泄漏
- `response.body` 的 `byteStream()` 在异常或协程取消后可能不会被关闭
 OkHttp Response 未显式关闭
 `responseId` 和 `finishReason` 变量在 flow builder 中声明但未使用

 **文件**: `BailianClient.kt:122-136` — 删除未使用的变量, 添加 `response.use { body?.close() }` 确保 Response 被关闭

---

### P1 - 重要优化

#### P1.1 OkHttpClient 连接未关闭
`BailianClient` 创建了 `OkHttpClient` 但从未调用 `dispatcher.executorService`、`connectionPool.evict()` 或在 `close()` 时清理. 也没有连接池管理. | **文件**: `BailianClient.kt:39-43` | 添加连接池/清理机制

#### P1.2 Response Body 未关闭
`emitStreamEvents()` 中 `response.body?.byteStream()?.bufferedReader()` 在 `reader.use {}` 块中使用了,但如果 `bufferedReader` 抛出异常, OkHttp Response 不会被关闭. | **文件**: `BailianClient.kt:164-234` | 添加 `finally { response.close() }` 或使用 `response.use { ... }` 模式

#### P1.3 History Trim 逻辑应优先保留最近上下文
当前从头部移除消息对（索引 0），但通常旧消息在头部而新消息在尾部,应优先保留最近的上下文, | **文件**: `AgentSession.kt:260-264` | 改为从尾部移除旧消息对

#### P1.4 对话持久化
应用重启后 `history` 全部丢失,没有持久化机制. Room/SQLite 持久化可以支持跨会话记忆保持. | **文件**: `AgentSession.kt` | 添加持久化层

#### P1.5 取消机制
用户发送新消息时如果上一个请求还在进行中,没有取消/中断处理. 可能导致消息乱序. | **文件**: `AgentSession.kt`, `MainActivity.kt` | 添加 `Job` 控制或 `Flow` 取消

#### P1.6 序列化优化
`BailianClient` 中 `Json` 配置使用 `encodeDefaults = true`,导致 JSON 中包含所有默认值字段（如 `stream: false`, `toolChoice: null`). 浪费 token. | **文件**: `BailianClient.kt:33-36` | 改为 `encodeDefaults = false`, 显式设置必要字段

---

### P2 - 改进建议

#### P2.1 A2UI 解析健壮性
`ChatScreen` 中 A2UI 检测使用 `content.startsWith("[A2UI]")`, 但如果 AI 输出中 `[A2UI]` 不在开头（比如前面有思考过程或换行）, A2UI 会被忽略. | **文件**: `ChatScreen.kt` | 改用正则搜索 `[A2UI]...[/A2UI]` 块

#### P2.2 多 Choice 处理
`ModelResponse.content` 使用 `choices?.firstOrNull()?.message?.content`,当模型返回多个 choices 时可能遗漏内容. | **文件**: `ModelModels.kt:71-72` | 遍历所有 choices 拼接或取第一个非空的

#### P2.3 线程一致性
`AgentSession.handleMessageStream()` 在 `Dispatchers.Default` 上运行,但内部 `executeToolCall` 在 `Dispatchers.IO` 上,线程切换频繁. | **文件**: `AgentSession.kt:191` | 整个 flow 统一在 `Dispatchers.IO` 上运行

#### P2.4 网络超时优化
`connectTimeout = 30s` 在移动网络环境下过长, `readTimeout = 120s` 过长. 缺少 `callTimeout` 整体超时. | **文件**: `BailianClient.kt:39-43` | 缩短 connectTimeout 到 10s, 增加 callTimeout 和 retryOnConnectionFailure

#### P2.5 API Key 安全
`MainActivity.kt:96-98` 硬编码了调试 API key. | **文件**: `MainActivity.kt:96-98` | 仅在 debug build 中包含,或使用 BuildConfig

#### P2.6 删除消息 UI
`ChatScreen.kt:318` 删除消息功能被注释掉. | **文件**: `ChatScreen.kt:318` | 实现或移除注释代码

#### P2.7 ChatViewModel 日志
`ChatViewModel.kt` 错误处理使用 `println` 而非 `Log`. | **文件**: `ChatViewModel.kt:43, 104` | 改用 `Log.e()`

#### P2.8 技能数动态化
`SettingsScreen` 中技能数量硬编码为 `8`. | **文件**: `MainActivity.kt:352-396` | 从 `SkillManager` 动态获取

#### P2.9 技能自动注册
`SkillManager` 通过硬编码 `switch` 注册技能,新增技能需手动注册. | **文件**: `SkillManager.kt:114-131` | 使用反射或配置文件自动发现

#### P2.10 前台服务保活
`GatewayService` 缺少 `START_STICKY` 标志,系统杀进程后不会自动恢复. | **文件**: `GatewayService.kt` | 实现 `onStartCommand()` 返回 `START_STICKY`

---

### P3 - 锦上添花

#### P3.1 Token 用量统计
`ModelResponse.usage` 包含 token 用量数据但未被使用. 无法追踪 API 调用成本. | **建议**: 从 usage 中提取并暴露给上层,便于 UI 显示或成本控制

#### P3.2 调试日志清理
生产构建中应移除或降低 `Log.d` 绚别日志. | **建议**: 使用 Timber 或条件日志

#### P3.3 权限自动请求
`SkillManager.checkSkillPermissions()` 只检查但不请求权限. | **建议**: 检查失败时自动发起权限请求

---

## 文件索引

| 文件 | 职责 |
|------|------|
| `agent/AgentSession.kt` | 会话管理、Agent Loop、streaming API |
| `model/ModelClient.kt` | LLM 客户端接口 (chat + chatStream) |
| `model/BailianClient.kt` | 百炼 API 实现 (同步 + SSE) |
| `model/ModelModels.kt` | 数据模型 (Message, Tool, ChatRequest, StreamChunk) |
| `skill/SkillManager.kt` | 技能加载与执行 |
| `GatewayManager.kt` | 组件编排 |
| `MainActivity.kt` | 主界面 + 流式集成 |
| `ChatViewModel.kt` | ViewModel + 流式集成 |
| `ChatScreen.kt` | 聊天 UI + A2UI 渲染 |
