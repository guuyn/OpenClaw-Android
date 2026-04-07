package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MessageEntity

interface MemoryExtractorInterface {
    suspend fun extractFromConversation(messages: List<MessageEntity>): Result<List<MemoryEntity>>
    suspend fun extractFromUserInput(content: String, type: MemoryType? = null): Result<MemoryEntity>
}
