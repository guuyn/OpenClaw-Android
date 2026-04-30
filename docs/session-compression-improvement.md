# 会话压缩质量提升设计方案

> **Created:** 2026-04-30
> **Status:** 设计阶段
> **Scope:** SessionCompressor + HybridSessionManager + CompressionPrompts

---

## 一、当前问题诊断

### 1.1 架构层面

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 压缩逻辑重复 | SessionCompressor.compress() vs HybridSessionManager.performCompression() | P0 |
| 两套 prompt 格式不一致 | CompressionPrompts vs HybridSessionManager.generateSummary() | P0 |
| 摘要更新不同步 | SessionCompressor 只写 DB，HybridSessionManager 写 DB + 更新缓存 | P0 |

**根因**：`SessionCompressor` 是独立的压缩服务，`HybridSessionManager` 也有自己的压缩逻辑（`generateSummary` + `performCompression`），两者各自为政。

### 1.2 Prompt 质量

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 增量压缩是简单拼接 | `generateSummary()` 中旧摘要 + 新消息直接拼接 | P0 |
| 输出格式不统一 | CompressionPrompts 要求结构化，generateSummary 只要求"要点列表" | P0 |
| 缺乏语义重要性评估 | 压缩只看 tokenCount > maxTokens | P1 |
| 首次压缩 prompt 过于简单 | 5 行 prompt，没有结构化输出要求 | P0 |

### 1.3 缓存

| 问题 | 说明 | 严重度 |
|------|------|--------|
| summaryCache 只是性能层 | Room DB 已有持久化，缓存不丢失数据 | P1 |
| getCurrentSummary 无索引 | SummaryDao 查询可用，但每次查 DB | P1 |

---

## 二、设计方案

### 2.1 核心决策：统一压缩引擎

**方案：将 HybridSessionManager 的压缩逻辑完全委托给 SessionCompressor，删除 generateSummary()。**

```
Before:
  HybridSessionManager
    ├── compressIfNeeded() → performCompression() → generateSummary()  ❌ 重复
    └── getConversationContext()

  SessionCompressor
    └── compress()  ❌ 未被 HybridSessionManager 使用

After:
  HybridSessionManager
    ├── compressIfNeeded() → SessionCompressor.compressIncremental()  ✅ 统一
    └── getConversationContext()

  SessionCompressor
    ├── compress()            — 首次压缩（批量）
    └── compressIncremental() — 增量压缩（合并旧摘要）
```

**理由：**
- SessionCompressor 已经有 LLM client 和 SummaryDao 依赖，天然适合做唯一压缩入口
- HybridSessionManager 保留压缩决策（何时压缩），委托压缩执行（如何压缩）
- 消除两套 prompt 格式不一致

---

### 2.2 P0-2: 结构化增量压缩 Prompt

#### 2.2.1 结构化摘要格式（输出规范）

所有压缩输出必须遵循以下统一格式：

```markdown
## 关键决策
- 决定采用 Kotlin 2.3.0 作为开发语言
- 选择 Bailian API 作为默认 LLM 提供商

## 用户偏好
- 喜欢深色模式界面
- 不希望推送非关键通知
- 偏好简洁的回复风格

## 待办事项
- 需要完善 AgentRouter 的关键词匹配逻辑
- 待修复通知权限请求流程

## 事实信息
- 当前会话涉及 OpenClaw-Android 项目
- 使用了 Room + KSP 作为本地数据库方案
```

**四类定义：**
- **关键决策**：已经做出的选择、确定下来的方案
- **用户偏好**：用户的个人偏好、习惯、风格要求
- **待办事项**：明确提到要做但还没完成的事情
- **事实信息**：重要事实、数据、项目信息（不属于以上三类）

#### 2.2.2 新 CompressionPrompts 设计

