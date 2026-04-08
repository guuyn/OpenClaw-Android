package ai.openclaw.android.domain.memory

/**
 * Mock embedding service for testing.
 * Generates deterministic vectors based on text content hash,
 * so identical/similar texts produce similar vectors.
 */
class MockEmbeddingService(
    private val dimension: Int = 384,
    private val ready: Boolean = true
) : EmbeddingService {

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension)
        val hash = text.hashCode()
        for (i in 0 until dimension) {
            // Generate deterministic pseudo-random values from text hash
            vector[i] = ((hash * (i + 1) * 31) % 2000 - 1000) / 1000f
        }
        // Normalize to unit vector for proper cosine similarity
        return normalize(vector)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = dimension

    override fun isReady(): Boolean = ready

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (value in v) norm += value * value
        norm = kotlin.math.sqrt(norm)
        if (norm < 1e-10) return v
        return FloatArray(v.size) { i -> v[i] / norm.toFloat() }
    }
}
