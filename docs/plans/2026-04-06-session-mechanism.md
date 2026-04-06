# 会话机制实现计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 实现长期持久会话 + 分层压缩 + SQLite/Room 存储

**Architecture:** 使用 Room 数据库存储会话和消息，HybridSessionManager 管理会话生命周期，SessionCompressor 负责分层压缩

**Tech Stack:** Kotlin, Room Database, Coroutines, JUnit 5

---

## Task 1: 数据模型与 DAO

**Files:**
- Create: `app/src/main/java/com/openclaw/android/data/local/AppDatabase.kt`
- Create: `app/src/main/java/com/openclaw/android/data/local/SessionDao.kt`
- Create: `app/src/main/java/com/openclaw/android/data/local/MessageDao.kt`
- Create: `app/src/main/java/com/openclaw/android/data/local/SummaryDao.kt`
- Create: `app/src/main/java/com/openclaw/android/data/model/SessionEntity.kt`
- Create: `app/src/main/java/com/openclaw/android/data/model/MessageEntity.kt`
- Create: `app/src/main/java/com/openclaw/android/data/model/SummaryEntity.kt`
- Test: `app/src/test/java/com/openclaw/android/SessionDaoTest.kt`

**Step 1: Write the failing test**

```kotlin
// SessionDaoTest.kt
@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sessionDao = db.sessionDao()
    }
    
    @After
    fun teardown() {
        db.close()
    }
    
    @Test
    fun insertSession_andRetrieveById() = runTest {
        val session = SessionEntity(
            sessionId = "test-session-1",
            name = null,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )
        
        sessionDao.insert(session)
        
        val retrieved = sessionDao.getById("test-session-1")
        assertEquals(session, retrieved)
    }
    
    @Test
    fun getDefaultSession_returnsNullName() = runTest {
        val session = SessionEntity(
            sessionId = "default",
            name = null,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )
        
        sessionDao.insert(session)
        
        val defaultSession = sessionDao.getDefaultSession()
        assertNotNull(defaultSession)
        assertNull(defaultSession?.name)
    }
}
```

**Step 2: Run test — confirm it fails**
Command: `./gradlew test --tests "com.openclaw.android.SessionDaoTest"`
Expected: FAIL — "Cannot access AppDatabase" or similar

**Step 3: Write minimal implementation**

```kotlin
// SessionEntity.kt
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val name: String?,
    val createdAt: Long,
    val lastActiveAt: Long,
    val tokenCount: Int,
    val status: SessionStatus
)

enum class SessionStatus {
    ACTIVE, COMPRESSED, ARCHIVED
}

// MessageEntity.kt
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val tokenCount: Int
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

// SummaryEntity.kt
@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val sessionId: String,
    val content: String,
    val messageRangeStart: Long,
    val messageRangeEnd: Long,
    val compressedAt: Long
)

// SessionDao.kt
@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)
    
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?
    
    @Query("SELECT * FROM sessions WHERE name IS NULL LIMIT 1")
    suspend fun getDefaultSession(): SessionEntity?
    
    @Query("SELECT * FROM sessions WHERE status = :status ORDER BY lastActiveAt DESC")
    suspend fun getByStatus(status: SessionStatus): List<SessionEntity>
    
    @Update
    suspend fun update(session: SessionEntity)
    
    @Query("UPDATE sessions SET tokenCount = :tokenCount WHERE sessionId = :sessionId")
    suspend fun updateTokenCount(sessionId: String, tokenCount: Int)
    
    @Query("UPDATE sessions SET lastActiveAt = :timestamp WHERE sessionId = :sessionId")
    suspend fun updateLastActive(sessionId: String, timestamp: Long)
    
    @Delete
    suspend fun delete(session: SessionEntity)
}

// MessageDao.kt
@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<MessageEntity>
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id IN (:ids)")
    suspend fun deleteByIds(sessionId: String, ids: List<Long>)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

// SummaryDao.kt
@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: SummaryEntity)
    
    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: String): SummaryEntity?
    
    @Delete
    suspend fun delete(summary: SummaryEntity)
}

// AppDatabase.kt
@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
}
```

**Step 4: Run test — confirm it passes**
Command: `./gradlew test --tests "com.openclaw.android.SessionDaoTest"`
Expected: PASS