```kotlin
// CompressionPrompts.kt

object CompressionPrompts {

    /**
     * 结构化摘要的系统 prompt
     * 要求 LLM 按四类输出，便于后续增量合并
     */
    val STRUCTURED_SUMMARIZE_SYSTEM = """
你是一个会话摘要助手。请将对话历史压缩为结构化摘要。

必须按以下四个类别输出（每类用 ## 标题，用 - 列表项）：

## 关键决策
- 对话中做出的重要决定、确定的方案选择
- 只保留已确定的决策，不保留讨论过程

## 用户偏好
- 用户表达的个人偏好、习惯、风格要求
- 包括明确说出的偏好和从上下文中可推断的偏好

## 待办事项
- 对话中提到但尚未完成的任务
- 不包括已完成的事项

## 事实信息
- 重要的事实、数据、项目信息、技术选型
- 不属于以上三类的其他重要信息

规则：
1. 每个类别如果没有内容，输出"（无）"，不要省略类别标题
2. 每条要点一句话，不超过 30 字
3. 删除寒暄、重复、已解决的讨论
4. 总字数控制在 300 字以内
5. 只输出结构化摘要，不要解释你的过程
""".trimIndent()

    /**
     * 增量压缩系统 prompt
     * 专门用于合并旧摘要 + 新对话
     */
    val INCREMENTAL_SUMMARIZE_SYSTEM = """
你是一个会话摘要合并助手。你需要将旧摘要和新对话合并为更新后的摘要。

输入：
- 旧摘要：已有的结构化摘要（包含关键决策/用户偏好/待办事项/事实信息四类）
- 新对话：最近产生的对话内容

输出：更新后的完整结构化摘要，格式与旧摘要相同。

合并规则：
1. 【关键决策】旧摘要中的决策保留，新对话中新增的决策也加入。如果新决策覆盖了旧决策，用新的替换旧的。
2. 【用户偏好】合并所有偏好。新对话中如果用户改变了偏好，用新的替换旧的。
3. 【待办事项】已完成的事项从待办中移除。新对话中新增的未完成事项加入。
4. 【事实信息】合并所有重要事实。过时的事实可以删除。
5. 每个类别如果没有内容，输出"（无）"。
6. 每条要点一句话，不超过 30 字。
7. 总字数控制在 300 字以内。
8. 只输出结构化摘要，不要解释你的过程。
""".trimIndent()

    /**
     * 构建首次压缩 prompt
     */
    fun buildFirstCompressPrompt(messages: List<MessageEntity>): String {
        val history = messages.joinToString("\n") { 
            "${it.role.name}: ${it.content.take(500)}" 
        }
        return "请压缩以下对话为结构化摘要：\n\n$history"
    }

    /**
     * 构建增量压缩 prompt
     */
    fun buildIncrementalPrompt(
        previousSummary: String,
        newMessages: List<MessageEntity>
    ): String {
        val newHistory = newMessages.joinToString("\n") {
            "${it.role.name}: ${it.content.take(500)}"
        }
        return """旧摘要：
$previousSummary

新对话：
$newHistory

请合并旧摘要和新对话，输出更新后的结构化摘要：""".trimIndent()
    }
}
```

---

### 2.3 P0-1: 统一压缩引擎实现

#### 2.3.1 SessionCompressor 增强

