package ai.openclaw.android.domain.session

import ai.openclaw.android.data.local.MessageDao
import ai.openclaw.android.data.local.SessionDao
import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.model.LocalLLMClient
import android.content.Context
import androidx.room.Room
import ai.openclaw.android.data.local.AppDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridSessionManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao
    private lateinit var summaryDao: SummaryDao
    private lateinit var manager: HybridSessionManager
    private lateinit var mockLlmClient: LocalLLMClient
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
        summaryDao = db.summaryDao()
        mockLlmClient = mockk()
        
        manager = HybridSessionManager(
            sessionDao = sessionDao,
            messageDao = messageDao,
            summaryDao = summaryDao,
            llmClient = mockLlmClient,
            tokenCounter = TokenCounter()
        )
    }
    
    @Test
    fun initialize_createsDefaultSession() = runTest {
        coEvery { mockLlmClient.isModelLoaded() } returns false
        val session = manager.initialize()
        assertNotNull(session)
        assertNull(session.name)
    }
    
    @Test
    fun addMessage_increasesTokenCount() = runTest {
        coEvery { mockLlmClient.isModelLoaded() } returns false
        manager.initialize()
        manager.addMessage(MessageRole.USER, "测试消息")
        
        val session = sessionDao.getSessionById(manager.getCurrentSessionId()!!)
        assertTrue(session!!.tokenCount > 0)
    }
    
    @Test
    fun getConversationContext_returnsMessages() = runTest {
        coEvery { mockLlmClient.isModelLoaded() } returns false
        manager.initialize()
        manager.addMessage(MessageRole.USER, "用户消息")
        manager.addMessage(MessageRole.ASSISTANT, "助手回复")
        
        val context = manager.getConversationContext()
        assertEquals(2, context.size)
    }
}