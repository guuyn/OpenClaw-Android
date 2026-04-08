package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MemoryVectorEntity
import kotlin.math.sqrt

class MemoryManager(
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingService: EmbeddingService,
    private val extractor: MemoryExtractorInterface
) {
    suspend fun store(memory: MemoryEntity): Result<MemoryEntity> = runCatching {
        // 生成 embedding（如果服务可用）
        val vector = if (embeddingService.isReady()) {
            embeddingService.embed(memory.content)
        } else {
            // 降级：生成伪向量
            FloatArray(embeddingService.getDimension()) { (memory.content.hashCode() * (it + 1) % 1000) / 1000f }
        }

        // 存储记忆
        val id = memoryDao.insert(memory)

        // 存储向量
        vectorDao.insert(MemoryVectorEntity(
            memoryId = id,
            vector = vector,
            updatedAt = System.currentTimeMillis()
        ))

        memory.copy(id = id)
    }

    suspend fun search(
        query: String,
        limit: Int = 10,
        threshold: Float = 0.5f
    ): List<MemorySearchResult> {
        // 生成查询向量
        val queryVector = if (embeddingService.isReady()) {
            embeddingService.embed(query)
        } else {
            FloatArray(embeddingService.getDimension()) { (query.hashCode() * (it + 1) % 1000) / 1000f }
        }

        // 暴力搜索：加载所有向量并计算余弦相似度
        val allVectors = vectorDao.getAll()
        val scored = allVectors.mapNotNull { vec ->
            val similarity = cosineSimilarity(queryVector, vec.vector)
            if (similarity >= threshold) {
                val memory = memoryDao.getById(vec.memoryId) ?: return@mapNotNull null
                MemorySearchResult(memory, similarity)
            } else null
        }

        return scored.sortedByDescending { it.similarity }.take(limit)
    }
    
    suspend fun getByType(type: MemoryType, limit: Int = 20): List<MemoryEntity> {
        return memoryDao.getByType(type, limit)
    }
    
    suspend fun getImportantMemories(limit: Int = 10): List<MemoryEntity> {
        return memoryDao.getHighPriority(limit)
    }
    
    suspend fun touch(memoryId: Long) {
        memoryDao.updateAccess(memoryId, System.currentTimeMillis())
    }
    
    suspend fun extractAndStore(messages: List<ai.openclaw.android.data.model.MessageEntity>): Result<Int> = runCatching {
        val memories = extractor.extractFromConversation(messages).getOrThrow()
        memories.forEach { store(it) }
        memories.size
    }
    
    suspend fun addManual(content: String, type: MemoryType? = null): Result<MemoryEntity> {
        return extractor.extractFromUserInput(content, type)
            .getOrThrow()
            .let { store(it) }
    }
    
    suspend fun cleanup(days: Int = 30, minAccessCount: Int = 2): Int {
        val threshold = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        val toDelete = memoryDao.findStale(threshold, minAccessCount)
        toDelete.forEach { memory ->
            memoryDao.delete(memory)
            vectorDao.deleteByMemoryId(memory.id)
        }
        return toDelete.size
    }

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vector dimensions must match: ${a.size} vs ${b.size}" }
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = sqrt(normA) * sqrt(normB)
            if (denominator < 1e-10) return 0f
            return (dotProduct / denominator).toFloat()
        }
    }
}