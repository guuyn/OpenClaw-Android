package ai.openclaw.android.domain.session

import ai.openclaw.android.data.local.MessageDao
import ai.openclaw.android.data.local.SessionDao
import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.domain.model.SessionConfig
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.domain.session.TokenCounter
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.Message
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

/**
 * 混合会话管理器 - 整合所有会话相关组件
 */
class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val llmClient: LocalLLMClient,
    private val tokenCounter: TokenCounter
) {
    private var currentSession: SessionEntity? = null
    private val config = SessionConfig()

    /**
     * 初始化/恢复会话
     */
    suspend fun initialize(): SessionEntity {
        // 尝试获取最新的会话，如果没有则创建新的
        val allSessions = sessionDao.getAllSessions().firstOrNull() ?: emptyList()
        val lastSession = allSessions.maxByOrNull { it.lastActiveAt }
        currentSession = lastSession ?: createNewSession()
        return currentSession!!
    }

    /**
     * 添加消息（自动检查压缩）
     */
    suspend fun addMessage(role: MessageRole, content: String): Result<MessageEntity> {
        return try {
            // 确保当前会话存在
            if (currentSession == null) {
                initialize()
            }

            // 计算消息的token数量
            val tokenCount = tokenCounter.estimate(content)

            // 创建消息实体
            val message = MessageEntity(
                id = 0, // Room会自动生成
                sessionId = currentSession!!.sessionId,
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                tokenCount = tokenCount
            )

            // 存储消息
            val messageId = messageDao.insertMessage(message)

            // 更新会话的lastActiveAt和token计数
            currentSession = currentSession?.copy(
                lastActiveAt = System.currentTimeMillis(),
                tokenCount = (currentSession?.tokenCount ?: 0) + tokenCount
            )
            sessionDao.updateSession(currentSession!!)

            // 检查是否需要压缩
            compressIfNeeded()

            Result.success(message.copy(id = messageId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取对话上下文（摘要 + 最近消息）
     */
    suspend fun getConversationContext(): List<MessageEntity> {
        val sessionId = currentSession?.sessionId ?: return emptyList()

        // 获取最新的摘要（如果有）
        val latestSummary = summaryDao.getSummaryBySessionId(sessionId)

        // 获取最近的消息（根据配置决定保留的数量）
        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val recentMessages = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.takeLast(config.preserveRecentMessages)
        } else {
            allMessages
        }

        // 如果有摘要，将其作为SYSTEM消息添加到开头
        return if (latestSummary != null) {
            val summaryMessage = MessageEntity(
                id = 0,
                sessionId = latestSummary.sessionId,
                role = MessageRole.SYSTEM,
                content = latestSummary.content,
                timestamp = latestSummary.compressedAt,
                tokenCount = tokenCounter.estimate(latestSummary.content)
            )
            listOf(summaryMessage) + recentMessages
        } else {
            // 如果没有摘要，返回所有消息
            allMessages
        }
    }

    /**
     * 检查并触发压缩
     */
    suspend fun compressIfNeeded(force: Boolean = false) {
        val session = currentSession ?: return
        
        // 如果强制压缩或token数量超过阈值，则执行压缩
        if (force || session.tokenCount > config.maxTokens) {
            performCompression()
        }
    }

    /**
     * 执行压缩操作
     */
    private suspend fun performCompression() {
        val sessionId = currentSession?.sessionId ?: return

        // 获取需要压缩的消息（除了最近保留的消息外的所有消息）
        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val messagesToCompress = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.take(allMessages.size - config.preserveRecentMessages)
        } else {
            emptyList()
        }

        if (messagesToCompress.isNotEmpty()) {
            // 使用LLM来生成摘要
            val messagesToSummarize = messagesToCompress.map { 
                "${it.role.name}: ${it.content}" 
            }.joinToString("\n")

            // 生成摘要
            val summaryContent = generateSummary(messagesToSummarize)

            if (summaryContent != null) {
                // 保存摘要
                val summaryEntity = ai.openclaw.android.data.model.SummaryEntity(
                    sessionId = sessionId,
                    content = summaryContent,
                    messageRangeStart = messagesToCompress.firstOrNull()?.timestamp ?: 0,
                    messageRangeEnd = messagesToCompress.lastOrNull()?.timestamp ?: 0,
                    compressedAt = System.currentTimeMillis()
                )
                summaryDao.insertSummary(summaryEntity)

                // 删除已压缩的消息
                val messageIdsToDelete = messagesToCompress.map { it.id }
                messageDao.deleteMessagesByIds(sessionId, messageIdsToDelete)

                // 重新计算会话的token计数
                val remainingMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
                val newTokenCount = remainingMessages.sumOf { it.tokenCount.toLong() }.toInt()
                
                currentSession = currentSession?.copy(tokenCount = newTokenCount)
                sessionDao.updateSession(currentSession!!)
            }
        }
    }

    /**
     * 生成摘要内容
     */
    private suspend fun generateSummary(content: String): String? {
        // 如果本地LLM可用，使用它来生成摘要
        return if (llmClient.isModelLoaded()) {
            try {
                val prompt = "请总结以下对话内容，保持重要信息：\n\n$content"
                llmClient.chat(listOf(Message(role = "user", content = prompt)))
                    .getOrNull()?.content
            } catch (e: Exception) {
                null
            }
        } else {
            // 如果本地LLM不可用，返回原始内容作为占位符
            "对话摘要: $content".take(500) // 限制长度
        }
    }

    /**
     * 创建命名会话
     */
    suspend fun createNamedSession(name: String): SessionEntity {
        val session = createNewSession(name)
        currentSession = session
        return session
    }

    /**
     * 创建新会话的辅助函数
     */
    private suspend fun createNewSession(name: String? = null): SessionEntity {
        val session = SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )
        sessionDao.insertSession(session)
        return session
    }

    /**
     * 获取当前会话 ID
     */
    fun getCurrentSessionId(): String? {
        return currentSession?.sessionId
    }

    /**
     * 切换到指定会话
     */
    suspend fun switchToSession(sessionId: String): Result<SessionEntity> {
        return try {
            val session = sessionDao.getSessionById(sessionId)
            if (session != null) {
                currentSession = session
                Result.success(session)
            } else {
                Result.failure(Exception("Session not found: $sessionId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 结束当前会话
     */
    suspend fun endCurrentSession() {
        currentSession = null
    }
}