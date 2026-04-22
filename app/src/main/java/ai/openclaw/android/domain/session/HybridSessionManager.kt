package ai.openclaw.android.domain.session

import ai.openclaw.android.data.local.MessageDao
import ai.openclaw.android.data.local.SessionDao
import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.domain.model.SessionConfig
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.Message
import ai.openclaw.android.util.CompressionPrompts
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 混合会话管理器 - 整合所有会话相关组件
 */
class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val llmClient: LocalLLMClient?,
    private val tokenCounter: TokenCounter,
    private val memoryManager: MemoryManager? = null
) {
    private var currentSession: SessionEntity? = null
    private val config = SessionConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var extractionJob: Job? = null

    /** 摘要 LRU 缓存 — 避免每次查 DB */
    private val summaryCache = object : LinkedHashMap<String, SummaryEntity>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SummaryEntity>): Boolean = size > SUMMARY_CACHE_SIZE
    }

    companion object {
        private const val TAG = "HybridSessionManager"
        private const val EXTRACTION_DELAY_MS = 30_000L
        private const val SUMMARY_CACHE_SIZE = 10
        private val MANUAL_MEMORY_TRIGGERS = listOf("记住这个", "记住：", "记住:", "请记住")
    }

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

            // 记忆系统触发
            handleMemoryTriggers(role, content)

            Result.success(message.copy(id = messageId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 处理记忆触发：手动标记 + 自动提取
     */
    private fun handleMemoryTriggers(role: MessageRole, content: String) {
        val mm = memoryManager ?: return

        // 手动标记检测
        if (role == MessageRole.USER && MANUAL_MEMORY_TRIGGERS.any { content.contains(it) }) {
            scope.launch {
                try {
                    mm.addManual(content)
                    Log.d(TAG, "Manual memory triggered")
                } catch (e: Exception) {
                    Log.w(TAG, "Manual memory failed", e)
                }
            }
        }

        // 延迟自动提取（仅用户消息触发）
        if (role == MessageRole.USER) {
            triggerDelayedExtraction()
        }
    }

    private fun triggerDelayedExtraction() {
        extractionJob?.cancel()
        extractionJob = scope.launch {
            delay(EXTRACTION_DELAY_MS)
            try {
                val messages = getConversationContext()
                if (messages.isNotEmpty()) {
                    memoryManager?.extractAndStore(messages)
                    Log.d(TAG, "Auto memory extraction completed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto memory extraction failed", e)
            }
        }
    }

    /**
     * 获取对话上下文（摘要 + 最近消息 + 记忆注入）
     */
    suspend fun getConversationContext(): List<MessageEntity> {
        val sessionId = currentSession?.sessionId ?: return emptyList()

        // 先从缓存获取摘要，缓存未命中再查 DB
        val latestSummary = getCachedSummary(sessionId)

        // 获取最近的消息（根据配置决定保留的数量）
        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val recentMessages = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.takeLast(config.preserveRecentMessages)
        } else {
            allMessages
        }

        // 构建上下文消息列表
        val contextMessages = if (latestSummary != null) {
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
            allMessages
        }

        // 注入重要记忆
        val memories = memoryManager?.getImportantMemories(5).orEmpty()
        if (memories.isNotEmpty()) {
            val memoryText = memories.joinToString("\n") {
                "[${it.memoryType.name}] ${it.content}"
            }
            val memoryMessage = MessageEntity(
                id = 0,
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "用户的重要记忆：\n$memoryText",
                timestamp = 0L,
                tokenCount = tokenCounter.estimate(memoryText)
            )
            return listOf(memoryMessage) + contextMessages
        }

        return contextMessages
    }

    /**
     * 获取摘要（缓存优先，DB 兜底）
     */
    private suspend fun getCachedSummary(sessionId: String): SummaryEntity? {
        return summaryCache[sessionId] ?: run {
            summaryDao.getSummaryBySessionId(sessionId)?.also {
                summaryCache[sessionId] = it
            }
        }
    }

    /**
     * 清空某会话的摘要缓存
     */
    fun invalidateSummaryCache(sessionId: String) {
        summaryCache.remove(sessionId)
    }

    /**
     * 获取记忆上下文文本（供 AgentSession 注入 system prompt）
     */
    suspend fun getMemoryContext(): String? {
        val memories = memoryManager?.getImportantMemories(5).orEmpty()
        if (memories.isEmpty()) return null
        return memories.joinToString("\n") {
            "[${it.memoryType.name}] ${it.content}"
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
            // 增量压缩：如果有旧摘要，合并到新摘要中
            val existingSummary = getCachedSummary(sessionId)
            val messagesToSummarize = messagesToCompress.map {
                "${it.role.name}: ${it.content}"
            }.joinToString("\n")

            // 生成摘要（传入旧摘要用于增量合并）
            val summaryContent = generateSummary(messagesToSummarize, existingSummary)

            if (summaryContent != null) {
                // 保存摘要
                val summaryEntity = SummaryEntity(
                    sessionId = sessionId,
                    content = summaryContent,
                    messageRangeStart = messagesToCompress.firstOrNull()?.timestamp ?: 0,
                    messageRangeEnd = messagesToCompress.lastOrNull()?.timestamp ?: 0,
                    compressedAt = System.currentTimeMillis()
                )
                summaryDao.insertSummary(summaryEntity)
                // 更新缓存
                summaryCache[sessionId] = summaryEntity

                // 删除已压缩的消息
                val messageIdsToDelete = messagesToCompress.map { it.id }
                messageDao.deleteMessagesByIds(sessionId, messageIdsToDelete)

                // 重新计算会话的token计数
                val remainingMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
                val newTokenCount = remainingMessages.sumOf { it.tokenCount.toLong() }.toInt()

                currentSession = currentSession?.copy(tokenCount = newTokenCount)
                sessionDao.updateSession(currentSession!!)

                Log.d(TAG, "Session compressed: sessionId=$sessionId, summaryLength=${summaryContent.length}")

                // 跨会话记忆关联：从摘要中提取重要事实存入记忆系统
                extractMemoriesFromSummary(summaryContent, sessionId)
            }
        }
    }

    /**
     * 从压缩摘要中提取重要信息，存入记忆系统（跨会话记忆关联）
     */
    private fun extractMemoriesFromSummary(summary: String, sessionId: String) {
        val mm = memoryManager ?: return

        scope.launch {
            try {
                // 基于规则的关键行提取
                val lines = summary.split("\n")
                var extractedCount = 0

                for (line in lines) {
                    val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
                    if (trimmed.isBlank() || trimmed.startsWith("##")) continue

                    // 识别用户偏好并存储
                    val inferredType = when {
                        trimmed.contains("喜欢") || trimmed.contains("偏好") || trimmed.contains("不要") || trimmed.contains("不用") -> MemoryType.PREFERENCE
                        trimmed.contains("决定") || trimmed.contains("选择") || trimmed.contains("采用") -> MemoryType.DECISION
                        trimmed.contains("待办") || trimmed.contains("要完成") || trimmed.contains("记得") -> MemoryType.TASK
                        else -> null
                    }

                    if (inferredType != null) {
                        mm.addManual(trimmed, inferredType)
                        extractedCount++
                    }
                }

                if (extractedCount > 0) {
                    Log.d(TAG, "Extracted $extractedCount cross-session memories from summary")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Memory extraction from summary failed", e)
            }
        }
    }

    /**
     * 生成摘要内容（支持增量压缩）
     * @param messagesText 待压缩的对话消息
     * @param previousSummary 之前的摘要（如果有，将增量合并）
     */
    private suspend fun generateSummary(
        messagesText: String,
        previousSummary: SummaryEntity? = null
    ): String? {
        return if (llmClient != null && llmClient.isModelLoaded()) {
            try {
                val prompt = if (previousSummary != null) {
                    // 增量压缩：合并旧摘要和新消息
                    """你正在压缩一段长对话。以下是之前的摘要（包含早期对话要点）：

${previousSummary.content}

以下是新产生的对话内容：

$messagesText

请将旧摘要和新对话合并为一份完整的摘要：
1. 保留旧摘要中仍然重要的信息
2. 添加新对话中的关键决策、用户偏好和待办事项
3. 删除过时的内容
4. 控制在 300 字以内
5. 使用要点列表格式

合并后的摘要：""".trimIndent()
                } else {
                    // 首次压缩：直接生成摘要
                    """请总结以下对话内容，保持重要信息：

$messagesText

要求：
1. 保留关键决策和结论
2. 保留用户偏好和重要信息
3. 保留未完成的任务或待办事项
4. 使用要点列表格式
5. 控制在 200 字以内

摘要：""".trimIndent()
                }
                llmClient.chat(listOf(Message(role = "user", content = prompt)))
                    .getOrNull()?.content
            } catch (e: Exception) {
                Log.w(TAG, "LLM summary generation failed", e)
                null
            }
        } else {
            // LLM 不可用，使用简单摘要
            if (previousSummary != null) {
                "${previousSummary.content}\n[新增对话: ${messagesText.take(200)}...]"
            } else {
                "对话摘要: ${messagesText.take(300)}"
            }
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
        extractionJob?.cancel()
        currentSession = null
    }
}
