package ai.openclaw.android.domain.session

import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.SummaryEntity

class SessionCompressorTest {
    private lateinit var compressor: SessionCompressor
    private lateinit var mockLlmClient: LocalLLMClient
    private lateinit var mockSummaryDao: SummaryDao
    
    @Before
    fun setup() {
        mockLlmClient = mockk()
        mockSummaryDao = mockk()
        compressor = SessionCompressor(mockLlmClient, mockSummaryDao)
    }
    
    private fun createTestSession(): SessionEntity {
        return SessionEntity(
            sessionId = "test-session-id",
            name = "Test Session",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )
    }
    
    private fun createTestMessage(id: Long): MessageEntity {
        return MessageEntity(
            id = id,
            sessionId = "test-session-id",
            role = MessageRole.USER,
            content = "Test message $id",
            timestamp = System.currentTimeMillis(),
            tokenCount = 10
        )
    }
    
    @Test
    fun compress_notEnoughMessages_returnsNull() = runTest {
        val session = createTestSession()
        val messages = (1..5).map { createTestMessage(it.toLong()) }
        
        val result = compressor.compress(session, messages, preserveRecent = 10)
        
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
    
    @Test
    fun compress_enoughMessages_returnsSummary() = runTest {
        val session = createTestSession()
        val messages = (1..20).map { createTestMessage(it.toLong()) }

        // LLM not loaded: isLlmReady returns false, so it creates a simple summary
        val compressorNoLlm = SessionCompressor(
            llmClient = mockLlmClient,
            summaryDao = mockSummaryDao,
            isLlmReady = { false }
        )

        val result = compressorNoLlm.compress(session, messages, preserveRecent = 10)

        assertTrue(result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertTrue(summary!!.content.startsWith("早期对话摘要"))
    }
}