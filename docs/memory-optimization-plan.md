# 手机端记忆系统优化方案

> 基于分层模块化架构的移动设备优化设计
> 创建时间：2026-04-10
> 状态：**✅ 全部完成**（2026-04-12）

---

## 📋 执行摘要

本方案针对 OpenClaw Android 项目当前记忆系统的不足，提出一套完整的优化方案。核心目标是平衡**资源消耗、响应延迟、隐私安全与上下文连贯性**。

### 当前状态 vs 目标状态

| 模块 | 状态 | 实现文件 |
|------|------|--------|
| 感知缓冲层 | ✅ SensoryBuffer（环形缓冲区 50 条 / 5 分钟） | `memory/SensoryBuffer.kt` |
| 混合检索引擎 | ✅ BM25 + 向量双路召回 + 时间衰减 | `domain/memory/HybridSearchEngine.kt` |
| BM25 索引 | ✅ 关键词索引 + 时间分桶 + LRU 缓存 | `data/local/BM25Index.kt` |
| 嵌入模型 | ✅ ONNX Runtime 优先 + TfLite/Simple 降级 | `ml/EmbeddingModel.kt` |
| 遗忘曲线 | ✅ 自动清理 + 存储阈值 500 + WorkManager | `domain/memory/MemoryMaintenanceWorker.kt` |
| 冲突检测 | ✅ 三区间策略（重复/合并/独立） | `domain/memory/MemoryManager.kt` |
| 隐私加密 | ✅ SQLCipher + Android Keystore | `security/SecurityKeyManager.kt` |
| 差分同步 | ✅ 双向 push/pull + version 冲突解决 | `domain/memory/DiffSyncManager.kt` |
| 安全审计 | ✅ SHA-256 哈希链防篡改 | `security/AuditLogger.kt` |
| 冷启动 | ✅ 72h SENSORY_ONLY → FULL 渐进式 | `domain/memory/ColdStartManager.kt` |
| 用户画像 | ✅ 后台分析 + FACT 记忆 | `domain/memory/UserProfileBuilderWorker.kt` |

---

## 🏗️ 整体架构

### 三层记忆模型

```
┌─────────────────────────────────────────────────────────┐
│                    记忆系统 (Memory OS)                  │
├─────────────────────────────────────────────────────────┤
│ 1. 感知记忆层   │ 即时对话上下文、当前屏幕内容、语音片段  │
│    (Sensory)    │ 生命周期：单次会话，驻留内存            │
│                 │ 容量：最近 50 条交互或 5 分钟             │
├─────────────────────────────────────────────────────────┤
│ 2. 摘要记忆层   │ 用户画像、习惯、关键事件压缩向量         │
│    (Semantic)   │ 生命周期：持久化，本地加密存储           │
│                 │ 存储：Room Database + sqlite-vec        │
├─────────────────────────────────────────────────────────┤
│ 3. 过程记忆层   │ 任务状态机、多轮操作序列                 │
│    (Episodic)   │ 生命周期：任务期间，云端可选同步         │
│                 │ 同步：Hub API 差分传输                  │
└─────────────────────────────────────────────────────────┘
```

---

## 🔧 核心模块设计

### 1. 感知记忆缓冲池 (Sensory Buffer)

**功能**：实时捕捉并暂存最近 N 轮交互，避免频繁 I/O。

#### 数据结构

```kotlin
// app/src/main/java/ai/openclaw/android/memory/SensoryBuffer.kt

class SensoryBuffer(
    private val maxLength: Int = 50,
    private val maxAgeMinutes: Long = 5
) {
    private val buffer = ArrayDeque<InteractionEntry>(maxLength)
    private val lock = ReentrantLock()
    
    data class InteractionEntry(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val text: String?,
        val screenFeatures: ScreenFeatures?,  // MobileCLIP 提取
        val ocrSummary: String?,              // OCR 文字摘要
        val intent: String?                   // 意图分类
    )
    
    data class ScreenFeatures(
        val sceneVector: FloatArray,          // 384 维场景特征
        val dominantColors: IntArray,         // 主色调
        val uiElementCount: Int               // UI 元素数量
    )
    
    /**
     * 添加交互记录
     * - 文本/ASR 结果：直接存入
     * - 屏幕图像：提取特征后丢弃原图
     * - 音频：仅保留转写文本
     */
    fun add(entry: InteractionEntry) {
        lock.withLock {
            // 移除过期条目
            val cutoff = System.currentTimeMillis() - maxAgeMinutes * 60_000
            while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) {
                buffer.removeFirst()
            }
            
            // 超出容量时移除最旧条目
            while (buffer.size >= maxLength) {
                buffer.removeFirst()
            }
            
            buffer.addLast(entry)
        }
    }
    
    /**
     * 获取最近 N 条记录
     */
    fun getRecent(n: Int = 10): List<InteractionEntry> {
        lock.withLock {
            return buffer.takeLast(n)
        }
    }
    
    /**
     * 清空缓冲区（会话结束时调用）
     */
    fun clear() {
        lock.withLock {
            buffer.clear()
        }
    }
}
```

