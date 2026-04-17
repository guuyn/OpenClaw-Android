package ai.openclaw.android.trigger.models

import java.util.*

/**
 * 统一事件格式 — 所有触发源（Cron/Notification/Accessibility/System）产生的事件
 */
data class TriggerEvent(
    val id: String = UUID.randomUUID().toString(),
    val source: EventSource,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any?> = emptyMap(),
    val dedupKey: String? = null
)
