package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.model.LocalLLMClient
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryExtractorTest {
    
    private val mockLlmClient = mock<LocalLLMClient>()
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
    
    @Test
    fun `classifyType should correctly identify preference type`() {
        val result = memoryExtractor.classifyType("我喜欢这个功能")
        assertEquals(MemoryType.PREFERENCE, result)
    }
    
    @Test
    fun `classifyType should correctly identify task type`() {
        val result = memoryExtractor.classifyType("明天要完成项目")
        assertEquals(MemoryType.TASK, result)
    }
    
    @Test
    fun `extractTags should correctly identify tags`() {
        val content = "项目工作相关的内容"
        val result = memoryExtractor.extractTags(content)
        
        assertTrue(result.contains("项目"))
        assertTrue(result.contains("工作"))
    }
}

class MemoryManagerTest {
    
    private val mockMemoryDao = mock<ai.openclaw.android.data.dao.MemoryDao>()
    private val mockVectorDao = mock<ai.openclaw.android.data.dao.MemoryVectorDao>()
    private val mockEmbeddingService = mock<ai.openclaw.android.domain.service.EmbeddingService>()
    private val mockExtractor = mock<MemoryExtractorInterface>()
    
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
        
        whenever(mockEmbeddingService.isReady()).thenReturn(true)
        whenever(mockEmbeddingService.embed(any())).thenReturn(FloatArray(384))
        whenever(mockMemoryDao.insert(any())).thenReturn(1L)
        
        val result = memoryManager.store(memory)
        
        assertTrue(result.isSuccess)
        verify(mockMemoryDao).insert(any())
        verify(mockVectorDao).insert(any())
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
        
        whenever(mockMemoryDao.getByType(MemoryType.FACT, 20)).thenReturn(expectedMemories)
        
        val result = memoryManager.getByType(MemoryType.FACT)
        
        assertEquals(expectedMemories, result)
        verify(mockMemoryDao).getByType(MemoryType.FACT, 20)
    }
}