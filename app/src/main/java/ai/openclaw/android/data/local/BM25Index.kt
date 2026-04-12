package ai.openclaw.android.data.local

import ai.openclaw.android.data.model.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * Pure Kotlin BM25 in-memory inverted index for memory search.
 * Simple Chinese (bigram) + English tokenization.
 */
class BM25Index(
    private val k1: Float = 1.5f,
    private val b: Float = 0.75f
) {
    data class BM25Result(
        val memoryId: Long,
        val score: Double
    )

    private data class Posting(
        val memoryId: Long,
        val tf: Int
    )

    private val invertedIndex = ConcurrentHashMap<String, MutableList<Posting>>()
    private val docLengths = ConcurrentHashMap<Long, Int>()
    private var totalDocs: Int = 0
    private var avgDocLength: Double = 0.0
    /** Running sum of all doc lengths, for O(1) avgDocLength update. */
    private var totalTokenCount: Long = 0L

    private val queryTokenCache = LruCache<String, List<String>>(64)

    /** Day-level time buckets for fast time-range filtering. Key = createdAt / DAY_MS. */
    private val timeBuckets = ConcurrentHashMap<Long, MutableSet<Long>>()

    companion object {
        private const val DAY_MS = 86_400_000L
    }

    /**
     * Tokenize text: Chinese character bigrams + English lowercase words.
     */
    fun tokenize(text: String): List<String> {
        queryTokenCache.get(text)?.let { return it }

        val tokens = mutableListOf<String>()
        val chars = text.toCharArray()
        var i = 0

        while (i < chars.size) {
            val c = chars[i]
            when {
                c in '\u4e00'..'\u9fff' -> {
                    // Collect consecutive CJK run, emit bigrams
                    val run = StringBuilder()
                    while (i < chars.size && chars[i] in '\u4e00'..'\u9fff') {
                        run.append(chars[i])
                        i++
                    }
                    val s = run.toString()
                    for (j in 0 until s.length - 1) {
                        tokens.add("${s[j]}${s[j + 1]}")
                    }
                    // Keep short runs as-is (2-4 chars are meaningful Chinese words)
                    if (s.length in 2..4) tokens.add(s)
                }
                c.isLetterOrDigit() -> {
                    // Collect English/number word
                    val word = StringBuilder()
                    while (i < chars.size && chars[i].isLetterOrDigit()) {
                        word.append(chars[i])
                        i++
                    }
                    tokens.add(word.toString().lowercase())
                }
                else -> i++
            }
        }
        val result = tokens.toList()
        queryTokenCache.put(text, result)
        return result
    }

    /**
     * Index a single memory entity.
     */
    fun index(entity: MemoryEntity) {
        removeFromIndex(entity.id)

        val tokens = tokenize(entity.content)
        if (tokens.isEmpty()) return

        val tfMap = mutableMapOf<String, Int>()
        for (t in tokens) {
            tfMap[t] = (tfMap[t] ?: 0) + 1
        }
        for ((term, freq) in tfMap) {
            invertedIndex.getOrPut(term) { mutableListOf() }.add(Posting(entity.id, freq))
        }

        docLengths[entity.id] = tokens.size
        totalDocs = docLengths.size
        totalTokenCount += tokens.size
        avgDocLength = if (totalDocs == 0) 0.0 else totalTokenCount.toDouble() / totalDocs

        val dayBucket = entity.createdAt / DAY_MS
        timeBuckets.getOrPut(dayBucket) { ConcurrentHashMap.newKeySet() }.add(entity.id)
    }

    /**
     * Remove a document from the index.
     */
    fun removeFromIndex(memoryId: Long) {
        // Remove from timeBuckets — scan all buckets since we don't have createdAt here.
        // This is rare (only on explicit removal) and bucket count is small.
        for (bucket in timeBuckets.values) {
            bucket.remove(memoryId)
        }
        timeBuckets.entries.removeAll { it.value.isEmpty() }

        val iter = invertedIndex.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.removeAll { it.memoryId == memoryId }
            if (entry.value.isEmpty()) iter.remove()
        }
        val removedLength = docLengths.remove(memoryId)
        if (removedLength != null) totalTokenCount -= removedLength
        totalDocs = docLengths.size
        avgDocLength = if (totalDocs == 0) 0.0 else totalTokenCount.toDouble() / totalDocs
    }

    /**
     * Search with BM25 scoring. Returns top-K results.
     */
    fun search(query: String, topK: Int = 20, timeFrom: Long? = null, timeTo: Long? = null): List<BM25Result> {
        if (query.isBlank() || totalDocs == 0) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        // Build candidate set from time buckets if time range is specified
        val timeCandidates: Set<Long>? = if (timeFrom != null || timeTo != null) {
            val fromDay = (timeFrom ?: 0L) / DAY_MS
            val toDay = (timeTo ?: Long.MAX_VALUE) / DAY_MS
            val candidates = mutableSetOf<Long>()
            for ((day, ids) in timeBuckets) {
                if (day in fromDay..toDay) {
                    candidates.addAll(ids)
                }
            }
            candidates
        } else null

        val scores = mutableMapOf<Long, Double>()

        for (qToken in queryTokens) {
            val postings = invertedIndex[qToken] ?: continue
            val df = postings.size
            val idf = ln((totalDocs - df + 0.5) / (df + 0.5) + 1.0)

            for (posting in postings) {
                if (timeCandidates != null && posting.memoryId !in timeCandidates) continue
                val dl = docLengths[posting.memoryId] ?: continue
                val tfNorm = (posting.tf * (k1 + 1f)) /
                    (posting.tf + k1 * (1f - b + b * dl / avgDocLength.coerceAtLeast(1.0)).toFloat())
                scores[posting.memoryId] = (scores[posting.memoryId] ?: 0.0) + idf * tfNorm
            }
        }

        return scores.map { (id, score) -> BM25Result(id, score) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Rebuild the entire index from DAO. Call on startup or after bulk changes.
     */
    suspend fun rebuildFromDao(memoryDao: MemoryDao) = withContext(Dispatchers.Default) {
        clear()
        val memories = memoryDao.getAll()
        for (memory in memories) {
            index(memory)
        }
    }

    fun clear() {
        invertedIndex.clear()
        docLengths.clear()
        timeBuckets.clear()
        queryTokenCache.evictAll()
        totalDocs = 0
        avgDocLength = 0.0
        totalTokenCount = 0L
    }

    val size: Int get() = totalDocs
}