**Step 5: Commit**
`git add . && git commit -m "feat(session): add Room database models and DAOs"`

---

## Task 2: TokenCounter

**Files:**
- Create: `app/src/main/java/com/openclaw/android/domain/session/TokenCounter.kt`
- Test: `app/src/test/java/com/openclaw/android/TokenCounterTest.kt`

**Step 1: Write the failing test**

```kotlin
// TokenCounterTest.kt
class TokenCounterTest {
    private val counter = TokenCounter()
    
    @Test
    fun estimate_chineseText() {
        val text = "这是一个测试消息"
        val tokens = counter.estimate(text)
        // 中文约 1.5 字/token，7 个字 ≈ 5 tokens
        assertTrue(tokens in 3..7)
    }
    
    @Test
    fun estimate_englishText() {
        val text = "This is a test message"
        val tokens = counter.estimate(text)
        // 英文约 0.25 词/token，4 个词 ≈ 5 tokens
        assertTrue(tokens in 3..7)
    }
    
    @Test
    fun estimate_mixedText() {
        val text = "这是 test 混合消息"
        val tokens = counter.estimate(text)
        assertTrue(tokens > 0)
    }
    
    @Test
    fun estimate_emptyString() {
        val tokens = counter.estimate("")
        assertEquals(0, tokens)
    }
}
```

**Step 2: Run test — confirm it fails**
Command: `./gradlew test --tests "com.openclaw.android.TokenCounterTest"`
Expected: FAIL — "Unresolved reference: TokenCounter"

**Step 3: Write minimal implementation**

```kotlin
// TokenCounter.kt
class TokenCounter {
    /**
     * 粗略估算 token 数量
     * 中文约 1.5 字/token，英文约 4 字/token
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        val nonChinese = text.length - chineseChars
        
        return (chineseChars * 0.67 + nonChinese * 0.25).toInt().coerceAtLeast(1)
    }
    
    /**
     * 精确计数（如果 tokenizer 可用）
     */
    fun countExact(text: String, tokenizer: Any?): Int {
        // TODO: 实现精确计数（需要模型 tokenizer）
        return estimate(text)
    }
}
```

**Step 4: Run test — confirm it passes**
Command: `./gradlew test --tests "com.openclaw.android.TokenCounterTest"`
Expected: PASS

**Step 5: Commit**
`git add . && git commit -m "feat(session): add TokenCounter for estimation"`

---

## Task 3: SessionCompressor

**Files:**
- Create: `app/src/main/java/com/openclaw/android/domain/session/SessionCompressor.kt`
- Create: `app/src/main/java/com/openclaw/android/util/CompressionPrompts.kt`
- Test: `app/src/test/java/com/openclaw/android/SessionCompressorTest.kt`

**Step 1: Write the failing test**

```kotlin
// SessionCompressorTest.kt
class SessionCompressorTest {
    private lateinit var compressor: SessionCompressor
    private lateinit var mockLlmClient: LocalLLMClient
    private lateinit var summaryDao: SummaryDao
    
    @Before
    fun setup() {
        mockLlmClient = mockk()
        summaryDao = mockk()
        compressor = SessionCompressor(mockLlmClient, summaryDao)
    }
    
    @Test
    fun compress_notEnoughMessages_returnsNull() = runTest {
        val session = createTestSession()
        val messages = (1..5).map { createTestMessage(it.toLong()) }
        
        val result = compressor.compress(session, messages, preserveRecent = 10)
        
        assertTrue(result.isFailure || result.getOrNull() == null)
    }
    
    @Test
    fun compress_enoughMessages_returnsSummary() = runTest {
        val session = createTestSession()
        val messages = (1..20).map { createTestMessage(it.toLong()) }
        
        coEvery { mockLlmClient.isAvailable() } returns true
        coEvery { mockLlmClient.summarize(any(), any()) } returns "测试摘要"
        coEvery { summaryDao.insert(any()) } just Runs
        
        val result = compressor.compress(session, messages, preserveRecent = 10)
        
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertEquals(session.sessionId, summary?.sessionId)
    }
    
    @Test
    fun compress_preservesCorrectRange() = runTest {
        val session = createTestSession()
        val messages = (1..20).map { createTestMessage(it.toLong()) }
        
        coEvery { mockLlmClient.isAvailable() } returns true
        coEvery { mockLlmClient.summarize(any(), any()) } returns "测试摘要"
        coEvery { summaryDao.insert(any()) } just Runs
        
        val result = compressor.compress(session, messages, preserveRecent = 10)
        
        val summary = result.getOrNull()
        // 应该压缩前 10 条（id 1-10），保留后 10 条（id 11-20）
        assertEquals(1L, summary?.messageRangeStart)
        assertEquals(10L, summary?.messageRangeEnd)
    }
    
    private fun createTestSession() = SessionEntity(
        sessionId = "test-session",
        name = null,
        createdAt = System.currentTimeMillis(),
        lastActiveAt = System.currentTimeMillis(),
        tokenCount = 0,
        status = SessionStatus.ACTIVE
    )
    
    private fun createTestMessage(id: Long) = MessageEntity(
        id = id,
        sessionId = "test-session",
        role = MessageRole.USER,
        content = "测试消息 $id",
        timestamp = System.currentTimeMillis(),
        tokenCount = 5
    )
}
```

