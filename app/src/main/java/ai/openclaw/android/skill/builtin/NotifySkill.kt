package ai.openclaw.android.skill.builtin

import ai.openclaw.android.notification.NotificationCategory
import ai.openclaw.android.notification.SmartNotification
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.skill.*
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * NotifySkill — 通知列表查询、通知清除、通知回复。
 *
 * 复用现有的 SmartNotificationListener 的通知缓存。
 * 需要通知监听权限（BIND_NOTIFICATION_LISTENER_SERVICE），
 * 无权限时返回友好的错误提示。
 *
 * 通知 key 格式：{userId}|{packageName}|{id}|{tag}
 * （即 StatusBarNotification.key）
 */
class NotifySkill(
    private val context: Context
) : Skill {
    override val id = "notify"
    override val name = "通知管理"
    override val description = "查询、清除和回复系统通知"
    override val version = "1.0.0"

    override val instructions = """
# Notification Skill

查询、清除和回复系统通知。

## 权限
需要授予通知监听权限（设置 → 应用 → OpenClaw → 通知使用权）。

## 可用工具
- `list` — 获取当前活跃的通知列表
- `dismiss` — 清除指定通知
- `reply` — 回复通知（通过 RemoteInput，如短信/微信回复）
""".trimIndent()

    override val tools: List<SkillTool> = listOf(
        ListTool(),
        DismissTool(),
        ReplyTool()
    )

    // ==================== notify_list ====================

    private inner class ListTool : SkillTool {
        override val name = "list"
        override val description =
            "获取当前活跃的通知列表。需要通知监听权限。"
        override val parameters = mapOf(
            "limit" to SkillParam(
                type = "number",
                description = "最大返回数量，默认 20",
                required = false,
                default = 20
            ),
            "package_name" to SkillParam(
                type = "string",
                description = "按包名过滤",
                required = false
            ),
            "min_priority" to SkillParam(
                type = "string",
                description = "最低优先级: low, default, high, max，默认 low",
                required = false,
                default = "low"
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val limit = (params["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 20
            val packageNameFilter = params["package_name"] as? String
            val minPriorityStr = (params["min_priority"] as? String)?.lowercase() ?: "low"
            val minPriority = parsePriority(minPriorityStr)

            // Check if notification listener is available
            if (!isNotificationListenerAvailable()) {
                return@withContext SkillResult(
                    false,
                    "",
                    "通知监听权限未授予。请在 设置 → 应用 → OpenClaw → 通知使用权 中启用。"
                )
            }

            try {
                val notifications = SmartNotificationListener.getActiveNotificationsList()

                val filtered = notifications
                    .filter { pkgName -> packageNameFilter == null || pkgName.packageName == packageNameFilter }
                    .filter { n -> getNotificationPriority(n) >= minPriority }
                    .take(limit)

                if (filtered.isEmpty()) {
                    return@withContext SkillResult(true, "当前没有活跃通知")
                }

                val output = buildListResult(filtered)
                SkillResult(true, output)
            } catch (e: SecurityException) {
                SkillResult(false, "", "通知监听权限异常: ${e.message}")
            } catch (e: Exception) {
                Log.e("NotifySkill", "list failed: ${e.message}", e)
                SkillResult(false, "", "获取通知列表失败: ${e.message}")
            }
        }
    }

    // ==================== notify_dismiss ====================

    private inner class DismissTool : SkillTool {
        override val name = "dismiss"
        override val description =
            "清除指定的通知。key 参数从 notify_list 获取。"
        override val parameters = mapOf(
            "key" to SkillParam(
                type = "string",
                description = "通知的 key（从 notify_list 获取）",
                required = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val key = params["key"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 key 参数")

            if (!isNotificationListenerAvailable()) {
                return@withContext SkillResult(
                    false,
                    "",
                    "通知监听权限未授予。请在 设置 → 应用 → OpenClaw → 通知使用权 中启用。"
                )
            }

            val listener = SmartNotificationListener.getInstanceForReply()
            if (listener == null) {
                return@withContext SkillResult(false, "", "通知监听服务未运行")
            }

            try {
                listener.cancelNotification(key)
                // Also update the in-memory cache
                SmartNotificationListener.deleteNotification(key)

                SkillResult(true, "已清除通知: $key")
            } catch (e: SecurityException) {
                SkillResult(false, "", "清除通知失败（权限异常）: ${e.message}")
            } catch (e: Exception) {
                Log.e("NotifySkill", "dismiss failed: ${e.message}", e)
                SkillResult(false, "", "清除通知失败: ${e.message}")
            }
        }
    }

    // ==================== notify_reply ====================

    private inner class ReplyTool : SkillTool {
        override val name = "reply"
        override val description =
            "回复通知（通过 RemoteInput）。适用于支持快速回复的通知（如短信、微信等）。"
        override val parameters = mapOf(
            "key" to SkillParam(
                type = "string",
                description = "通知的 key（从 notify_list 获取）",
                required = true
            ),
            "text" to SkillParam(
                type = "string",
                description = "回复内容",
                required = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
            val key = params["key"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 key 参数")
            val text = params["text"] as? String
                ?: return@withContext SkillResult(false, "", "缺少 text 参数")

            if (!isNotificationListenerAvailable()) {
                return@withContext SkillResult(
                    false,
                    "",
                    "通知监听权限未授予。请在 设置 → 应用 → OpenClaw → 通知使用权 中启用。"
                )
            }

            val listener = SmartNotificationListener.getInstanceForReply()
            if (listener == null) {
                return@withContext SkillResult(false, "", "通知监听服务未运行")
            }

            try {
                // Get all active notifications from the system
                val activeSbns = listener.activeNotifications
                val targetSbn = activeSbns?.find { it.key == key }
                    ?: return@withContext SkillResult(false, "", "未找到指定通知（可能已被清除）")

                val notification = targetSbn.notification
                val actions = notification.actions ?: return@withContext SkillResult(false, "", "该通知没有可操作按钮")

                // Find the first action with a RemoteInput and send reply
                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        val remoteInput = remoteInputs[0]
                        val replyIntent = action.actionIntent

                        if (replyIntent != null) {
                            try {
                                val results = Bundle().apply {
                                    putCharSequence(remoteInput.resultKey, text)
                                }

                                // Create an Intent with RemoteInput results, send via PendingIntent
                                val fillInIntent = android.content.Intent().apply {
                                    android.app.RemoteInput.addResultsToIntent(
                                        arrayOf(remoteInput),
                                        this,
                                        results
                                    )
                                }
                                replyIntent.send(context, 0, fillInIntent)

                                return@withContext SkillResult(true, "已回复通知: $key")
                            } catch (e: PendingIntent.CanceledException) {
                                Log.w("NotifySkill", "Reply PendingIntent cancelled: ${e.message}")
                            }
                        }
                    }
                }

                return@withContext SkillResult(false, "", "该通知不支持快速回复（无 RemoteInput action）")
            } catch (e: SecurityException) {
                SkillResult(false, "", "回复通知失败（权限异常）: ${e.message}")
            } catch (e: Exception) {
                Log.e("NotifySkill", "reply failed: ${e.message}", e)
                SkillResult(false, "", "回复通知失败: ${e.message}")
            }
        }
    }

    // ==================== Helper methods ====================

    /**
     * Check if the notification listener service is available and connected.
     */
    private fun isNotificationListenerAvailable(): Boolean {
        return try {
            // Check if notification listener permission is granted
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            enabled?.contains(context.packageName) == true
        } catch (e: Exception) {
            Log.w("NotifySkill", "Failed to check notification listener status: ${e.message}")
            false
        }
    }

    /**
     * Map priority string to integer.
     */
    private fun parsePriority(str: String): Int {
        return when (str) {
            "max" -> Notification.PRIORITY_MAX
            "high" -> Notification.PRIORITY_HIGH
            "default" -> Notification.PRIORITY_DEFAULT
            "low" -> Notification.PRIORITY_LOW
            else -> Notification.PRIORITY_LOW
        }
    }

    /**
     * Get the priority from a SmartNotification.
     * Since SmartNotification stores extras as Map<String, String>,
     * we map from category to a priority level.
     */
    private fun getNotificationPriority(notification: SmartNotification): Int {
        return when (notification.category) {
            NotificationCategory.URGENT -> Notification.PRIORITY_MAX
            NotificationCategory.IMPORTANT -> Notification.PRIORITY_HIGH
            NotificationCategory.NORMAL -> Notification.PRIORITY_DEFAULT
            NotificationCategory.NOISE -> Notification.PRIORITY_LOW
            NotificationCategory.PENDING -> Notification.PRIORITY_DEFAULT
        }
    }

    /**
     * Format the notification list as a readable result.
     */
    private fun buildListResult(notifications: List<SmartNotification>): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return buildString {
            append("活跃通知 (共 ${notifications.size} 条):\n")
            append("---\n")

            notifications.forEachIndexed { index, n ->
                append("[${index + 1}] ${n.title}\n")
                append("    Key: ${n.id}\n")
                append("    应用: ${n.packageName}\n")
                append("    内容: ${n.text.take(100)}\n")
                append("    时间: ${formatter.format(n.timestamp)}\n")
                append("    类别: ${n.category.name}\n")

                // Try to extract actions from extras
                val actions = extractActions(n.extras)
                if (actions.isNotEmpty()) {
                    append("    操作: ${actions.joinToString(", ")}\n")
                }

                // Check if this notification supports reply
                if (supportsReply(n.extras)) {
                    append("    💬 支持回复\n")
                }

                append("\n")
            }
        }
    }

    /**
     * Extract action labels from notification extras.
     */
    private fun extractActions(extras: Map<String, String>): List<String> {
        // Actions info may be stored in various extras; this is a best-effort extraction
        return emptyList()
    }

    /**
     * Check if a notification supports reply (has RemoteInput action).
     */
    private fun supportsReply(extras: Map<String, String>): Boolean {
        return extras.containsKey("android.remoteInput") ||
                extras.containsKey("android.carrier_id")
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        val available = isNotificationListenerAvailable()
        Log.i("NotifySkill", "Initialized. Notification listener: ${if (available) "available" else "NOT available"}")
    }

    override fun cleanup() {}
}
