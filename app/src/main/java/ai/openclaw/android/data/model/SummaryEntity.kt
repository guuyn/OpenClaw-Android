package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val sessionId: String,
    val content: String,
    val messageRangeStart: Long,
    val messageRangeEnd: Long,
    val compressedAt: Long
)