#### 多模态处理流程

```kotlin
// app/src/main/java/ai/openclaw/android/memory/MultiModalProcessor.kt

class MultiModalProcessor @Inject constructor(
    private val mobileCLIP: MobileCLIPEngine,      // 轻量视觉模型
    private val ocrEngine: OCREngine,              // 文字识别
    private val intentClassifier: IntentClassifier // 意图分类
) {
    /**
     * 处理屏幕截图
     * - 提取场景特征向量 (384 维)
     * - 执行 OCR 获取文字摘要
     * - 丢弃原始图片以节省存储
     */
    suspend fun processScreenshot(bitmap: Bitmap): ScreenFeatures {
        return withContext(Dispatchers.Default) {
            val sceneVector = mobileCLIP.extractFeatures(bitmap)
            val ocrText = ocrEngine.recognize(bitmap)
            
            ScreenFeatures(
                sceneVector = sceneVector,
                dominantColors = extractDominantColors(bitmap),
                uiElementCount = detectUIElements(bitmap)
            )
        }
    }
    
    /**
     * 处理音频
     * - 仅保留 ASR 转写文本
     * - 不存储原始音频流
     */
    suspend fun processAudio(audioData: ByteArray): String {
        return asrEngine.transcribe(audioData)
        // 音频数据立即丢弃，不保留
    }
}
```

#### 依赖集成

```kotlin
// app/build.gradle.kts

dependencies {
    // MobileCLIP - 轻量视觉模型 (~15MB)
    implementation("com.github.clip:mobile-clip-android:1.0.0")
    
    // MediaPipe Text Classifier - 意图分类 (<5MB)
    implementation("com.google.mediapipe:tasks-vision:0.10.0")
    
    // Tesseract OCR - 文字识别
    implementation("com.rmfland:tesseract-android:5.0.0")
}
```

---

### 2. 混合检索增强模块 (Hybrid RAG Core)

**功能**：从长期记忆中召回与当前对话相关的历史信息。

#### 双路召回架构

```kotlin
// app/src/main/java/ai/openclaw/android/memory/HybridSearchEngine.kt

class HybridSearchEngine @Inject constructor(
    private val memoryDao: MemoryDao,
    private val memoryVectorDao: MemoryVectorDao,
    private val embeddingModel: EmbeddingModel,      // BGE-small 量化版
    private val bm25Index: BM25Index                 // 关键词索引
) {
    data class SearchQuery(
        val text: String,
        val timeRange: TimeRange? = null,
        val tags: List<String> = emptyList(),
        val maxResults: Int = 20
    )
    
    data class SearchResult(
        val memory: MemoryEntity,
        val keywordScore: Double,
        val vectorScore: Double,
        val combinedScore: Double
    )
    
    /**
     * 双路召回 + 重排序
     */
    suspend fun search(query: SearchQuery): List<SearchResult> {
        return withContext(Dispatchers.Default) {
            // 1. 关键词路：BM25 快速匹配专有名词、日程
            val keywordResults = bm25Index.search(
                query.text,
                timeRange = query.timeRange,
                topK = query.maxResults * 2
            )
            
            // 2. 语义向量路：计算查询向量相似度
            val queryVector = embeddingModel.encode(query.text)
            val vectorResults = memoryVectorDao.findSimilar(
                vector = queryVector,
                threshold = 0.6f,
                topK = query.maxResults * 2
            )
            
            // 3. 合并结果
            val merged = mergeResults(keywordResults, vectorResults)
            
            // 4. 重排序（仅在 Top-K 后使用交叉编码）
            val reranked = rerank(merged, query.text)
            
            // 5. 应用过滤条件
            reranked
                .filter { query.tags.isEmpty() || it.memory.tags.any { tag -> tag in query.tags } }
                .take(query.maxResults)
        }
    }
    
    private fun mergeResults(
        keywordResults: List<MemoryEntity>,
        vectorResults: List<MemoryEntity>
    ): List<MemoryEntity> {
        // 去重 + 保留各自高分结果
        return (keywordResults + vectorResults)
            .distinctBy { it.id }
            .take(50)
    }
    
    private suspend fun rerank(
        candidates: List<MemoryEntity>,
        query: String
    ): List<SearchResult> {
        // 使用轻量交叉编码模型精排 Top-20
        // 计算 combinedScore = 0.4 * keywordScore + 0.6 * vectorScore
        return candidates.mapIndexed { index, memory ->
            SearchResult(
                memory = memory,
                keywordScore = calculateKeywordScore(memory, query),
                vectorScore = calculateVectorScore(memory, query),
                combinedScore = 0.0 // 待计算
            )
        }.sortedByDescending { it.combinedScore }
    }
}
```

