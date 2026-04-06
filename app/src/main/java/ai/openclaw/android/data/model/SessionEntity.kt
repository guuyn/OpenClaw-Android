package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val name: String?,              // null = 默认会话
    val createdAt: Long,
    val lastActiveAt: Long,
    val tokenCount: Int,
    val status: SessionStatus       // ACTIVE, COMPRESSED, ARCHIVED
)