package ai.openclaw.android.memory

import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.MemoryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Hybrid search engine combining BM25 keyword search with vector similarity.
 *
 * Dual-path recall:
 *   1. Keyword path: BM25 fast match for proper nouns, schedules
 *   2. Vector path: semantic similarity via embedding model
 *
 * Score fusion: combinedScore = 0.4 * keywordScore + 0.6 * vectorScore
 */
class HybridSearchEngine(
    private val memoryDao: MemoryDao,
    private val vectorDao: MemoryVectorDao,
    private val embeddingModel: EmbeddingService,
    private val bm25Index: BM25Index
) {
    companion object {
        private const val TAG = "MemoryHybridSearch"
        private const val KEYWORD_WEIGHT = 0.4
        private const val VECTOR_WEIGHT = 0.6
        private const val SIMILARITY_THRESHOLD = 0.6f
    }

    data class SearchQuery(
        val text: String,
        val maxResults: Int = 20
    )

    data class SearchResult(
        val memory: MemoryEntity,
        val keywordScore: Double,
        val vectorScore: Double,
        val combinedScore: Double
    )

    /**
     * Dual-path recall + re-ranking.
     */
    suspend fun search(query: SearchQuery): List<SearchResult> = withContext(Dispatchers.Default) {
        // 1. Keyword path: BM25 fast match
        val keywordResults = bm25Index.search(query.text, topK = query.maxResults * 2)
        val keywordScores = keywordResults.associate { it.memoryId to it.score }

        // 2. Vector path: semantic similarity
        val queryVector = embeddingModel.embed(query.text)
        val vectorResults = vectorDao.getAll()
        val vectorScores = mutableMapOf<Long, Float>()
        for (vec in vectorResults) {
            val sim = MemoryManager.cosineSimilarity(queryVector, vec.vector)
            if (sim >= SIMILARITY_THRESHOLD) {
                vectorScores[vec.memoryId] = sim
            }
        }

        // 3. Merge and re-rank
        val merged = mergeResults(keywordScores, vectorScores)

        // 4. Score and sort
        rerank(merged, keywordScores, vectorScores)
            .sortedByDescending { it.combinedScore }
            .take(query.maxResults)
    }

    /**
     * Parallel dual-path search for lower latency.
     */
    suspend fun asyncSearch(query: SearchQuery): List<SearchResult> = coroutineScope {
        val kwDeferred = async(Dispatchers.Default) {
            bm25Index.search(query.text, topK = query.maxResults * 2)
                .associate { it.memoryId to it.score }
        }
        val vecDeferred = async(Dispatchers.Default) {
            val queryVector = embeddingModel.embed(query.text)
            val scores = mutableMapOf<Long, Float>()
            for (vec in vectorDao.getAll()) {
                val sim = MemoryManager.cosineSimilarity(queryVector, vec.vector)
                if (sim >= SIMILARITY_THRESHOLD) {
                    scores[vec.memoryId] = sim
                }
            }
            scores
        }

        val keywordScores = kwDeferred.await()
        val vectorScores = vecDeferred.await()
        val merged = mergeResults(keywordScores, vectorScores)

        rerank(merged, keywordScores, vectorScores)
            .sortedByDescending { it.combinedScore }
            .take(query.maxResults)
    }

    private fun mergeResults(
        keywordScores: Map<Long, Double>,
        vectorScores: Map<Long, Float>
    ): Set<Long> {
        return keywordScores.keys + vectorScores.keys
    }

    private suspend fun rerank(
        candidateIds: Set<Long>,
        keywordScores: Map<Long, Double>,
        vectorScores: Map<Long, Float>
    ): List<SearchResult> {
        val maxKw = keywordScores.values.maxOrNull() ?: 0.0
        val maxVec: Float = vectorScores.values.maxOrNull() ?: 0.0f

        return candidateIds.mapNotNull { id ->
            val memory = memoryDao.getById(id) ?: return@mapNotNull null

            val normKw = if (maxKw > 0) (keywordScores[id] ?: 0.0) / maxKw else 0.0
            val normVec = if (maxVec > 0) ((vectorScores[id] ?: 0.0f).toDouble()) / maxVec.toDouble() else 0.0
            val combined = KEYWORD_WEIGHT * normKw + VECTOR_WEIGHT * normVec

            SearchResult(
                memory = memory,
                keywordScore = keywordScores[id] ?: 0.0,
                vectorScore = (vectorScores[id] ?: 0f).toDouble(),
                combinedScore = combined
            )
        }
    }

    suspend fun rebuildIndex() {
        Log.i(TAG, "Rebuilding BM25 index...")
        bm25Index.rebuildFromDao(memoryDao)
        Log.i(TAG, "BM25 index rebuilt (${bm25Index.size} docs)")
    }
}