**Step 2: Run test — confirm it fails**
Command: `./gradlew test --tests "com.openclaw.android.SessionCompressorTest"`
Expected: FAIL — "Unresolved reference: SessionCompressor"

**Step 3: Write minimal implementation**

```kotlin
// CompressionPrompts.kt
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

// SessionCompressor.kt
class SessionCompressor(
    private val llmClient: LocalLLMClient,
    private val summaryDao: SummaryDao
) {
    suspend fun compress(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        // 消息太少，不压缩
        if (messages.size <= preserveRecent) {
            return@runCatching null
        }
        
        // LLM 不可用，使用简单截断
        if (!llmClient.isAvailable()) {
            return@runCatching createSimpleSummary(session, messages)
        }
        
        // 分离消息
        val toCompress = messages.dropLast(preserveRecent)
        val toPreserve = messages.takeLast(preserveRecent)
        
        // 调用 LLM 压缩
        val summary = withTimeoutOrNull(30_000) {
            llmClient.summarize(
                system = CompressionPrompts.SUMMARIZE_SYSTEM,
                user = CompressionPrompts.buildPrompt(toCompress)
            )
        } ?: createSimpleSummaryText(toCompress)
        
        val entity = SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
        
        summaryDao.insert(entity)
        entity
    }
    
    private fun createSimpleSummary(session: SessionEntity, messages: List<MessageEntity>): SummaryEntity {
        val summary = createSimpleSummaryText(messages)
        return SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = messages.first().id,
            messageRangeEnd = messages.last().id,
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

**Step 4: Run test — confirm it passes**
Command: `./gradlew test --tests "com.openclaw.android.SessionCompressorTest"`
Expected: PASS

**Step 5: Commit**
`git add . && git commit -m "feat(session): add SessionCompressor with LLM integration"`

---

## Task 4: HybridSessionManager

**Files:**
- Create: `app/src/main/java/com/openclaw/android/domain/session/HybridSessionManager.kt`
- Create: `app/src/main/java/com/openclaw/android/domain/model/SessionConfig.kt`
- Test: `app/src/test/java/com/openclaw/android/HybridSessionManagerTest.kt`

**Step 1: Write the failing test**

```kotlin
// HybridSessionManagerTest.kt
@RunWith(AndroidJUnit4::class)
class HybridSessionManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: HybridSessionManager
    private lateinit var mockLlmClient: LocalLLMClient
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        mockLlmClient = mockk()
        
        manager = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
            llmClient = mockLlmClient,
            tokenCounter = TokenCounter()
        )
    }
    
    @After
    fun teardown() {
        db.close()
    }
    
    @Test
    fun initialize_createsDefaultSession() = runTest {
        coEvery { mockLlmClient.isAvailable() } returns false
        
        val session = manager.initialize()
        
        assertNotNull(session)
        assertNull(session.name) // 默认会话 name = null
    }
    
    @Test
    fun addMessage_increasesTokenCount() = runTest {
        coEvery { mockLlmClient.isAvailable() } returns false
        
        manager.initialize()
        manager.addMessage(MessageRole.USER, "测试消息")
        
        val session = db.sessionDao().getById(manager.getCurrentSessionId()!!)
        assertTrue(session!!.tokenCount > 0)
    }
    
    @Test
    fun getConversationContext_returnsMessages() = runTest {
        coEvery { mockLlmClient.isAvailable() } returns false
        
        manager.initialize()
        manager.addMessage(MessageRole.USER, "用户消息")
        manager.addMessage(MessageRole.ASSISTANT, "助手回复")
        
        val context = manager.getConversationContext()
        
        assertEquals(2, context.size)
    }
    
    @Test
    fun compressIfNeeded_whenOverThreshold_triggersCompression() = runTest {
        coEvery { mockLlmClient.isAvailable() } returns true
        coEvery { mockLlmClient.summarize(any(), any()) } returns "摘要内容"
        
        manager.initialize()
        
        // 添加消息直到超过阈值
        repeat(25) { i ->
            manager.addMessage(MessageRole.USER, "这是一条较长的测试消息，内容比较长 $i")
        }
        
        // 验证压缩发生
        val messages = db.messageDao().getBySession(manager.getCurrentSessionId()!!)
        assertTrue(messages.size < 25) // 部分消息被压缩
        
        val summary = db.summaryDao().getBySession(manager.getCurrentSessionId()!!)
        assertNotNull(summary) // 摘要已创建
    }
    
    @Test
    fun createNamedSession_createsNewSession() = runTest {
        coEvery { mockLlmClient.isAvailable() } returns false
        
        manager.initialize()
        val namedSession = manager.createNamedSession("工作会话")
        
        assertEquals("工作会话", namedSession.name)
        assertNotEquals(manager.getCurrentSessionId(), namedSession.sessionId)
    }
}
```

**Step 2: Run test — confirm it fails**
Command: `./gradlew test --tests "com.openclaw.android.HybridSessionManagerTest"`
Expected: FAIL — "Unresolved reference: HybridSessionManager"

**Step 3: Write minimal implementation**

```kotlin
// SessionConfig.kt
data class SessionConfig(
    val maxTokens: Int = 1800,
    val preserveRecentMessages: Int = 10,
    val autoCompressDefault: Boolean = true
)

