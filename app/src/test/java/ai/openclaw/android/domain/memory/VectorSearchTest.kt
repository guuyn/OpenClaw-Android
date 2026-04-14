package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MemoryVectorEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CosineSimilarityTest {

    @Test
    fun `identical vectors should have similarity 1`() {
        val v = floatArrayOf(1f, 0f, 0f)
        val sim = MemoryManager.cosineSimilarity(v, v)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `orthogonal vectors should have similarity 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val sim = MemoryManager.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun `opposite vectors should have similarity -1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        val sim = MemoryManager.cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 0.001f)
    }

    @Test
    fun `similarity should be scale invariant`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f) // same direction, scaled
        val sim = MemoryManager.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `zero vectors should return 0 similarity`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        val sim = MemoryManager.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun `dimension mismatch should throw`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertThrows(IllegalArgumentException::class.java) {
            MemoryManager.cosineSimilarity(a, b)
        }
    }

    @Test
    fun `high dimensional vectors should compute correctly`() {
        // Create two vectors with known dot product
        val dim = 384
        val a = FloatArray(dim) { i -> if (i < 10) 1f else 0f }
        val b = FloatArray(dim) { i -> if (i < 10) 1f else 0f }
        val sim = MemoryManager.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `partial overlap vectors`() {
        val a = floatArrayOf(1f, 1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 1f, 0f)
        val sim = MemoryManager.cosineSimilarity(a, b)
        // dot=1, |a|=sqrt(2), |b|=sqrt(2), cosine=1/2=0.5
        val expected = 0.5f
        assertEquals(expected, sim, 0.001f)
    }
}

class MockEmbeddingServiceTest {

    private lateinit var service: MockEmbeddingService

    @Before
    fun setup() {
        service = MockEmbeddingService(dimension = 64)
    }

    @Test
    fun `isReady returns configured value`() {
        assertTrue(MockEmbeddingService(ready = true).isReady())
        assertFalse(MockEmbeddingService(ready = false).isReady())
    }

    @Test
    fun `getDimension returns configured dimension`() = runTest {
        assertEquals(64, service.getDimension())
        assertEquals(128, MockEmbeddingService(dimension = 128).getDimension())
    }

    @Test
    fun `embed returns vector of correct dimension`() = runTest {
        val vector = service.embed("hello world")
        assertEquals(64, vector.size)
    }

    @Test
    fun `embed produces unit vector`() = runTest {
        val vector = service.embed("hello world")
        var normSq = 0.0
        for (v in vector) normSq += v * v
        val norm = sqrt(normSq).toFloat()
        assertEquals(1.0f, norm, 0.01f)
    }

    @Test
    fun `same text produces same embedding`() = runTest {
        val a = service.embed("test text")
        val b = service.embed("test text")
        assertArrayEquals(a, b, 0.001f)
    }

    @Test
    fun `different texts produce different embeddings`() = runTest {
        val a = service.embed("hello")
        val b = service.embed("world")
        // At least some dimensions should differ
        var diffs = 0
        for (i in a.indices) {
            if (abs(a[i] - b[i]) > 0.001f) diffs++
        }
        assertTrue("Different texts should produce different vectors", diffs > 0)
    }

    @Test
    fun `embedBatch returns correct number of vectors`() = runTest {
        val texts = listOf("one", "two", "three")
        val vectors = service.embedBatch(texts)
        assertEquals(3, vectors.size)
        vectors.forEach { assertEquals(64, it.size) }
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("Mismatch at index $i", expected[i], actual[i], delta)
        }
    }
}

class VectorSearchIntegrationTest {

    private val mockMemoryDao = mockk<MemoryDao>()
    private val mockVectorDao = mockk<MemoryVectorDao>()
    private val embeddingService = MockEmbeddingService(dimension = 64)
    private val mockExtractor = mockk<MemoryExtractorInterface>()

    private lateinit var memoryManager: MemoryManager

