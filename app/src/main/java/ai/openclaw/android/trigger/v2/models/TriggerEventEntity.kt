package ai.openclaw.android.trigger.v2.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * TriggerEventEntity — v2 触发事件日志
 *
 * 比 v1 的 TriggerLog 增加了 context 和 decision 字段，
 * 支持 AI 决策追踪和用户反馈
 */
@Entity(
    tableName = "trigger_events_v2",
    indices = [
        Index("triggerId"),
        Index("timestamp"),
        Index("decision"),
        Index("userFeedback")
    ]
)
data class TriggerEventEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val triggerId: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 触发时的上下文信息（JSON） */
    val context: String = "{}",
    /** AI 决策结果 */
    val decision: String = "unknown",       // triggered / filtered / error
    /** 用户反馈 */
    val userFeedback: String = "none",       // none / correct_trigger / correct_skip
    /** 执行结果 */
    val success: Boolean = true,
    val result: String? = null,
    val error: String? = null
)
