package ai.openclaw.android.domain.session

import ai.openclaw.android.data.local.SummaryDao
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.Message
import ai.openclaw.android.util.CompressionPrompts
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class SessionCompressor(
    private val llmClient: LocalLLMClient,
    private val summaryDao: SummaryDao,
    private val isLlmReady: (() -> Boolean)? = null
) {
    suspend fun compress(
        session: SessionEntity,
        messages: List<MessageEntity>,
        preserveRecent: Int = 10
    ): Result<SummaryEntity?> = runCatching {
        // 消息太少，不压缩
        if (messages.size <= preserveRecent) {
            return@runCatching null
        }

        // 分离消息
        val toCompress = messages.dropLast(preserveRecent)

        // 如果没有要压缩的消息，直接返回
        if (toCompress.isEmpty()) {
            return@runCatching null
        }

        // LLM 不可用，使用简单截断
        val modelReady = isLlmReady?.invoke() ?: llmClient.isModelLoaded()
        if (!modelReady) {
            return@runCatching createSimpleSummary(session, messages, toCompress, preserveRecent)
        }

        // 调用 LLM 压缩（带超时）
        val summary = withTimeoutOrNull(30_000) {
            val response = llmClient.chat(
                listOf(
                    Message(role = "system", content = CompressionPrompts.SUMMARIZE_SYSTEM),
                    Message(role = "user", content = CompressionPrompts.buildPrompt(toCompress))
                )
            ).getOrNull()
            response?.content ?: createSimpleSummaryText(toCompress)
        } ?: createSimpleSummaryText(toCompress)

        SummaryEntity(
            sessionId = session.sessionId,
            content = summary,
            messageRangeStart = toCompress.first().id,
            messageRangeEnd = toCompress.last().id,
            compressedAt = System.currentTimeMillis()
        )
    }

    private fun createSimpleSummary(
        session: SessionEntity,
        allMessages: List<MessageEntity>,
        toCompress: List<MessageEntity>,
        preserveRecent: Int
    ): SummaryEntity {
        val summaryText = createSimpleSummaryText(toCompress)
        return SummaryEntity(
            sessionId = session.sessionId,
            content = summaryText,
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