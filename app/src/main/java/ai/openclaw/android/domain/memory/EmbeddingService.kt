package ai.openclaw.android.domain.memory

interface EmbeddingService {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    fun getDimension(): Int
    fun isReady(): Boolean
}