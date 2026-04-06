package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.entity.MemoryEntity

data class MemorySearchResult(
    val memory: MemoryEntity,
    val similarity: Float
)