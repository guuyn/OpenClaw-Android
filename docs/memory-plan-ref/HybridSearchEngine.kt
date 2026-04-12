package ai.openclaw.android.memory

import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.LogManager

/**
 * Hybrid search engine combining BM25 keyword search with vector similarity search.
 * Uses score fusion: combinedScore = 0.4 * keywordScore + 0.6 * vectorScore
 */
class HybridSearchEngine(
    private val bm25Index: BM25Index,
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingService: EmbeddingService
) {
    companion object {
        private const val TAG = "HybridSearchEngine"
        // Weight for keyword (BM25) score
        private const val KEYWORD_WEIGHT = 0.4f
        // Weight for vector similarity score
        private const val VECTOR_WEIGHT = 0.6f
        // Minimum combined score to include in results
        private const val MIN_COMBINED_SCORE = 0.3f
        // Default search window: 90 days
        private const val DEFAULT_SEARCH_DAYS = 90L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    data class SearchResult(
        val memory: MemoryEntity,
        val keywordScore: Double,
        val vectorScore: Float,
        val combinedScore: Float
    )

    /**
     * Hybrid search: combines BM25 keyword search with vector similarity.
     *
     * Pipeline:
     * 1. BM25 keyword search -> top K candidates
     * 2. Vector similarity search -> top K candidates
     * 3. Merge candidates, normalize scores
     * 4. Score fusion -> combinedScore = w_kw * kw + w_vec * vec
     * 5. Return top results sorted by combined score
     */
    suspend fun search(
        query: String,
        topK: Int = 10,
        timeRange: Pair<Long, Long>? = null
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val since = timeRange?.first
            ?: (System.currentTimeMillis() - DEFAULT_SEARCH_DAYS * DAY_MS)
        val until = timeRange?.second ?: System.currentTimeMillis()

        // --- Keyword path (BM25) ---
        val bm25Results = bm25Index.search(query, topK * 3, Pair(since, until))
        val keywordScores = mutableMapOf<Long, Double>()
        var maxKwScore = 0.0
        for (r in bm25Results) {
            keywordScores[r.memoryId] = r.score
            if (r.score > maxKwScore) maxKwScore = r.score
        }

        // --- Vector path ---
        val queryVector = if (embeddingService.isReady()) {
            embeddingService.embed(query)
        } else {
            // Fallback pseudo-vector
            FloatArray(embeddingService.getDimension()) {
                (query.hashCode() * (it + 1) % 1000) / 1000f
            }
        }

        val vectors = vectorDao.getRecent(since, 300).ifEmpty { vectorDao.getAll() }
        val vectorScores = mutableMapOf<Long, Float>()
        var maxVecScore = 0f
        for (vec in vectors) {
            val ts = 0L // vector entity doesn't have timestamp, skip time filter here
            val sim = MemoryManager.cosineSimilarity(queryVector, vec.vector)
            if (sim > 0.1f) {
                vectorScores[vec.memoryId] = sim
                if (sim > maxVecScore) maxVecScore = sim
            }
        }

        // --- Merge & Score Fusion ---
        val allCandidateIds = keywordScores.keys + vectorScores.keys
        val results = mutableListOf<SearchResult>()

        for (memoryId in allCandidateIds) {
            val memory = memoryDao.getById(memoryId) ?: continue

            // Normalize scores to [0, 1]
            val normKw = if (maxKwScore > 0) (keywordScores[memoryId] ?: 0.0) / maxKwScore else 0.0
            val normVec = if (maxVecScore > 0) (vectorScores[memoryId] ?: 0f) / maxVecScore else 0f

            val combined = KEYWORD_WEIGHT * normKw.toFloat() + VECTOR_WEIGHT * normVec

            if (combined >= MIN_COMBINED_SCORE) {
                results.add(SearchResult(
                    memory = memory,
                    keywordScore = keywordScores[memoryId] ?: 0.0,
                    vectorScore = vectorScores[memoryId] ?: 0f,
                    combinedScore = combined
                ))
            }
        }

        val sorted = results.sortedByDescending { it.combinedScore }.take(topK)

        LogManager.shared.log("INFO", TAG,
            "Hybrid search: query='${query.take(20)}', kw=${bm25Results.size}, vec=${vectorScores.size}, merged=${sorted.size}")

        return sorted
    }

    /**
     * Rebuild BM25 index from database
     */
    suspend fun rebuildIndex() {
        LogManager.shared.log("INFO", TAG, "Rebuilding BM25 index...")
        bm25Index.rebuildFromDao(memoryDao)
        LogManager.shared.log("INFO", TAG, "BM25 index rebuilt")
    }
}