#### 向量数据库集成

```kotlin
// app/src/main/java/ai/openclaw/android/data/local/MemoryVectorDao.kt

@Dao
abstract class MemoryVectorDao {
    /**
     * 使用 sqlite-vec 进行近似最近邻搜索
     * 支持 int8 量化向量，索引速度极快
     */
    @Query("""
        SELECT m.*, mv.vector, 
               vec_distance_cosine(mv.vector, :vector) as similarity
        FROM memories m
        JOIN memory_vectors mv ON m.id = mv.memoryId
        WHERE vec_distance_cosine(mv.vector, :vector) >= :threshold
        ORDER BY similarity DESC
        LIMIT :topK
    """)
    abstract suspend fun findSimilar(
        vector: FloatArray,
        threshold: Float = 0.6f,
        topK: Int = 20
    ): List<MemoryEntity>
    
    /**
     * 按时间分区索引（优化大范围检索）
     */
    @Query("""
        SELECT m.*, mv.vector
        FROM memories m
        JOIN memory_vectors mv ON m.id = mv.memoryId
        WHERE m.createdAt BETWEEN :startTime AND :endTime
        ORDER BY m.lastAccessedAt DESC
    """)
    abstract suspend fun findByTimeRange(
        startTime: Long,
        endTime: Long
    ): List<MemoryEntity>
}
```

#### Embedding 模型配置

```kotlin
// app/src/main/java/ai/openclaw/android/memory/EmbeddingModel.kt

class EmbeddingModel @Inject constructor(
    private val context: Context
) {
    private val session: OrtSession by lazy {
        // BAAI/bge-small-zh-v1.5 ONNX 量化版 (~30MB)
        val modelPath = context.filesDir.resolve("models/bge-small-zh-quantized.onnx").path
        OrtEnvironment().createSession(modelPath, OrtSession.SessionOptions())
    }
    
    /**
     * 编码文本为 384 维向量
     */
    suspend fun encode(text: String): FloatArray {
        return withContext(Dispatchers.Default) {
            val tokens = tokenize(text)
            val output = session.run(mapOf("input_ids" to tokens))
            output[0].floatBuffer.array().sliceArray(0..383)
        }
    }
}
```

---

### 3. 记忆沉淀与遗忘机制

**功能**：将缓冲区的对话提炼为长期知识，并清理冗余信息。

#### 后台调度器

```kotlin
// app/src/main/java/ai/openclaw/android/memory/MemoryConsolidationWorker.kt

class MemoryConsolidationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val consolidation = MemoryConsolidation(
                memoryDao = getMemoryDao(),
                sensoryBuffer = getSensoryBuffer()
            )
            
            // 1. 冲突检测与合并
            consolidation.detectAndResolveConflicts()
            
            // 2. 遗忘曲线清理
            consolidation.applyForgettingCurve()
            
            // 3. 存储阈值检查
            consolidation.checkStorageThreshold()
            
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "记忆沉淀失败")
            Result.retry()
        }
    }
    
    companion object {
        /**
         * 配置后台调度
         * 触发条件：充电 + WiFi + 低负载时段
         */
        fun scheduleWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
                repeatInterval = 4,
                TimeUnit.HOURS
            ).setConstraints(constraints)
             .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "memory_consolidation",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
```

#### 冲突检测逻辑

