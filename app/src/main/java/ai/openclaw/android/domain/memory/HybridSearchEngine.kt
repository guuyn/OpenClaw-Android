package ai.openclaw.android.domain.memory

import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.exp
import kotlin.math.ln

/**
 * Hybrid search combining BM25 keyword search with vector similarity and time decay.
 * Score fusion: combinedScore = 0.35 * keywordScore + 0.55 * vectorScore + 0.10 * recency
 */
class HybridSearchEngine(
    private val bm25Index: BM25Index,
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingService: EmbeddingService
) {
    companion object {
        private const val TAG = "HybridSearchEngine"
        private const val KEYWORD_WEIGHT = 0.35f
        private const val VECTOR_WEIGHT = 0.55f
        private const val RECENCY_WEIGHT = 0.10f
        private const val MIN_COMBINED_SCORE = 0.3f
        private const val DEFAULT_SEARCH_DAYS = 90L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        /** Half-life in days for exponential time decay. */
        private const val RECENCY_HALF_LIFE_DAYS = 30.0
        private const val VECTOR_CACHE_TTL_MS = 30_000L
    }

    private data class VectorCacheEntry(val scores: Map<Long, Float>, val timestamp: Long)
    private val vectorCache = LinkedHashMap<String, VectorCacheEntry>(16, 0.75f, true)
    private var vectorCacheLastCleanup = 0L

    private fun getCachedVectorResult(query: String): Map<Long, Float>? {
        val now = System.currentTimeMillis()
        if (now - vectorCacheLastCleanup > VECTOR_CACHE_TTL_MS) {
            vectorCache.entries.removeAll { now - it.value.timestamp > VECTOR_CACHE_TTL_MS }
            vectorCacheLastCleanup = now
        }
        val entry = vectorCache[query] ?: return null
        if (now - entry.timestamp > VECTOR_CACHE_TTL_MS) {
            vectorCache.remove(query)
            return null
        }
        return entry.scores
    }

    data class SearchResult(
        val memory: MemoryEntity,
        val keywordScore: Double,
        val vectorScore: Float,
        val recencyScore: Float,
        val combinedScore: Float
    )

    /** Detailed score breakdown for diagnostics. */
    data class ScoreBreakdown(
        val memoryId: Long,
        val keywordRaw: Double,
        val keywordNorm: Float,
        val vectorRaw: Float,
        val vectorNorm: Float,
        val recencyRaw: Float,
        val combinedScore: Float
    )

    /** Compute recency in [0, 1] via exponential decay from lastAccessedAt. */
    private fun computeRecency(lastAccessedAt: Long): Float {
        val now = System.currentTimeMillis()
        val ageDays = ((now - lastAccessedAt) / DAY_MS).coerceAtLeast(0).toDouble()
        // exp(-ln(2) * age / halfLife) → 1.0 when fresh, ~0.5 at halfLife, →0 as age→∞
        return exp(-ln(2.0) * ageDays / RECENCY_HALF_LIFE_DAYS).toFloat()
    }

    /** Core fusion logic shared by all search methods. */
    private suspend fun fuseResults(
        keywordScores: Map<Long, Double>,
        vectorScores: Map<Long, Float>,
        topK: Int,
        includeDetails: Boolean = false
    ): Pair<List<SearchResult>, List<ScoreBreakdown>> {
        val maxKwScore = keywordScores.values.maxOrNull() ?: 0.0
        val maxVecScore = vectorScores.values.maxOrNull() ?: 0f

        val allCandidateIds = keywordScores.keys + vectorScores.keys
        val memoryMap = if (allCandidateIds.isNotEmpty()) {
            memoryDao.getByIds(allCandidateIds.toList()).associateBy { it.id }
        } else {
            emptyMap()
        }
        val results = mutableListOf<SearchResult>()
        val details = mutableListOf<ScoreBreakdown>()

        for (memoryId in allCandidateIds) {
            val memory = memoryMap[memoryId] ?: continue

            val normKw = if (maxKwScore > 0) ((keywordScores[memoryId] ?: 0.0) / maxKwScore).toFloat() else 0f
            val normVec = if (maxVecScore > 0) (vectorScores[memoryId] ?: 0f) / maxVecScore else 0f
            val recency = computeRecency(memory.lastAccessedAt)

            val combined = KEYWORD_WEIGHT * normKw + VECTOR_WEIGHT * normVec + RECENCY_WEIGHT * recency

            if (includeDetails) {
                details.add(ScoreBreakdown(
                    memoryId = memoryId,
                    keywordRaw = keywordScores[memoryId] ?: 0.0,
                    keywordNorm = normKw,
                    vectorRaw = vectorScores[memoryId] ?: 0f,
                    vectorNorm = normVec,
                    recencyRaw = recency,
                    combinedScore = combined
                ))
            }

            if (combined >= MIN_COMBINED_SCORE) {
                results.add(SearchResult(
                    memory = memory,
                    keywordScore = keywordScores[memoryId] ?: 0.0,
                    vectorScore = vectorScores[memoryId] ?: 0f,
                    recencyScore = recency,
                    combinedScore = combined
                ))
            }
        }

        val sorted = results.sortedByDescending { it.combinedScore }.take(topK)
        return sorted to details
    }

    /** Run a single BM25 keyword search and return scored map. */
    private suspend fun keywordSearch(query: String, topK: Int): Map<Long, Double> {
        val bm25Results = bm25Index.search(query, topK * 3)
        val scores = mutableMapOf<Long, Double>()
        for (r in bm25Results) {
            scores[r.memoryId] = r.score
        }
        return scores
    }

    /** Run vector similarity search and return scored map. Results are cached for 30s. */
    private suspend fun vectorSearch(query: String, since: Long): Map<Long, Float> {
        getCachedVectorResult(query)?.let { return it }

        val queryVector = if (embeddingService.isReady()) {
            embeddingService.embed(query)
        } else {
            FloatArray(embeddingService.getDimension()) {
                (query.hashCode() * (it + 1) % 1000) / 1000f
            }
        }

        val vectors = vectorDao.getRecent(since, 300).ifEmpty { vectorDao.getAll() }
        val scores = mutableMapOf<Long, Float>()
        for (vec in vectors) {
            val sim = MemoryManager.cosineSimilarity(queryVector, vec.vector)
            if (sim > 0.1f) {
                scores[vec.memoryId] = sim
            }
        }

        vectorCache[query] = VectorCacheEntry(scores, System.currentTimeMillis())
        return scores
    }

    /** Standard hybrid search. */
    suspend fun search(query: String, topK: Int = 10): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val since = System.currentTimeMillis() - DEFAULT_SEARCH_DAYS * DAY_MS
        val keywordScores = keywordSearch(query, topK)
        val vectorScores = vectorSearch(query, since)

        val (sorted, _) = fuseResults(keywordScores, vectorScores, topK)

        LogManager.shared.log("INFO", TAG,
            "Hybrid search: query='${query.take(20)}', kw=${keywordScores.size}, vec=${vectorScores.size}, merged=${sorted.size}")
        return sorted
    }

    /** Search with detailed score breakdown for each candidate. */
    suspend fun searchWithDetails(query: String, topK: Int = 10): Pair<List<SearchResult>, List<ScoreBreakdown>> {
        if (query.isBlank()) return emptyList<SearchResult>() to emptyList<ScoreBreakdown>()

        val since = System.currentTimeMillis() - DEFAULT_SEARCH_DAYS * DAY_MS
        val keywordScores = keywordSearch(query, topK)
        val vectorScores = vectorSearch(query, since)

        val (sorted, details) = fuseResults(keywordScores, vectorScores, topK, includeDetails = true)

        LogManager.shared.log("INFO", TAG,
            "Hybrid search w/ details: query='${query.take(20)}', kw=${keywordScores.size}, vec=${vectorScores.size}, merged=${sorted.size}")
        return sorted to details
    }

    /** Parallel hybrid search — BM25 and vector paths run concurrently via CoroutineScope(Dispatchers.Default). */
    suspend fun asyncSearch(query: String, topK: Int = 10, timeRange: Pair<Long, Long>? = null): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val since = timeRange?.first ?: (System.currentTimeMillis() - DEFAULT_SEARCH_DAYS * DAY_MS)

        val (keywordScores, vectorScores) = coroutineScope {
            val kwDeferred = async(Dispatchers.Default) { keywordSearch(query, topK) }
            val vecDeferred = async(Dispatchers.Default) { vectorSearch(query, since) }
            Pair(kwDeferred.await(), vecDeferred.await())
        }

        val (sorted, _) = fuseResults(keywordScores, vectorScores, topK)

        LogManager.shared.log("INFO", TAG,
            "Async hybrid search: query='${query.take(20)}', kw=${keywordScores.size}, vec=${vectorScores.size}, merged=${sorted.size}")
        return sorted
    }

    suspend fun rebuildIndex() {
        LogManager.shared.log("INFO", TAG, "Rebuilding BM25 index...")
        bm25Index.rebuildFromDao(memoryDao)
        LogManager.shared.log("INFO", TAG, "BM25 index rebuilt (${bm25Index.size} docs)")
    }
}
