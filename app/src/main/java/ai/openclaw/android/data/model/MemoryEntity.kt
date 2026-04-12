package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val memoryType: MemoryType,
    val priority: Int,
    val source: String?,
    val tags: List<String>,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
    val version: Int = 1
)
