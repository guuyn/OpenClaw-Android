package ai.openclaw.android.trigger.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "trigger_logs",
    indices = [Index("ruleId"), Index("executedAt")]
)
data class TriggerLog(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ruleId: String,
    val eventId: String,
    val executedAt: Long = System.currentTimeMillis(),
    val actionType: String,
    val success: Boolean,
    val error: String? = null,
    val result: String? = null
)
