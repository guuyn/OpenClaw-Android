package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.model.LocalLLMClient
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.local.MemoryVectorDao
import ai.openclaw.android.domain.memory.EmbeddingService
import ai.openclaw.android.domain.memory.MemoryExtractorInterface

class MemoryExtractorTest {

    private val mockLlmClient = mockk<LocalLLMClient>()
    private val memoryExtractor = LlmMemoryExtractor(mockLlmClient)

    @Test
    fun `extractFromConversation should return empty list when messages is empty`() = runTest {
        val result = memoryExtractor.extractFromConversation(emptyList())

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull()?.size)
    }

    @Test
    fun `extractFromUserInput should correctly create MemoryEntity`() = runTest {
        val content = "我喜欢 Kotlin 编程"
        val result = memoryExtractor.extractFromUserInput(content)

        assertTrue(result.isSuccess)
        val memory = result.getOrNull()!!
        assertEquals(content, memory.content)
        assertEquals(MemoryType.PREFERENCE, memory.memoryType)
        assertEquals(3, memory.priority)
        assertEquals("manual", memory.source)
    }

    // 移除对私有函数的直接访问测试，因为它们是内部实现细节
}

class MemoryManagerTest {

    private val mockMemoryDao = mockk<MemoryDao>()
    private val mockVectorDao = mockk<MemoryVectorDao>()
    private val mockEmbeddingService = mockk<EmbeddingService>()
    private val mockExtractor = mockk<MemoryExtractorInterface>()

    private val memoryManager = MemoryManager(
        mockMemoryDao,
        mockVectorDao,
        mockEmbeddingService,
        mockExtractor
    )

    @Test
    fun `store should save memory and vector successfully`() = runTest {
        val memory = MemoryEntity(
            content = "Test memory",
            memoryType = MemoryType.FACT,
            priority = 3,
            source = "test",
            tags = listOf("test"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        every { mockEmbeddingService.isReady() } returns true
        coEvery { mockEmbeddingService.embed(any()) } returns FloatArray(384)
        coEvery { mockMemoryDao.insert(any()) } returns 1L
        coEvery { mockVectorDao.insert(any()) } just Runs

        val result = memoryManager.store(memory)

        assertTrue(result.isSuccess)
        coVerify { mockMemoryDao.insert(any()) }
        coVerify { mockVectorDao.insert(any()) }
    }

    @Test
    fun `getByType should call dao with correct parameters`() = runTest {
        val expectedMemories = listOf(
            MemoryEntity(
                content = "Test memory",
                memoryType = MemoryType.FACT,
                priority = 3,
                source = "test",
                tags = listOf("test"),
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )
        )

        coEvery { mockMemoryDao.getByType(MemoryType.FACT, 20) } returns expectedMemories

        val result = memoryManager.getByType(MemoryType.FACT)

        assertEquals(expectedMemories, result)
        coVerify { mockMemoryDao.getByType(MemoryType.FACT, 20) }
    }
}
