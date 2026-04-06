package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MemoryEntity

data class MemorySearchResult(
    val memory: MemoryEntity,
    val similarity: Float
)