```kotlin
// app/src/main/java/ai/openclaw/android/memory/MemoryConsolidation.kt

class MemoryConsolidation(
    private val memoryDao: MemoryDao,
    private val sensoryBuffer: SensoryBuffer
) {
    data class Conflict(
        val oldMemory: MemoryEntity,
        val newMemory: MemoryEntity,
        val conflictType: ConflictType,
        val confidence: Float
    )
    
    enum class ConflictType {
        CONTRADICTION,      // 直接矛盾（如生日不同）
        DUPLICATE,          // 重复内容
        OUTDATED,           // 信息过时
        AMBIGUOUS           // 模糊冲突
    }
    
    /**
     * 冲突检测与合并
     * 策略：基于时间戳最新的信源优先，保留冲突日志供用户确认
     */
    suspend fun detectAndResolveConflicts() {
        val newMemories = sensoryBuffer.getRecent()
            .filter { it.intent != null }
            .map { it.toMemoryEntity() }
        
        for (newMemory in newMemories) {
            val potentialConflicts = memoryDao.findByEntityAndRelation(
                entity = extractEntity(newMemory.content),
                relation = extractRelation(newMemory.content)
            )
            
            for (oldMemory in potentialConflicts) {
                if (isContradiction(oldMemory, newMemory)) {
                    val conflict = Conflict(
                        oldMemory = oldMemory,
                        newMemory = newMemory,
                        conflictType = ConflictType.CONTRADICTION,
                        confidence = calculateConfidence(oldMemory, newMemory)
                    )
                    
                    // 时间戳最新优先
                    if (newMemory.createdAt > oldMemory.lastAccessedAt) {
                        memoryDao.markAsConflicted(oldMemory.id, conflict)
                        memoryDao.insert(newMemory)
                    } else {
                        // 保留冲突日志供用户确认
                        memoryDao.logConflict(conflict)
                    }
                }
            }
        }
    }
    
    /**
     * 遗忘曲线模拟
     * - 记录每条记忆的最后访问时间和访问频次
     * - 存储超阈值时，优先删除低频、低情感极性的记忆
     */
    suspend fun applyForgettingCurve() {
        val totalSize = memoryDao.getStorageSize()
        val threshold = 200 * 1024 * 1024L // 200MB
        
        if (totalSize < threshold) {
            return // 未达阈值，无需清理
        }
        
        // 计算遗忘分数 = f(访问频次，最后访问时间，情感极性)
        val candidates = memoryDao.getAll()
            .map { memory ->
                val forgettingScore = calculateForgettingScore(memory)
                memory to forgettingScore
            }
            .sortedByDescending { it.second }
        
        // 删除遗忘分数最高的记忆（最可能被遗忘的）
        val toDelete = candidates.takeWhile { (memory, score) ->
            memoryDao.getStorageSize() > threshold * 0.8 // 清理到 80% 阈值
        }.map { it.first }
        
        for (memory in toDelete) {
            // 保留向量索引，仅删除文本细节
            memoryDao.archiveMemory(memory.id)
        }
    }
    
    private fun calculateForgettingScore(memory: MemoryEntity): Double {
        val recencyWeight = 0.4
        val frequencyWeight = 0.4
        val emotionWeight = 0.2
        
        val recencyScore = exp(-0.001 * (System.currentTimeMillis() - memory.lastAccessedAt))
        val frequencyScore = log(memory.accessCount + 1) / log(100)
        val emotionScore = abs(memory.emotionPolarity ?: 0.0)
        
        return recencyWeight * (1 - recencyScore) +
               frequencyWeight * (1 - frequencyScore) +
               emotionWeight * (1 - emotionScore)
    }
}
```

---

### 4. 隐私沙盒与同步网关

**功能**：确保记忆数据的安全存储和隐私保护同步。

#### 存储加密

```kotlin
// app/src/main/java/ai/openclaw/android/security/MemoryEncryption.kt

class MemoryEncryption @Inject constructor(
    private val context: Context,
    private val keyStore: KeyStore
) {
    /**
     * 使用 SQLCipher 进行数据库加密 (AES-256)
     */
    fun createEncryptedDatabase(): SupportSQLiteDatabase {
        val masterKey = getOrCreateMasterKey()
        val passphrase = SQLCipherKey(masterKey)
        
        return SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("memories.db"),
            passphrase
        )
    }
    
    /**
     * 主密钥存储于 Android KeyStore
     * - 硬件-backed（如有TEE）
     * - 需要用户认证（指纹/面部）
     */
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        val alias = "memory_master_key"
        val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        
        return entry?.secretKey ?: createNewMasterKey(alias)
    }
    
    /**
     * 差分同步
     * - 仅上传匿名化向量索引
     * - 明文记忆端侧加密后同步至云端
     * - 云端无解密能力
     */
    suspend fun syncToCloud(memories: List<MemoryEntity>) {
        val encryptedMemories = memories.map { memory ->
            EncryptedMemory(
                id = memory.id,
                encryptedContent = encrypt(memory.content),
                vector = anonymizeVector(memory.vector),
                metadata = memory.metadata.copy(
                    userId = hashUserId(memory.metadata.userId) // 匿名化
                )
            )
        }
        
        // 同步到 Google Drive / iCloud
        cloudSyncService.upload(encryptedMemories)
    }
}
```