    @Before
    fun setup() {
        memoryManager = MemoryManager(
            mockMemoryDao,
            mockVectorDao,
            embeddingService,
            mockExtractor
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun makeMemory(id: Long, content: String) = MemoryEntity(
        id = id,
        content = content,
        memoryType = MemoryType.FACT,
        priority = 3,
        source = "test",
        tags = listOf("test"),
        createdAt = System.currentTimeMillis(),
        lastAccessedAt = System.currentTimeMillis()
    )

    @Test
    fun `search returns memories above threshold sorted by similarity`() = runTest {
        // Use real embedding service to get deterministic vectors
        val query = "Kotlin programming"
        val queryVec = embeddingService.embed(query)

        val memory1 = makeMemory(1, "Kotlin programming")
        val memory2 = makeMemory(2, "Weather forecast")
        val vec1 = embeddingService.embed(memory1.content)
        val vec2 = embeddingService.embed(memory2.content)

        coEvery { mockVectorDao.getAll() } returns listOf(
            MemoryVectorEntity(1, vec1, 1000L),
            MemoryVectorEntity(2, vec2, 1000L)
        )
        coEvery { mockMemoryDao.getById(1) } returns memory1
        coEvery { mockMemoryDao.getById(2) } returns memory2

        val results = memoryManager.search(query, threshold = 0.0f)

        // "Kotlin programming" query should match itself exactly (similarity = 1.0)
        assertEquals(2, results.size)
        assertEquals(1.0f, results[0].similarity, 0.001f)
        assertEquals("Kotlin programming", results[0].memory.content)
        // Results should be sorted by similarity descending
        assertTrue(results[0].similarity >= results[1].similarity)
    }

    @Test
    fun `search filters out results below threshold`() = runTest {
        val query = "Kotlin programming"
        val queryVec = embeddingService.embed(query)
        val memory1 = makeMemory(1, "Kotlin programming")
        val memory2 = makeMemory(2, "Weather forecast")
        val vec1 = embeddingService.embed(memory1.content)
        val vec2 = embeddingService.embed(memory2.content)

        val sim1 = MemoryManager.cosineSimilarity(queryVec, vec1)
        val sim2 = MemoryManager.cosineSimilarity(queryVec, vec2)

        // Use a threshold between the two similarities
        val threshold = (sim1 + sim2) / 2f

        coEvery { mockVectorDao.getAll() } returns listOf(
            MemoryVectorEntity(1, vec1, 1000L),
            MemoryVectorEntity(2, vec2, 1000L)
        )
        coEvery { mockMemoryDao.getById(1) } returns memory1
        coEvery { mockMemoryDao.getById(2) } returns memory2

        val results = memoryManager.search(query, threshold = threshold)

        // Only the higher-similarity result should remain
        assertEquals(1, results.size)
        assertEquals("Kotlin programming", results[0].memory.content)
    }

    @Test
    fun `search returns empty when no vectors stored`() = runTest {
        coEvery { mockVectorDao.getAll() } returns emptyList()

        val results = memoryManager.search("anything")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search respects limit parameter`() = runTest {
        val memories = (1..20).map { i ->
            makeMemory(i.toLong(), "Memory item $i")
        }
        val vectors = memories.map { m ->
            MemoryVectorEntity(m.id, embeddingService.embed(m.content), 1000L)
        }

        coEvery { mockVectorDao.getAll() } returns vectors
        memories.forEach { m ->
            coEvery { mockMemoryDao.getById(m.id) } returns m
        }

        val results = memoryManager.search("Memory item", limit = 5, threshold = 0.0f)

        assertTrue(results.size <= 5)
    }

    @Test
    fun `search handles missing memory gracefully`() = runTest {
        val vec = embeddingService.embed("test")
        coEvery { mockVectorDao.getAll() } returns listOf(
            MemoryVectorEntity(999L, vec, 1000L)
        )
        coEvery { mockMemoryDao.getById(999L) } returns null

        val results = memoryManager.search("test", threshold = 0.0f)

        assertTrue(results.isEmpty())
    }
}