```kotlin
// SessionCompressor.kt

class SessionCompressor(
    private val llmClient: LocalLLMClient,
    private val summaryDao: SummaryDao,
    private val tokenCounter: TokenCounter,  // 新增依赖
    private val isLlmReady: (() -> Boolean)? = null
) {

    /**
     * 首次压缩（无旧摘要）
     * 替换原来的 compress() 方法
     */
    suspend fun compressFirstTime(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        if (messages.size <= preserveRecent) return@runCatching null
        
        val toCompress = messages.dropLast(preserveRecent)
        if (toCompress.isEmpty()) return@runCatching null

        val modelReady = isLlmReady?.invoke() ?: llmClient.isModelLoaded()
        if (!modelReady) {
            return@runCatching createSimpleSummary(session, toCompress)
        }

        val summary = compressWithLlm(
            systemPrompt = CompressionPrompts.STRUCTURED_SUMMARIZE_SYSTEM,
            userPrompt = CompressionPrompts.buildFirstCompressPrompt(toCompress),
            fallbackText = { createSimpleSummaryText(toCompress) }
        )

        SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    /**
     * 增量压缩（合并旧摘要）
     * 替换 HybridSessionManager.performCompression() 中的 generateSummary()
     */
    suspend fun compressIncremental(
        session: SessionEntity,
        messages: List<MessageEntity>,
        existingSummary: SummaryEntity,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        val toCompress = if (messages.size > preserveRecent) {
            messages.dropLast(preserveRecent)
        } else {
            return@runCatching null
        }
        if (toCompress.isEmpty()) return@runCatching null

        val modelReady = isLlmReady?.invoke() ?: llmClient.isModelLoaded()
        if (!modelReady) {
            return@runCatching createSimpleSummary(session, toCompress)
        }

        val summary = compressWithLlm(
            systemPrompt = CompressionPrompts.INCREMENTAL_SUMMARIZE_SYSTEM,
            userPrompt = CompressionPrompts.buildIncrementalPrompt(
                previousSummary = existingSummary.content,
                newMessages = toCompress
            ),
            fallbackText = { "${existingSummary.content}\n[新增: ${createSimpleSummaryText(toCompress)}]" }
        )

        SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    /**
     * 通用 LLM 压缩方法（消除重复）
     */
    private suspend fun compressWithLlm(
        systemPrompt: String,
        userPrompt: String,
        fallbackText: () -> String
    ): String {
        return withTimeoutOrNull(30_000) {
            val response = llmClient.chat(
                listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                )
            ).getOrNull()
            response?.content?.takeIf { it.isNotBlank() } ?: fallbackText()
        } ?: fallbackText()
    }

    private fun createSimpleSummary(
        session: SessionEntity,
        toCompress: List<MessageEntity>
    ): SummaryEntity {
        return SummaryEntity(
            sessionId = session.sessionId,
            content = createSimpleSummaryText(toCompress),
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    private fun createSimpleSummaryText(messages: List<MessageEntity>): String {
        return messages.take(3).joinToString("; ") {
            "${it.role.name.first()}: ${it.content.take(50)}..."
        }.let { "早期对话摘要: $it" }
    }
}
```

#### 2.3.2 HybridSessionManager 瘦身

```kotlin
// HybridSessionManager.kt 变更

class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val sessionCompressor: SessionCompressor,  // 替换 llmClient
    private val tokenCounter: TokenCounter,
    private val memoryManager: MemoryManager? = null
) {

    // 删除: generateSummary() 方法 — 完全移除
    // 删除: 内联的 LLM 调用和 prompt 构建
    // 删除: summaryCache — 改用 DB 直查（见下方 P1-2）

    /**
     * 执行压缩操作 — 委托给 SessionCompressor
     */
    private suspend fun performCompression() {
        val session = currentSession ?: return
        val sessionId = session.sessionId

        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        if (allMessages.isEmpty()) return

        val existingSummary = summaryDao.getSummaryBySessionId(sessionId)

        val result = if (existingSummary != null) {
            // 增量压缩
            sessionCompressor.compressIncremental(
                session = session,
                messages = allMessages,
                existingSummary = existingSummary
            )
        } else {
            // 首次压缩
            sessionCompressor.compressFirstTime(
                session = session,
                messages = allMessages
            )
        }

        val summaryEntity = result.getOrNull() ?: return

        // 保存摘要到 DB
        summaryDao.insertSummary(summaryEntity)

        // 删除已压缩的消息
        val messagesToCompress = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.take(allMessages.size - config.preserveRecentMessages)
        } else {
            emptyList()
        }
        val messageIdsToDelete = messagesToCompress.map { it.id }
        messageDao.deleteMessagesByIds(sessionId, messageIdsToDelete)

        // 更新 token 计数
        val remainingMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val newTokenCount = remainingMessages.sumOf { it.tokenCount.toLong() }.toInt()
        currentSession = currentSession?.copy(tokenCount = newTokenCount)
        sessionDao.updateSession(currentSession!!)

        Log.d(TAG, "Session compressed: sessionId=$sessionId, summaryLength=${summaryEntity.content.length}")

        // 跨会话记忆关联
        extractMemoriesFromSummary(summaryEntity.content, sessionId)
    }

    /**
     * 获取摘要 — 简化为直查 DB（删除 LRU 缓存）
     * 理由：Room 查询已经很快，且每个会话只有一条摘要记录
     * 如果后续发现性能问题，可以加 Room 索引
     */
    private suspend fun getLatestSummary(sessionId: String): SummaryEntity? {
        return summaryDao.getSummaryBySessionId(sessionId)
    }

    // 替换 getConversationContext() 中的 getCachedSummary 调用
    // 原: val latestSummary = getCachedSummary(sessionId)
    // 新: val latestSummary = getLatestSummary(sessionId)

    // 删除以下方法：
    // - getCachedSummary()
    // - invalidateSummaryCache()
    // - summaryCache 字段
}
```

