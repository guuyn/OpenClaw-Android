package ai.openclaw.android.feishu

import kotlinx.serialization.Serializable

/**
 * 飞书事件模型
 */
@Serializable
data class FeishuEvent(
    val type: String,
    val header: EventHeader?,
    val event: EventBody?
)

/**
 * 事件头信息
 */
@Serializable
data class EventHeader(
    val event_id: String,
    val event_type: String,
    val create_time: String,
    val token: String
)

/**
 * 事件主体
 */
@Serializable
data class EventBody(
    val message: FeishuMessage?
)

/**
 * 飞书消息模型
 */
@Serializable
data class FeishuMessage(
    val message_id: String,
    val chat_id: String,
    val chat_type: String,  // "p2p" | "group"
    val content: String,
    val sender: SenderInfo
)

/**
 * 发送者信息
 */
@Serializable
data class SenderInfo(
    val sender_id: String,
    val sender_type: String,
    val tenant_key: String
)