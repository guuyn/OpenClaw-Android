# OpenClaw-Android 会话机制设计

**日期**: 2026-04-06  
**状态**: 已批准  
**目标**: 实现长期持久会话 + 分层压缩 + SQLite/Room 存储

---

## 1. 需求概述

| 需求项 | 决策 |
|--------|------|
| 会话类型 | 长期持久 |
| 压缩策略 | 分层压缩（摘要 + 保留最近消息） |
| 存储方案 | SQLite/Room |
| 管理模式 | 混合（默认自动 + 命名手动） |

---

## 2. 数据模型

```kotlin
// 会话实体
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val name: String?,              // null = 默认会话
    val createdAt: Long,
    val lastActiveAt: Long,
    val tokenCount: Int,
    val status: SessionStatus       // ACTIVE, COMPRESSED, ARCHIVED
)

// 消息实体
@Entity(tableName = "messages", 
        foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,          // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long,
    val tokenCount: Int
)

// 压缩摘要
@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val sessionId: String,
    val content: String,            // LLM 生成的摘要
    val messageRangeStart: Long,    // 涵盖的消息 ID 范围
    val messageRangeEnd: Long,
    val compressedAt: Long
)
```

---

## 3. 核心组件架构

### 3.1 HybridSessionManager

```kotlin
class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val llmClient: LocalLLMClient,
    private val tokenCounter: TokenCounter
) {
    private var currentSession: SessionEntity? = null
    
    private val config = SessionConfig(
        maxTokens = 1800,           // 触发压缩阈值（总 2048 的 88%）
        preserveRecentMessages = 10, // 保留最近 N 条原文
        autoCompressDefault = true   // 默认会话自动压缩
    )
    
    // 核心方法
    suspend fun initialize(): SessionEntity
    suspend fun addMessage(role: MessageRole, content: String): Result<MessageEntity>
    suspend fun getConversationContext(): List<Message>
    suspend fun compressIfNeeded(force: Boolean = false)
    
    // 会话管理
    suspend fun createNamedSession(name: String): SessionEntity
    suspend fun switchSession(sessionId: String)
    suspend fun listSessions(): List<SessionMeta>
    suspend fun endSession(sessionId: String)
}
```

### 3.2 SessionCompressor

```kotlin
class SessionCompressor(
    private val llmClient: LocalLLMClient,
    private val summaryDao: SummaryDao
) {
    suspend fun compress(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity>
}
```

### 3.3 TokenCounter

```kotlin
class TokenCounter {
    // 粗略估算：中文约 1.5 字/token，英文约 0.25 词/token
    fun estimate(text: String): Int {
        val chineseChars = text.count { it.code > 0x4E00 && it.code < 0x9FFF }
        val englishWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return (chineseChars * 0.67 + englishWords * 1.3).toInt()
    }
    
    // 精确计数（使用模型自带的 tokenizer，如果可用）
    fun countExact(text: String, tokenizer: Tokenizer?): Int {
        return tokenizer?.encode(text)?.size ?: estimate(text)
    }
}
```

---

## 4. 压缩流程

```
用户消息 → addMessage() → 检查 tokenCount
                           ↓
                     tokenCount > 阈值?
                           ↓ Yes
                     compressIfNeeded()
                           ↓
                     分离消息（旧消息 vs 保留消息）
                           ↓
                     调用 LLM 生成摘要
                           ↓
                     存储摘要到 summaries
                           ↓
                     删除已压缩消息
                           ↓
                     getConversationContext() 返回: [摘要] + [最近消息]
```

---

## 5. 压缩提示词

```kotlin
object CompressionPrompts {
    val SUMMARIZE_SYSTEM = """
你是一个会话摘要助手。请将以下对话历史压缩成简洁的摘要。

要求：
1. 保留关键决策和结论
2. 保留用户偏好和重要信息
3. 保留未完成的任务或待办事项
4. 使用要点列表格式
5. 控制在 200 字以内

输出格式：
## 关键信息
- ...
## 用户偏好
- ...
## 待办事项
- ...
""".trimIndent()

    fun buildPrompt(messages: List<MessageEntity>): String {
        val history = messages.joinToString("\n") { 
            "${it.role.name}: ${it.content}" 
        }
        return "请压缩以下对话：\n\n$history"
    }
}
```

