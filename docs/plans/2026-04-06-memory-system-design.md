# OpenClaw-Android 记忆系统设计

**日期**: 2026-04-06
**状态**: 已批准
**目标**: 实现跨会话持久化记忆 + 语义检索 + 自动/手动提取

---

## 1. 需求概述

| 需求项 | 决策 |
|--------|------|
| 记忆目标 | 参考 OpenClaw WSL2 方案（MEMORY.md + LanceDB） |
| 存储方案 | SQLite + sqlite-vec 向量搜索 |
| Embedding 模型 | TensorFlow Lite + MiniLM-L6-v2 (~45MB) |
| 提取策略 | 混合（自动提取 + 手动标记） |

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────┐
│                    MemoryManager                         │
├─────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ MemoryExtractor│  │ TfLiteEmbedding │  │ MemoryStore  │   │
│  │ (自动/手动提取) │  │ Service (MiniLM)│  │ (Room+sqlite-vec)│   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────┘
```

---

## 3. 数据模型

### 3.1 MemoryEntity

```kotlin
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,              // 记忆内容
    val memoryType: MemoryType,       // PREFERENCE, FACT, DECISION, TASK, PROJECT
    val priority: Int,                // 1-5, 5 最高
    val source: String?,              // 来源："auto" | "manual" | conversation_id
    val tags: List<String>,           // 标签
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0          // 访问次数（用于遗忘策略）
)

enum class MemoryType {
    PREFERENCE,   // 用户偏好：我喜欢蓝色
    FACT,         // 事实信息：我叫张三
    DECISION,     // 重要决策：选择方案 A
    TASK,         // 待办任务：明天开会
    PROJECT       // 项目信息：项目路径是 /path
}
```

### 3.2 MemoryVectorEntity

```kotlin
@Entity(tableName = "memory_vectors")
data class MemoryVectorEntity(
    @PrimaryKey val memoryId: Long,
    val vector: FloatArray,           // 384 维向量 (MiniLM-L6-v2)
    val updatedAt: Long
)
```

### 3.3 DAO 接口

```kotlin
@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long
    
    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?
    
    @Query("SELECT * FROM memories WHERE memoryType = :type ORDER BY priority DESC, createdAt DESC LIMIT :limit")
    suspend fun getByType(type: MemoryType, limit: Int): List<MemoryEntity>
    
    @Query("SELECT * FROM memories WHERE priority >= 4 ORDER BY priority DESC, lastAccessedAt DESC LIMIT :limit")
    suspend fun getHighPriority(limit: Int): List<MemoryEntity>
    
    @Query("UPDATE memories SET lastAccessedAt = :timestamp, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun updateAccess(id: Long, timestamp: Long)
    
    @Query("SELECT * FROM memories WHERE lastAccessedAt < :threshold AND accessCount < :minAccessCount")
    suspend fun findStale(threshold: Long, minAccessCount: Int): List<MemoryEntity>
    
    @Delete
    suspend fun delete(memory: MemoryEntity)
}

@Dao
interface MemoryVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: MemoryVectorEntity)
    
    @Query("SELECT * FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun getByMemoryId(memoryId: Long): MemoryVectorEntity?
    
    // sqlite-vec 相似度搜索
    @Query("""
        SELECT memoryId, vec_distance_cosine(vector, :queryVector) as similarity
        FROM memory_vectors
        ORDER BY similarity ASC
        LIMIT :limit
    """)
    suspend fun searchSimilar(queryVector: FloatArray, limit: Int): List<VectorSearchResult>
    
    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun deleteByMemoryId(memoryId: Long)
}

data class VectorSearchResult(
    val memoryId: Long,
    val similarity: Float
)
```

---

## 4. Embedding 服务

### 4.1 接口定义

```kotlin
interface EmbeddingService {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    fun getDimension(): Int = 384
}
```

### 4.2 TensorFlow Lite 实现

```kotlin
class TfLiteEmbeddingService(
    private val context: Context
) : EmbeddingService {
    
    private var interpreter: Interpreter? = null
    private val tokenizer: BertTokenizer
    
    companion object {
        private const val MODEL_FILE = "minilm-l6-v2.tflite"
        private const val VOCAB_FILE = "vocab.txt"
        private const val MAX_SEQ_LENGTH = 128
        private const val EMBEDDING_DIM = 384
    }
    
    init {
        tokenizer = BertTokenizer(context.assets.open(VOCAB_FILE))
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelBuffer = context.assets.open(MODEL_FILE).use { it.readBytes() }
            interpreter = Interpreter(modelBuffer)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            false
        }
    }
    
    override suspend fun embed(text: String): FloatArray {
        val tokens = tokenizer.tokenize(text, MAX_SEQ_LENGTH)
        val inputIds = tokens.first
        val attentionMask = tokens.second
        
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interpreter?.run(
            mapOf(
                "input_ids" to arrayOf(inputIds),
                "attention_mask" to arrayOf(attentionMask)
            ),
            output
        )
        return output[0]
    }
    
    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }
}
```

### 4.3 模型信息

| 属性 | 值 |
|------|---|
| 模型 | all-MiniLM-L6-v2 |
| 大小 | ~45MB |
| 维度 | 384 |
| 推理速度 | ~5ms/句 (Android) |
| 来源 | https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2 |

---

## 5. 记忆提取

### 5.1 提取提示词

```kotlin
object MemoryExtractionPrompts {
    val SYSTEM_PROMPT = """
分析以下对话，提取需要记住的信息。

输出 JSON 格式：
{
  "memories": [
    {
      "type": "PREFERENCE|FACT|DECISION|TASK|PROJECT",
      "content": "具体内容",
      "priority": 1-5,
      "tags": ["标签1", "标签2"]
    }
  ]
}

提取规则：
1. PREFERENCE: 用户偏好（"我喜欢..."、"不要..."）
2. FACT: 事实信息（"我叫..."、"我的邮箱是..."）
3. DECISION: 重要决策（"选择方案A"、"决定用..."）
4. TASK: 待办事项（"明天要..."、"记得..."）
5. PROJECT: 项目信息（"项目路径是..."、"使用...框架"）

只提取新信息，不要重复已有记忆。
""".trimIndent()
}
```

### 5.2 MemoryExtractor

```kotlin
class MemoryExtractor(private val llmClient: LocalLLMClient) {
    