// HybridSessionManager.kt
class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val llmClient: LocalLLMClient,
    private val tokenCounter: TokenCounter
) {
    private var currentSession: SessionEntity? = null
    private val config = SessionConfig()
    private val compressor by lazy { SessionCompressor(llmClient, summaryDao) }
    
    suspend fun initialize(): SessionEntity {
        // 尝试获取现有默认会话
        var session = sessionDao.getDefaultSession()
        
        if (session == null) {
            // 创建新会话
            session = SessionEntity(
                sessionId = UUID.randomUUID().toString(),
                name = null,
                createdAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis(),
                tokenCount = 0,
                status = SessionStatus.ACTIVE
            )
            sessionDao.insert(session)
        }
        
        currentSession = session
        return session
    }
    
    suspend fun addMessage(role: MessageRole, content: String): Result<MessageEntity> = runCatching {
        val session = currentSession ?: throw IllegalStateException("Session not initialized")
        
        val tokenCount = tokenCounter.estimate(content)
        val message = MessageEntity(
            sessionId = session.sessionId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            tokenCount = tokenCount
        )
        
        val id = messageDao.insert(message)
        messageDao.updateLastActive(session.sessionId, System.currentTimeMillis())
        
        // 更新 token 计数
        val newTokenCount = session.tokenCount + tokenCount
        sessionDao.updateTokenCount(session.sessionId, newTokenCount)
        currentSession = session.copy(tokenCount = newTokenCount)
        
        // 检查是否需要压缩
        if (config.autoCompressDefault && session.name == null) {
            compressIfNeeded()
        }
        
        message.copy(id = id)
    }
    
    suspend fun getConversationContext(): List<MessageEntity> {
        val session = currentSession ?: return emptyList()
        
        val messages = messageDao.getBySession(session.sessionId)
        val summary = summaryDao.getBySession(session.sessionId)
        
        return if (summary != null) {
            // 返回摘要（作为系统消息）+ 最近消息
            listOf(
                MessageEntity(
                    sessionId = session.sessionId,
                    role = MessageRole.SYSTEM,
                    content = "历史摘要: ${summary.content}",
                    timestamp = summary.compressedAt,
                    tokenCount = tokenCounter.estimate(summary.content)
                )
            ) + messages
        } else {
            messages
        }
    }
    
    suspend fun compressIfNeeded(force: Boolean = false) {
        val session = currentSession ?: return
        
        val shouldCompress = force || session.tokenCount > config.maxTokens
        if (!shouldCompress) return
        
        val messages = messageDao.getBySession(session.sessionId)
        
        val result = compressor.compress(session, messages, config.preserveRecentMessages)
        
        result.getOrNull()?.let { summary ->
            // 删除已压缩的消息
            val toDelete = messages
                .filter { it.id in summary.messageRangeStart..summary.messageRangeEnd }
                .map { it.id }
            
            if (toDelete.isNotEmpty()) {
                messageDao.deleteByIds(session.sessionId, toDelete)
            }
            
            // 更新 token 计数
            val remainingTokens = messages
                .filter { it.id !in toDelete }
                .sumOf { it.tokenCount } + tokenCounter.estimate(summary.content)
            
            sessionDao.updateTokenCount(session.sessionId, remainingTokens)
            currentSession = session.copy(tokenCount = remainingTokens)
        }
    }
    
    suspend fun createNamedSession(name: String): SessionEntity {
        val session = SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )
        sessionDao.insert(session)
        return session
    }
    
    fun getCurrentSessionId(): String? = currentSession?.sessionId
}
```

**Step 4: Run test — confirm it passes**
Command: `./gradlew test --tests "com.openclaw.android.HybridSessionManagerTest"`
Expected: PASS

**Step 5: Commit**
`git add . && git commit -m "feat(session): add HybridSessionManager with compression"`

---

## Task 5: 集成测试

**Files:**
- Test: `app/src/test/java/com/openclaw/android/SessionIntegrationTest.kt`

**Step 1: Write the failing test**

```kotlin
// SessionIntegrationTest.kt
@RunWith(AndroidJUnit4::class)
class SessionIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: HybridSessionManager
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        
        // 使用真实的 LLM 客户端（如果可用）
        val llmClient = LocalLLMClient.getInstance(context)
        
        manager = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
            llmClient = llmClient,
            tokenCounter = TokenCounter()
        )
    }
    
    @After
    fun teardown() {
        db.close()
    }
    
    @Test
    fun fullConversation_withCompression() = runTest {
        // 初始化
        manager.initialize()
        
        // 模拟完整对话
        val conversation = listOf(
            "我想开发一个 Android 应用" to MessageRole.USER,
            "好的，你想做什么类型的应用？" to MessageRole.ASSISTANT,
            "一个会话管理工具" to MessageRole.USER,
            "明白，需要什么功能？" to MessageRole.ASSISTANT,
            "长期持久，分层压缩" to MessageRole.USER
        )
        
        conversation.forEach { (content, role) ->
            manager.addMessage(role, content)
        }
        
        // 验证消息存储
        val context = manager.getConversationContext()
        assertTrue(context.size >= conversation.size)
    }
    
    @Test
    fun sessionRecovery_afterRestart() = runTest {
        // 创建会话并添加消息
        manager.initialize()
        manager.addMessage(MessageRole.USER, "记住这个信息")
        val sessionId = manager.getCurrentSessionId()!!
        
        // 模拟重启（创建新的 manager）
        val newManager = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
            llmClient = LocalLLMClient.getInstance(ApplicationProvider.getApplicationContext()),
            tokenCounter = TokenCounter()
        )
        
        // 恢复会话
        newManager.initialize()
        
        // 验证恢复
        assertEquals(sessionId, newManager.getCurrentSessionId())
        val context = newManager.getConversationContext()
        assertTrue(context.any { it.content.contains("记住这个信息") })
    }
}
```

**Step 2: Run test — confirm behavior**
Command: `./gradlew test --tests "com.openclaw.android.SessionIntegrationTest"`
Expected: PASS（如果 LLM 可能失败，降级为简单截断）

**Step 3: Commit**
`git add . && git commit -m "test(session): add integration tests"`

---

## 执行选项

计划已保存到 `docs/plans/2026-04-06-session-mechanism.md`

**两种执行方式：**

1. **Subagent-Driven** — 我为每个任务派发子代理，任务间审查
2. **Manual** — 你自己运行任务

**选择哪种方式？**