---

## 6. Token 预算分配

```
总预算: 2048 tokens
├── 系统提示词: ~300 tokens
├── 摘要（压缩后）: ~200 tokens
├── 最近消息（原文）: ~1000 tokens
├── 用户输入: ~200 tokens
└── 预留（工具调用等）: ~348 tokens

触发压缩阈值: 1800 tokens (88%)
保留最近消息: 10 条
```

---

## 7. 错误处理

| 情况 | 处理策略 |
|------|---------|
| 消息太少（≤10 条） | 跳过压缩 |
| LLM 不可用 | 简单截断作为摘要 |
| 压缩超时（>30s） | 取消压缩，删除最旧消息 |
| Token 硬限制（>1.5x） | 强制压缩 |
| 数据库损坏 | 回退内存模式 |
| 应用重启 | 从数据库恢复会话 |

```kotlin
sealed class SessionError {
    object DatabaseError : SessionError()
    object CompressionFailed : SessionError()
    object TokenLimitExceeded : SessionError()
    object SessionNotFound : SessionError()
    data class LLMError(val message: String) : SessionError()
}
```

---

## 8. 测试策略

### 8.1 单元测试

```kotlin
@Test
fun testTokenCount_and_triggerCompression() = runTest {
    val manager = HybridSessionManager(...)
    
    repeat(20) { i ->
        manager.addMessage(MessageRole.USER, "这是第 $i 条消息，内容较长...")
    }
    
    val messages = messageDao.getBySession(manager.currentSession!!.sessionId)
    assertTrue(messages.size < 20)
    
    val summary = summaryDao.getBySession(manager.currentSession!!.sessionId)
    assertNotNull(summary)
}

@Test
fun testCompression_preservesRecentMessages() = runTest {
    val manager = HybridSessionManager(...)
    
    val allMessages = mutableListOf<String>()
    repeat(30) { i ->
        val msg = "消息 $i"
        allMessages.add(msg)
        manager.addMessage(MessageRole.USER, msg)
    }
    
    manager.compressIfNeeded(force = true)
    
    val messages = messageDao.getBySession(manager.currentSession!!.sessionId)
    val recentOriginals = allMessages.takeLast(10)
    recentOriginals.forEach { original ->
        assertTrue(messages.any { it.content == original })
    }
}
```

### 8.2 测试覆盖清单

| 测试场景 | 类型 | 优先级 |
|---------|------|--------|
| Token 计数准确 | 单元 | P0 |
| 压缩触发正确 | 单元 | P0 |
| 摘要保留关键信息 | 集成 | P0 |
| 应用重启恢复 | 集成 | P1 |
| 多会话切换 | 集成 | P1 |
| LLM 不可用降级 | 单元 | P2 |
| 压缩超时处理 | 单元 | P2 |
| 并发安全 | 压力 | P2 |

---

## 9. TDD 开发顺序

1. 先写 `TokenCounter` 测试 → 实现
2. 先写 `SessionDao/MessageDao` 测试 → 实现
3. 先写压缩触发测试 → 实现 `compressIfNeeded`
4. 先写摘要生成测试 → 实现 `SessionCompressor`
5. 集成测试 → 完成 `HybridSessionManager`

---

## 10. 文件结构

```
app/src/main/java/com/openclaw/android/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── SessionDao.kt
│   │   ├── MessageDao.kt
│   │   └── SummaryDao.kt
│   └── model/
│       ├── SessionEntity.kt
│       ├── MessageEntity.kt
│       └── SummaryEntity.kt
├── domain/
│   ├── session/
│   │   ├── HybridSessionManager.kt
│   │   ├── SessionCompressor.kt
│   │   └── TokenCounter.kt
│   └── model/
│       ├── Session.kt
│       ├── Message.kt
│       └── SessionConfig.kt
└── util/
    └── CompressionPrompts.kt

app/src/test/java/com/openclaw/android/
├── TokenCounterTest.kt
├── SessionDaoTest.kt
├── SessionCompressorTest.kt
└── HybridSessionManagerTest.kt
```

---

**批准人**: 用户123767  
**批准时间**: 2026-04-06 10:30