#### 存储隔离

```kotlin
// app/src/main/java/ai/openclaw/android/memory/MemoryStorageConfig.kt

object MemoryStorageConfig {
    /**
     * 强制存储于 App 私有目录
     * /data/data/ai.openclaw.android/databases/memories.db
     */
    fun getDatabasePath(context: Context): File {
        return context.getDatabasePath("memories.db").apply {
            parentFile?.mkdirs()
            // 设置文件权限为仅应用可访问
            setReadable(false, false)
            setWritable(false, false)
            setExecutable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }
    }
    
    /**
     * 敏感记忆存储于系统 KeyStore 加密区
     */
    fun getSecureStoragePath(context: Context): File {
        return context.filesDir.resolve("secure_memories/").apply {
            mkdirs()
            // 使用 EncryptedFile (AndroidX Security)
        }
    }
}
```

---

## 📦 技术选型清单

| 组件 | 推荐方案 | 内存/存储占用 | 集成难度 |
|------|---------|--------------|---------|
| **Embedding 模型** | BAAI/bge-small-zh-v1.5 (ONNX 量化) | ~30MB | ⭐⭐ |
| **视觉模型** | MobileCLIP | ~15MB | ⭐⭐⭐ |
| **向量存储** | sqlite-vec + 虚拟表 | <10MB/千条 | ⭐⭐ |
| **关键词检索** | SQLite FTS5 + BM25 | <5MB | ⭐ |
| **分类/实体提取** | MediaPipe Text Classifier | <5MB | ⭐⭐ |
| **加密方案** | SQLCipher (AES-256) | CPU 开销可忽略 | ⭐⭐ |
| **后台调度** | WorkManager | 系统自带 | ⭐ |

---

## 🚀 实施路线图

### 阶段一：基础架构 (P0) ✅ 已完成 (2026-04-11)

- [x] 实现 SensoryBuffer 环形缓冲区（`memory/SensoryBuffer.kt`，82行）
- [x] 实现 BM25Index 关键词索引（`data/local/BM25Index.kt`，202行）
- [x] 添加 ONNX Embedding 模型（`ml/EmbeddingModel.kt`，134行，ONNX Runtime 1.21.0）
- [x] 实现 HybridSearchEngine 混合检索（`domain/memory/HybridSearchEngine.kt`，204行）
- [x] Koin DI 注册（BM25Index、EmbeddingModel、HybridSearchEngine）
- [x] MemoryManager 添加 hybridSearch() 方法
- [x] EmbeddingServiceFactory ONNX 优先降级链

### 阶段二：检索引擎优化 (P0) ✅ 已完成 (2026-04-11)

- [x] 完成双路召回逻辑（HybridSearchEngine BM25 + 向量）
- [x] 实现重排序算法（时间衰减因子：0.35×kw + 0.55×vec + 0.10×recency）
- [x] 时间分区索引（BM25Index timeBuckets 按天分桶）
- [x] 性能优化（LruCache tokenize 缓存 64 条 + VectorCacheEntry TTL 30s）
- [x] asyncSearch() 并行搜索方法

### 阶段三：沉淀机制 (P1) ✅ 已完成 (2026-04-11)

- [x] 冲突检测与合并（CONFLICT_THRESHOLD，三区间策略：>0.95 跳过 / 0.80~0.95 保留更完整 / <0.80 正常存储）
- [x] 遗忘曲线调度（exponential backoff）
- [x] 存储阈值监控（MAX_MEMORY_COUNT=500，超限自动 cleanup，MIN_RETAIN_COUNT=20）
- [x] WorkManager 后台任务（MemoryMaintenanceWorker，24h 周期，work-runtime-ktx:2.10.1）
- [x] Application 中调度接入

### 阶段四：隐私安全 (P1) ✅ 已完成 (2026-04-11)