    // 自动提取（每次对话后调用）
    suspend fun extractFromConversation(
        messages: List<MessageEntity>
    ): Result<List<MemoryEntity>>
    
    // 手动标记（用户说"记住这个"）
    suspend fun extractFromUserInput(
        content: String,
        type: MemoryType? = null
    ): Result<MemoryEntity>
}
```

### 5.3 触发机制

| 场景 | 触发方式 | 处理方法 |
|------|---------|---------|
| 自动提取 | 对话结束 30s 后 | `extractFromConversation()` |
| 手动标记 | 用户说"记住这个..." | `extractFromUserInput()` |
| 关键词触发 | 检测"我喜欢"、"重要"、"决定" | 自动创建高优先级记忆 |

---

## 6. MemoryManager（核心管理器）

```kotlin
class MemoryManager(
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingService: EmbeddingService,
    private val extractor: MemoryExtractor
) {
    // 存储（自动生成向量）
    suspend fun store(memory: MemoryEntity): Result<MemoryEntity>
    
    // 语义搜索
    suspend fun search(query: String, limit: Int = 10, threshold: Float = 0.7f): List<MemorySearchResult>
    
    // 按类型获取
    suspend fun getByType(type: MemoryType, limit: Int = 20): List<MemoryEntity>
    
    // 获取高优先级记忆（会话恢复注入）
    suspend fun getImportantMemories(limit: Int = 10): List<MemoryEntity>
    
    // 更新访问记录
    suspend fun touch(memoryId: Long)
    
    // 自动提取并存储
    suspend fun extractAndStore(messages: List<MessageEntity>): Result<Int>
    
    // 手动添加
    suspend fun addManual(content: String, type: MemoryType? = null): Result<MemoryEntity>
    
    // 清理过期记忆
    suspend fun cleanup(days: Int = 30, minAccessCount: Int = 2): Int
    
    // 导出
    suspend fun export(): List<MemoryExport>
}
```

---

## 7. 测试策略

| 测试场景 | 类型 | 优先级 |
|---------|------|--------|
| Embedding 生成正确 | 单元 | P0 |
| 向量搜索返回相关结果 | 集成 | P0 |
| 记忆提取解析正确 | 单元 | P0 |
| 自动触发存储 | 集成 | P1 |
| 手动标记存储 | 单元 | P1 |
| 遗忘策略清理 | 单元 | P2 |

---

## 8. 文件结构

```
app/src/main/java/ai/openclaw/android/
├── data/
│   ├── local/
│   │   ├── MemoryDao.kt
│   │   ├── MemoryVectorDao.kt
│   │   └── Converters.kt
│   └── model/
│       ├── MemoryEntity.kt
│       ├── MemoryVectorEntity.kt
│       └── MemoryType.kt
├── domain/
│   └── memory/
│       ├── MemoryManager.kt
│       ├── MemoryExtractor.kt
│       └── EmbeddingService.kt
├── ml/
│   ├── TfLiteEmbeddingService.kt
│   └── BertTokenizer.kt
└── assets/
    ├── minilm-l6-v2.tflite
    └── vocab.txt

app/src/test/java/ai/openclaw/android/
├── MemoryExtractorTest.kt
├── TfLiteEmbeddingServiceTest.kt
└── MemoryManagerTest.kt
```

---

## 9. TDD 开发顺序

1. **模型文件准备** - 下载 MiniLM-L6-v2 TFLite 模型
2. **TfLiteEmbeddingService** - 实现 embedding 生成
3. **MemoryDao / MemoryVectorDao** - 实现数据访问
4. **MemoryExtractor** - 实现记忆提取
5. **MemoryManager** - 整合所有组件

---

## 10. 与会话系统集成

```kotlin
// 在 HybridSessionManager 中集成
class HybridSessionManager(
    // ... existing params
    private val memoryManager: MemoryManager
) {
    suspend fun addMessage(role: MessageRole, content: String): Result<MessageEntity> {
        // ... existing logic
        
        // 检测手动标记
        if (content.contains("记住这个") || content.contains("记住：")) {
            memoryManager.addManual(content)
        }
        
        // 返回后触发自动提取（延迟 30s）
        if (role == MessageRole.USER) {
            triggerDelayedExtraction()
        }
        
        return result
    }
    
    suspend fun getConversationContext(): List<MessageEntity> {
        val messages = // ... existing logic
        
        // 注入重要记忆
        val memories = memoryManager.getImportantMemories(5)
        val memoryContext = memories.map { 
            MessageEntity(
                role = MessageRole.SYSTEM,
                content = "[记忆] ${it.content}",
                ...
            )
        }
        
        return memoryContext + messages
    }
}
```

---

**批准人**: 用户123767
**批准时间**: 2026-04-06 11:39