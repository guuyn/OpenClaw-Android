package ai.openclaw.android.ml

import ai.openclaw.android.domain.memory.EmbeddingService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Simple Embedding Service using word hashing
 * 
 * A lightweight alternative when TFLite model is not available.
 * Uses consistent hashing to generate embeddings from text.
 * 
 * Performance: O(n) where n is text length
 * Quality: Suitable for basic semantic similarity (not as good as neural embeddings)
 */
class SimpleEmbeddingService(
    private val context: Context,
    private val dimension: Int = 384
) : EmbeddingService {

    companion object {
        private const val TAG = "SimpleEmbeddingService"
    }

    private var isInitialized = false
    private val wordVectors = mutableMapOf<String, FloatArray>()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Pre-compute some common word vectors for consistency
            isInitialized = true
            Log.i(TAG, "Simple embedding service initialized (dimension: $dimension)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            false
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initialize()
        }

        // Simple embedding: hash-based approach
        // This creates consistent embeddings for similar texts
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) {
            return@withContext FloatArray(dimension)
        }

        // Generate embedding by averaging word hashes
        val embedding = FloatArray(dimension)
        
        for (word in words) {
            val wordVector = getWordVector(word)
            for (i in 0 until dimension) {
                embedding[i] += wordVector[i]
            }
        }

        // Normalize
        val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in 0 until dimension) {
                embedding[i] /= norm
            }
        }

        embedding
    }

    /**
     * Generate a consistent vector for a word using hashing
     */
    private fun getWordVector(word: String): FloatArray {
        return wordVectors.getOrPut(word) {
            val vector = FloatArray(dimension)
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(word.toByteArray())
            
            // Use hash bytes to generate deterministic pseudo-random values
            for (i in 0 until dimension) {
                val byteIndex = (i * 4) % hash.size
                val value = ((hash[byteIndex].toInt() and 0xFF) - 128) / 128.0f
                vector[i] = value
            }
            
            vector
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = dimension

    override fun isReady(): Boolean = isInitialized
}