- [x] SQLCipher 加密数据库（`net.zetetic:sqlcipher-android:4.10.0`，SupportOpenHelperFactory）
- [x] SecurityKeyManager KeyStore 密钥管理（EncryptedSharedPreferences + AES256_GCM MasterKey）
- [x] DiffSyncManager 差分同步（SyncTarget 接口，双向 push/pull，version 字段冲突解决）
- [x] AuditLogger 安全审计（SHA-256 哈希链防篡改，STORE/DELETE/MERGE/SYNC 四种操作）
- [x] 数据库迁移 v2→v3（ALTER TABLE memories ADD COLUMN version）

### 阶段五：冷启动优化 (P2) ✅ 已完成 (2026-04-12)

- [x] ColdStartManager + MemoryMode 枚举（SENSORY_ONLY / FULL，72h 冷启动期）
- [x] UserProfileBuilderWorker 后台构建用户画像（WorkManager 24h 周期任务）
- [x] MemoryManager 模式检查集成（SENSORY_ONLY 下 store 只存 PREFERENCE/FACT，hybridSearch 降级）
- [x] Koin DI 注册 + Application onCreate 集成

---

## 📊 性能目标

| 指标 | 当前 | 目标 | 测量方式 |
|------|------|------|---------|
| 检索延迟 | N/A | <100ms (P95) | 端到端计时 |
| 内存占用 | ~50MB | <100MB | Android Profiler |
| 存储效率 | 1x | 3:1 压缩 | 数据库大小/记忆数 |
| 冷启动时间 | ~2s | <1s | 首次检索延迟 |
| 电池影响 | N/A | <1%/天 | 后台任务耗电 |

---

## 🔍 测试策略

### 单元测试

```kotlin
// app/src/test/java/ai/openclaw/android/memory/SensoryBufferTest.kt

@Test
fun `buffer removes expired entries`() {
    val buffer = SensoryBuffer(maxLength = 50, maxAgeMinutes = 5)
    
    // 添加过期条目
    buffer.add(InteractionEntry(timestamp = System.currentTimeMillis() - 10 * 60_000))
    
    // 验证已移除
    assertTrue(buffer.getRecent().isEmpty())
}

@Test
fun `buffer respects max length`() {
    val buffer = SensoryBuffer(maxLength = 10)
    
    // 添加 15 条记录
    repeat(15) { buffer.add(InteractionEntry()) }
    
    // 验证只保留最近 10 条
    assertEquals(10, buffer.getRecent().size)
}
```

### 集成测试

```kotlin
// app/src/androidTest/java/ai/openclaw/android/memory/HybridSearchIntegrationTest.kt

@Test
fun `hybrid search returns relevant results`() = runTest {
    val engine = HybridSearchEngine(...)
    
    // 插入测试数据
    insertTestMemories()
    
    // 执行搜索
    val results = engine.search(SearchQuery(text = "上海 面馆"))
    
    // 验证结果相关性
    assertTrue(results.any { it.memory.content.contains("德兴馆") })
    assertTrue(results.all { it.combinedScore > 0.5 })
}
```

---

## ⚠️ 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 嵌入模型体积过大 | 安装包增加 30MB | 使用 ONNX 量化版，动态下载 |
| 向量检索耗电 | 电池消耗增加 | 仅在充电时批量索引 |
| 隐私泄露风险 | 高 | SQLCipher + KeyStore 双重加密 |
| 检索精度不足 | 用户体验差 | A/B 测试调整权重参数 |

---

## 📚 参考资料

1. [sqlite-vec 文档](https://github.com/asg017/sqlite-vec)
2. [BGE Embedding 模型](https://huggingface.co/BAAI/bge-small-zh-v1.5)
3. [MobileCLIP 论文](https://arxiv.org/abs/2311.17049)
4. [SQLCipher 集成指南](https://www.zetetic.net/sqlcipher/sqlcipher-for-android/)
5. [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)

---

## 📝 更新日志

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|---------|------|
| 2026-04-10 | v1.0 | 初始版本 | Windows |
| 2026-04-11 | v2.0 | 阶段一~三代码编写完成（Claude Code + GLM-5.1） | Windows |
| 2026-04-11 | v3.0 | 阶段四安全模块完成（SQLCipher、KeyStore、DiffSync、AuditLogger） | Windows |
| 2026-04-12 | v4.0 | **全部五阶段完成**：冷启动优化（ColdStartManager、UserProfileBuilder、模式集成） | Windows |
