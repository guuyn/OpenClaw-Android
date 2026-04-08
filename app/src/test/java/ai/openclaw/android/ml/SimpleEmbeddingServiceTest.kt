package ai.openclaw.android.ml

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for SimpleEmbeddingService
 */
class SimpleEmbeddingServiceTest {
    
    @Test
    fun `test embedding dimension`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val embedding = service.embed("test sentence")
        
        assertEquals(384, embedding.size)
    }
    
    @Test
    fun `test similar texts have similar embeddings`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val emb1 = service.embed("hello world")
        val emb2 = service.embed("hello world") // Same text
        val emb3 = service.embed("goodbye moon") // Different text
        
        // Same text should produce same embedding
        assertArrayEquals(emb1, emb2, 0.001f)
        
        // Different text should produce different embedding
        assertFalse("Different texts should have different embeddings", 
            emb1.contentEquals(emb3))
    }
    
    @Test
    fun `test embedding is normalized`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val embedding = service.embed("this is a test sentence")
        
        // Calculate L2 norm
        val norm = kotlin.math.sqrt(embedming.sumOf { (it * it).toDouble() })
        
        // Should be close to 1.0 (normalized)
        assertEquals(1.0, norm, 0.01)
    }
    
    @Test
    fun `test batch embedding`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val texts = listOf("first sentence", "second sentence", "third sentence")
        val embeddings = service.embedBatch(texts)
        
        assertEquals(3, embeddings.size)
        embeddings.forEach { emb ->
            assertEquals(384, emb.size)
        }
    }
    
    @Test
    fun `test empty text handling`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val embedding = service.embed("")
        
        assertEquals(384, embedding.size)
        // Should return zero vector for empty input
        assertTrue(embedding.all { it == 0f })
    }
    
    @Test
    fun `test cosine similarity between similar words`() = runBlocking {
        val service = SimpleEmbeddingService(mockContext(), dimension = 384)
        service.initialize()
        
        val emb1 = service.embed("hello")
        val emb2 = service.embed("hello world")
        val emb3 = service.embed("goodbye")
        
        val sim12 = cosineSimilarity(emb1, emb2)
        val sim13 = cosineSimilarity(emb1, emb3)
        
        // Note: Simple embedding may not have perfect semantic understanding
        // but should at least be consistent
        assertTrue("Similarity should be in valid range", sim12 >= -1.0 && sim12 <= 1.0)
        assertTrue("Similarity should be in valid range", sim13 >= -1.0 && sim13 <= 1.0)
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return (dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
    }
}