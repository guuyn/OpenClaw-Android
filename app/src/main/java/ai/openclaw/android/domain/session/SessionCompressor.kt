package ai.openclaw.android.domain.session

import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.Message
import ai.openclaw.android.util.CompressionPrompts
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class SessionCompressor(
    private val llmClient: LocalLLMClient?,
    private val summaryDao: SummaryDao,
    private val isLlmReady: (() -> Boolean)? = null
) {
    companion object {
        private const val TAG = "SessionCompressor"
        private const val LLM_TIMEOUT_MS = 30_000L
    }

    /**
     * 首次压缩（无旧摘要）
     * 使用结构化 prompt，输出四类摘要格式
     */
    suspend fun compressFirstTime(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        // 消息太少，不压缩
        if (messages.size <= preserveRecent) {
            return@runCatching null
        }

        // 分离要压缩的消息
        val toCompress = messages.dropLast(preserveRecent)
        if (toCompress.isEmpty()) {
            return@runCatching null
        }

        // LLM 不可用，使用简单截断
        val modelReady = llmClient != null && (isLlmReady?.invoke() ?: llmClient.isModelLoaded())
        if (!modelReady) {
            return@runCatching createSimpleSummary(session, toCompress)
        }

        // 调用 LLM 结构化压缩
        val summary = compressWithLlm(
            systemPrompt = CompressionPrompts.STRUCTURED_SUMMARIZE_SYSTEM,
            userPrompt = CompressionPrompts.buildFirstCompressPrompt(toCompress),
            fallbackText = { createSimpleSummaryText(toCompress) }
        )

        SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    /**
     * 增量压缩（合并旧摘要 + 新对话）
     * 替代 HybridSessionManager.generateSummary() 的逻辑
     */
    suspend fun compressIncremental(
        session: SessionEntity,
        messages: List<MessageEntity>,
        existingSummary: SummaryEntity,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        // 分离要压缩的消息
        val toCompress = if (messages.size > preserveRecent) {
            messages.dropLast(preserveRecent)
        } else {
            return@runCatching null
        }
        if (toCompress.isEmpty()) {
            return@runCatching null
        }

        // LLM 不可用，使用简单拼接
        val modelReady = llmClient != null && (isLlmReady?.invoke() ?: llmClient.isModelLoaded())
        if (!modelReady) {
            val fallbackContent = "${existingSummary.content}\n[新增: ${createSimpleSummaryText(toCompress)}]"
            return@runCatching SummaryEntity(
                sessionId = session.sessionId,
                content = fallbackContent,
                messageRangeStart = toCompress.first().id,
                messageRangeEnd = toCompress.last().id,
                compressedAt = System.currentTimeMillis()
            )
        }

        // 调用 LLM 增量合并
        val summary = compressWithLlm(
            systemPrompt = CompressionPrompts.INCREMENTAL_SUMMARIZE_SYSTEM,
            userPrompt = CompressionPrompts.buildIncrementalPrompt(
                previousSummary = existingSummary.content,
                newMessages = toCompress
            ),
            fallbackText = { "${existingSummary.content}\n[新增: ${createSimpleSummaryText(toCompress)}]" }
        )

        SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    /**
     * 兼容旧接口（向后兼容，委托给 compressFirstTime）
     * @deprecated 使用 compressFirstTime 或 compressIncremental
     */
    @Deprecated("Use compressFirstTime or compressIncremental instead")
    suspend fun compress(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> {
        return compressFirstTime(session, messages, preserveRecent)
    }

    /**
     * 通用 LLM 压缩方法（消除重复）
     */
    private suspend fun compressWithLlm(
        systemPrompt: String,
        userPrompt: String,
        fallbackText: () -> String
    ): String {
        // Caller guarantees llmClient is non-null before calling this method
        val client = llmClient!!
        return withTimeoutOrNull(LLM_TIMEOUT_MS) {
            val response = client.chat(
                listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                )
            ).getOrNull()
            val content = response?.content
            if (content.isNullOrBlank()) {
                Log.w(TAG, "LLM returned empty content, using fallback")
                fallbackText()
            } else {
                content
            }
        } ?: run {
            Log.w(TAG, "LLM compression timed out, using fallback")
            fallbackText()
        }
    }

    private fun createSimpleSummary(
        session: SessionEntity,
        toCompress: List<MessageEntity>
    ): SummaryEntity {
        return SummaryEntity(
            sessionId = session.sessionId,
            content = createSimpleSummaryText(toCompress),
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    private fun createSimpleSummaryText(messages: List<MessageEntity>): String {
        return messages.take(3).joinToString("; ") {
            "${it.role.name.first()}: ${it.content.take(min(50, it.content.length))}..."
        }.let { "早期对话摘要: $it" }
    }
}
