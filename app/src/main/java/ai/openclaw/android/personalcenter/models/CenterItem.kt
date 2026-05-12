package ai.openclaw.android.personalcenter.models

import android.app.PendingIntent
import ai.openclaw.android.personalcenter.sources.ItemSource

/**
 * 个人中心统一数据模型
 * 通知、日程、短信等所有信息源统一为此结构，按 importance 降序排列
 */
data class CenterItem(
    val id: String,                           // 唯一标识
    val source: ItemSource,                   // 来源类型
    val sourceApp: String,                    // 来源应用名（"微信"、"系统日历"、"短信"）
    val importance: Float,                    // 重要程度 0.0~1.0
    val title: String,                        // 标题/摘要
    val body: String,                         // 正文内容
    val timestamp: Long,                      // 事件发生时间
    val createdAt: Long = System.currentTimeMillis(), // 记录创建时间
    val isRead: Boolean = false,              // 是否已读
    val openIntent: PendingIntent? = null,    // 点击跳转
    val dedupKey: String = "",                // 去重键
    val mergedCount: Int = 1,                 // 合并的关联条目数
    val priorityLevel: PriorityLevel = PriorityLevel.UNKNOWN, // urgent / today / reference
    val actionType: ActionType = ActionType.NONE,             // reply / act / info / none
    val expiryTimestamp: Long = Long.MAX_VALUE,              // 过期时间戳，超时后退出优先区
)

enum class PriorityLevel { URGENT, TODAY, REFERENCE, UNKNOWN }
enum class ActionType { REPLY, ACT, INFO, NONE }
