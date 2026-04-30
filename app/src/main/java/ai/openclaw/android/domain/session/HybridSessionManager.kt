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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 混合会话管理器 - 整合所有会话相关组件
 *
 * 架构变更 (2026-04-30)：
 * - 压缩逻辑委托给 SessionCompressor（统一压缩引擎）
 * - 删除 summaryCache，改为 DB 直查（Room 单条查询 < 1ms）
 * - 增加语义重要性评估，智能决定压缩时机
 */
class HybridSessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val sessionCompressor: SessionCompressor,
    private val tokenCounter: TokenCounter,
    private val memoryManager: MemoryManager? = null
) {
    private var currentSession: SessionEntity? = null
    private val config = SessionConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var extractionJob: Job? = null

    companion object {
        private const val TAG = "HybridSessionManager"
        private const val EXTRACTION_DELAY_MS = 30_000L
        private val MANUAL_MEMORY_TRIGGERS = listOf("记住这个", "记住：", "记住:", "请记住")
    }

    /**
     * 初始化/恢复会话
     */
    suspend fun initialize(): SessionEntity {
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
            if (currentSession == null) {
                initialize()
            }

            val tokenCount = tokenCounter.estimate(content)

            val message = MessageEntity(
                id = 0,
                sessionId = currentSession!!.sessionId,
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                tokenCount = tokenCount
            )

            val messageId = messageDao.insertMessage(message)

            currentSession = currentSession?.copy(
                lastActiveAt = System.currentTimeMillis(),
                tokenCount = (currentSession?.tokenCount ?: 0) + tokenCount
            )
            sessionDao.updateSession(currentSession!!)

            compressIfNeeded()

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

        val latestSummary = summaryDao.getSummaryBySessionId(sessionId)

        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val recentMessages = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.takeLast(config.preserveRecentMessages)
        } else {
            allMessages
        }

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
     *
     * 智能触发策略（2026-04-30 新增）：
     * 1. 强制模式 → 立即压缩
     * 2. Token 严重超标（> 2x maxTokens）→ 强制压缩，不管重要性
     * 3. Token 超标 + 消息数足够 + 语义重要性低 → 压缩
     * 4. Token 超标但语义重要性高 → 延迟压缩
     * 5. 否则 → 不压缩（保底：只看 token 阈值）
     */
    suspend fun compressIfNeeded(force: Boolean = false) {
        val session = currentSession ?: return

        if (force) {
            performCompression()
            return
        }

        if (!config.autoCompressDefault) return

        val allMessages = messageDao.getMessagesBySessionId(session.sessionId).firstOrNull() ?: emptyList()
        val messagesToCompress = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.take(allMessages.size - config.preserveRecentMessages)
        } else {
            emptyList()
        }

        val tokenOverflow = session.tokenCount > config.maxTokens
        val criticalTokenOverflow = session.tokenCount > config.maxTokens * 2
        val enoughMessages = messagesToCompress.size >= config.minMessagesForCompress

        // 保底：只看 token 阈值（原有行为）
        if (force || (tokenOverflow && !enoughMessages)) {
            performCompression()
            return
        }

        if (!tokenOverflow) return
        if (!enoughMessages) return

        // 严重超标 → 强制压缩
        if (criticalTokenOverflow) {
            Log.d(TAG, "Critical token overflow (${session.tokenCount} > ${config.maxTokens * 2}), force compressing")
            performCompression()
            return
        }

        // 语义重要性评估
        val importanceScore = assessCompressionUrgency(messagesToCompress)
        val isCompressible = importanceScore < config.importanceThreshold

        if (isCompressible) {
            performCompression()
        } else {
            Log.d(TAG, "Compression deferred: high importance messages detected (score=$importanceScore)")
        }
    }

    /**
     * 评估待压缩消息的语义重要性
     * 返回 0.0 ~ 1.0，越高表示越重要（越不该压缩）
     *
     * 四维加权评估：
     * - 用户意图关键词 (40%)
     * - 消息长度 (20%)
     * - 工具调用 (20%)
     * - 讨论深度 (20%)
     */
    private fun assessCompressionUrgency(messages: List<MessageEntity>): Float {
        if (messages.isEmpty()) return 0f

        var score = 0f

        // 规则1: 用户消息中的重要性关键词 (权重 40%)
        val importantUserKeywords = listOf(
            "记住", "重要", "注意", "关键", "必须", "一定",
            "决定", "选择", "采用", "偏好", "喜欢",
            "请", "帮我", "需要", "要求",
            "TODO", "待办", "任务", "bug", "修复",
            "密码", "密钥", "token", "api"
        )
        val userMessages = messages.filter { it.role == MessageRole.USER }
        if (userMessages.isNotEmpty()) {
            val importantUserCount = userMessages.count { msg ->
                importantUserKeywords.any { keyword ->
                    msg.content.contains(keyword, ignoreCase = true)
                }
            }
            score += (importantUserCount.toFloat() / userMessages.size) * 0.4f
        }

        // 规则2: 消息长度 (权重 20%)
        val avgLength = messages.map { it.content.length }.average()
        if (avgLength > 200) {
            score += 0.2f
        } else if (avgLength > 100) {
            score += 0.1f
        }

        // 规则3: 包含工具调用 (权重 20%)
        val hasToolCalls = messages.any {
            it.content.contains("[TOOL]") || it.content.contains("tool_call")
        }
        if (hasToolCalls) {
            score += 0.2f
        }

        // 规则4: 对话轮次多 = 讨论深度 (权重 20%)
        val turnCount = messages.count { it.role == MessageRole.USER }
        if (turnCount > 5) {
            score += 0.2f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 执行压缩操作 — 委托给 SessionCompressor
     */
    private suspend fun performCompression() {
        val session = currentSession ?: return
        val sessionId = session.sessionId

        val allMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        if (allMessages.isEmpty()) return

        val messagesToCompress = if (allMessages.size > config.preserveRecentMessages) {
            allMessages.take(allMessages.size - config.preserveRecentMessages)
        } else {
            emptyList()
        }
        if (messagesToCompress.isEmpty()) return

        val existingSummary = summaryDao.getSummaryBySessionId(sessionId)

        val result = if (existingSummary != null) {
            // 增量压缩：合并旧摘要
            Log.d(TAG, "Incremental compression: sessionId=$sessionId, existingSummaryLength=${existingSummary.content.length}")
            sessionCompressor.compressIncremental(
                session = session,
                messages = allMessages,
                existingSummary = existingSummary
            )
        } else {
            // 首次压缩
            Log.d(TAG, "First-time compression: sessionId=$sessionId, messagesToCompress=${messagesToCompress.size}")
            sessionCompressor.compressFirstTime(
                session = session,
                messages = allMessages
            )
        }

        val summaryEntity = result.getOrNull() ?: run {
            Log.w(TAG, "Compression returned null for sessionId=$sessionId")
            return
        }

        // 保存摘要到 DB（UPSERT）
        summaryDao.insertSummary(summaryEntity)

        // 删除已压缩的消息
        val messageIdsToDelete = messagesToCompress.map { it.id }
        messageDao.deleteMessagesByIds(sessionId, messageIdsToDelete)

        // 重新计算会话 token 计数
        val remainingMessages = messageDao.getMessagesBySessionId(sessionId).firstOrNull() ?: emptyList()
        val newTokenCount = remainingMessages.sumOf { it.tokenCount.toLong() }.toInt()

        currentSession = currentSession?.copy(tokenCount = newTokenCount)
        sessionDao.updateSession(currentSession!!)

        Log.d(TAG, "Session compressed: sessionId=$sessionId, summaryLength=${summaryEntity.content.length}")

        // 跨会话记忆关联
        extractMemoriesFromSummary(summaryEntity.content, sessionId)
    }

    /**
     * 从压缩摘要中提取重要信息，存入记忆系统（跨会话记忆关联）
     */
    private fun extractMemoriesFromSummary(summary: String, sessionId: String) {
        val mm = memoryManager ?: return

        scope.launch {
            try {
                val lines = summary.split("\n")
                var extractedCount = 0

                for (line in lines) {
                    val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
                    if (trimmed.isBlank() || trimmed.startsWith("##")) continue

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
