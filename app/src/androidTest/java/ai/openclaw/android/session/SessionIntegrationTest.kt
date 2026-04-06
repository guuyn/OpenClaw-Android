package ai.openclaw.android.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: HybridSessionManager
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        
        val llmClient = LocalLLMClient.getInstance(context)
        
        manager = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
            llmClient = llmClient,
            tokenCounter = TokenCounter()
        )
    }
    
    @After
    fun teardown() {
        db.close()
    }
    
    @Test
    fun fullConversation_withCompression() = runTest {
        // 初始化
        manager.initialize()
        
        // 模拟完整对话
        val conversation = listOf(
            "我想开发一个 Android 应用" to MessageRole.USER,
            "好的，你想做什么类型的应用？" to MessageRole.ASSISTANT,
            "一个会话管理工具" to MessageRole.USER,
            "明白，需要什么功能？" to MessageRole.ASSISTANT,
            "长期持久，分层压缩" to MessageRole.USER
        )
        
        conversation.forEach { (content, role) ->
            manager.addMessage(role, content)
        }
        
        // 验证消息存储
        val context = manager.getConversationContext()
        assertTrue(context.size >= conversation.size)
    }
    
    @Test
    fun sessionRecovery_afterRestart() = runTest {
        // 创建会话并添加消息
        manager.initialize()
        manager.addMessage(MessageRole.USER, "记住这个信息")
        val sessionId = manager.getCurrentSessionId()!!
        
        // 模拟重启（创建新的 manager）
        val newManager = HybridSessionManager(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            summaryDao = db.summaryDao(),
            llmClient = LocalLLMClient.getInstance(ApplicationProvider.getApplicationContext()),
            tokenCounter = TokenCounter()
        )
        
        // 恢复会话
        newManager.initialize()
        
        // 验证恢复
        assertEquals(sessionId, newManager.getCurrentSessionId())
        val context = newManager.getConversationContext()
        assertTrue(context.any { it.content.contains("记住这个信息") })
    }
    
    @Test
    fun compression_triggersWhenOverThreshold() = runTest {
        manager.initialize()
        
        // 添加大量消息触发压缩
        repeat(30) { i ->
            manager.addMessage(MessageRole.USER, "这是一条测试消息，内容比较长 $i")
        }
        
        // 验证压缩发生
        val messages = db.messageDao().getBySession(manager.getCurrentSessionId()!!)
        // 压缩后消息应该 < 30 条（部分被摘要替代）
        assertTrue(messages.size < 30)
    }
}