**变更摘要：**
- 删除 `generateSummary()` 及所有内联 prompt
- 删除 `summaryCache` 及相关方法
- `llmClient` 依赖 → 改为 `sessionCompressor` 依赖
- `performCompression()` 改为委托调用

---

### 2.4 P0: SummaryDao 索引优化

```sql
// 在 AppDatabase.kt 中增加索引（迁移）

@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, ...],
    version = 3,  // 从 2 升到 3
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    // ... 现有代码 ...
}

// 修改 SummaryEntity:
@Entity(
    tableName = "summaries",
    indices = [Index(value = ["sessionId"], unique = true)]
)
data class SummaryEntity(
    @PrimaryKey val sessionId: String,
    val content: String,
    val messageRangeStart: Long,
    val messageRangeEnd: Long,
    val compressedAt: Long
)
```

> **说明**：`sessionId` 已经是 `@PrimaryKey`，Room 自动为其创建索引。显式声明 `Index(unique = true)` 让意图更明确。如果 DB version 已 > 1 且摘要表已存在，需要写 Migration。

---

### 2.5 P1-1: 智能压缩触发

#### 2.5.1 方案：语义重要性评估

当前 `compressIfNeeded()` 只看 `tokenCount > maxTokens`，改进为：

```kotlin
// SessionConfig.kt — 新增配置
data class SessionConfig(
    val maxTokens: Int = 1800,
    val preserveRecentMessages: Int = 10,
    val autoCompressDefault: Boolean = true,
    val importanceThreshold: Float = 0.7f,    // 重要性阈值，低于此值才压缩
    val minMessagesForCompress: Int = 5       // 至少 N 条消息才触发压缩
)
```

```kotlin
// HybridSessionManager.kt — 修改 compressIfNeeded()

suspend fun compressIfNeeded(force: Boolean = false) {
    val session = currentSession ?: return

    if (force) {
        performCompression()
        return
    }

    if (!config.autoCompressDefault) return

    val allMessages = messageDao.getMessagesBySessionId(session.sessionId).firstOrNull() ?: emptyList()
    val messagesToCompress = allMessages.take(allMessages.size - config.preserveRecentMessages)

    // 条件1: token 数超标
    val tokenOverflow = session.tokenCount > config.maxTokens
    
    // 条件2: 消息数足够
    val enoughMessages = messagesToCompress.size >= config.minMessagesForCompress

    // 条件3: 语义重要性低（允许压缩）
    val isCompressible = if (tokenOverflow) {
        assessCompressionUrgency(messagesToCompress) < config.importanceThreshold
    } else {
        false
    }

    if (tokenOverflow && enoughMessages && isCompressible) {
        performCompression()
    } else if (tokenOverflow && enoughMessages) {
        // token 超标但重要性高 → 延迟压缩，等更多消息后一起压缩
        Log.d(TAG, "Compression deferred: high importance messages detected")
    }
}

/**
 * 评估待压缩消息的重要性
 * 返回 0.0 ~ 1.0，越高表示越重要（越不该压缩）
 */
private fun assessCompressionUrgency(messages: List<MessageEntity>): Float {
    if (messages.isEmpty()) return 0f

    var score = 0f
    val totalMessages = messages.size

    // 规则1: 用户消息中的关键词
    val importantUserKeywords = listOf(
        "记住", "重要", "注意", "关键", "必须", "一定", 
        "决定", "选择", "采用", "偏好", "喜欢",
        "请", "帮我", "需要", "要求",
        "TODO", "待办", "任务", "bug", "修复",
        "密码", "密钥", "token", "api"
    )
    val userMessages = messages.filter { it.role == MessageRole.USER }
    val importantUserCount = userMessages.count { msg ->
        importantUserKeywords.any { keyword -> msg.content.contains(keyword, ignoreCase = true) }
    }
    if (userMessages.isNotEmpty()) {
        score += (importantUserCount.toFloat() / userMessages.size) * 0.4f
    }

    // 规则2: 消息长度（长消息通常包含更多信息）
    val avgLength = messages.map { it.content.length }.average()
    if (avgLength > 200) {
        score += 0.2f
    } else if (avgLength > 100) {
        score += 0.1f
    }

    // 规则3: 包含工具调用（通常是技术讨论）
    val hasToolCalls = messages.any { 
        it.content.contains("[TOOL]") || it.content.contains("tool_call") 
    }
    if (hasToolCalls) {
        score += 0.2f
    }

    // 规则4: 对话轮次多（多轮讨论通常更重要）
    val turnCount = messages.count { it.role == MessageRole.USER }
    if (turnCount > 5) {
        score += 0.2f
    }

    return score.coerceIn(0f, 1f)
}
```

