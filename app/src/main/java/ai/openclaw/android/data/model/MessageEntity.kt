package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val tokenCount: Int
)