**设计思路：**
- 基于规则的快速评估（不用 LLM，避免额外开销）
- 4 个维度加权：用户意图 (40%)、消息长度 (20%)、工具调用 (20%)、讨论深度 (20%)
- 分数 > 0.7 时延迟压缩
- 如果 token 严重超标（> 2x maxTokens）则强制压缩，不管重要性

```kotlin
// 在 compressIfNeeded() 中加入强制阈值
val criticalTokenOverflow = session.tokenCount > config.maxTokens * 2
if (criticalTokenOverflow) {
    performCompression()
    return
}
```

---

## 三、依赖注入变更

```kotlin
// AppModule.kt — 更新 SessionCompressor 和 HybridSessionManager 的 DI

// 原:
// single { SessionCompressor(get(), get()) }
// single { HybridSessionManager(get(), get(), get(), get(), get(), get()) }

// 新:
single { TokenCounter() }
single { 
    SessionCompressor(
        llmClient = get(),
        summaryDao = get(),
        tokenCounter = get()
    )
}
single {
    HybridSessionManager(
        sessionDao = get(),
        messageDao = get(),
        summaryDao = get(),
        sessionCompressor = get(),  // 替换 llmClient
        tokenCounter = get(),
        memoryManager = get()
    )
}
```

---

## 四、迁移计划

### Phase 1: 基础设施（Day 1）
- [ ] 新增 `CompressionPrompts.STRUCTURED_SUMMARIZE_SYSTEM` 和 `INCREMENTAL_SUMMARIZE_SYSTEM`
- [ ] 新增 `SessionCompressor.compressIncremental()` 方法
- [ ] 新增 `assessCompressionUrgency()` 重要性评估
- [ ] 更新 `SessionConfig` 添加新参数

### Phase 2: 统一引擎（Day 2）
- [ ] 修改 `HybridSessionManager` 依赖：`llmClient` → `sessionCompressor`
- [ ] 删除 `HybridSessionManager.generateSummary()`
- [ ] 删除 `summaryCache` 及相关方法
- [ ] 更新 `performCompression()` 委托给 SessionCompressor
- [ ] 更新 `AppModule.kt` DI

### Phase 3: 清理（Day 3）
- [ ] 删除旧的 `CompressionPrompts.SUMMARIZE_SYSTEM` 和 `buildPrompt()`（如果未被其他地方引用）
- [ ] 跑全量测试：`:app:testDebugUnitTest` + `assembleDebug`
- [ ] 真机验证压缩流程

---

## 五、风险与回滚

| 风险 | 概率 | 缓解 |
|------|------|------|
| LLM 输出不符合结构化格式 | 中 | 在 SessionCompressor 中加格式验证，不符合则 fallback |
| 重要性评估规则不准确 | 低 | 参数可调（importanceThreshold），可通过配置调整 |
| 删除 summaryCache 影响性能 | 极低 | Room 查询单条记录 < 1ms，缓存收益可忽略 |
| 新 prompt 产生更差的摘要 | 低 | 先保留旧 prompt 作为 fallback，A/B 验证 |

---

## 六、预期效果

| 指标 | 当前 | 目标 |
|------|------|------|
| 摘要结构化程度 | ❌ 自由格式 | ✅ 四类固定格式 |
| 压缩逻辑重复 | 2 套 | 1 套（SessionCompressor） |
| 增量合并质量 | 简单拼接 | 结构化合并 |
| 压缩触发准确率 | 只看 token | token + 语义重要性 |
| 代码可维护性 | 分散 | 集中 |
