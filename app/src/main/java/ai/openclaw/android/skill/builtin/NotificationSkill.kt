package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.delay
import androidx.core.app.NotificationCompat
import ai.openclaw.android.notification.SmartNotification
import ai.openclaw.android.notification.SmartNotificationListener
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

class NotificationSkill(private val context: Context) : Skill {

    override val id = "notification"
    override val name = "通知管理"
    override val description = "获取、发送、删除手机通知"
    override val version = "1.0.0"

    override val instructions = """
# Notification Skill

管理手机通知，支持获取通知列表、发送本地通知、删除通知。

## 用法
- 获取通知：list_notifications
- 发送通知：send_notification
- 删除通知：delete_notification
- 清空通知：clear_notifications
- 标记已读：mark_notification_read
"""

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    override val tools: List<SkillTool> = listOf(
        ListNotificationsTool(),
        SendNotificationTool(),
        DeleteNotificationTool(),
        ClearNotificationsTool(),
        MarkNotificationReadTool()
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "openclaw_skill",
            "OpenClaw 通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "OpenClaw Agent 发送的通知"
            enableLights(true)
            lightColor = Color.BLUE
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    inner class ListNotificationsTool : SkillTool {
        override val name = "list_notifications"
        override val description = "获取当前通知列表，支持按包名过滤"
        override val parameters = mapOf(
            "packageName" to SkillParam("string", "按包名过滤（可选）", false),
            "limit" to SkillParam("number", "返回数量限制", false, "20"),
            "includeRead" to SkillParam("boolean", "包含已读通知", false, "false")
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val packageName = params["packageName"] as? String
            val limit = (params["limit"] as? Number)?.toInt() ?: 20
            val includeRead = params["includeRead"] as? Boolean ?: false

            // 直接从系统通知栏获取（不依赖内存 StateFlow）
            val notifications = SmartNotificationListener.getActiveNotificationsList()
            Log.d("NotificationSkill", "Got ${notifications.size} notifications from system")

            val filtered = if (!includeRead) {
                // 内存列表中的已读状态，对新拉取的通知默认未读
                notifications
            } else {
                notifications
            }
            val finalNotifications = packageName?.let { pkgName ->
                filtered.filter { it.packageName.contains(pkgName, ignoreCase = true) }
            } ?: filtered

            val cardJson = buildNotificationListCard(finalNotifications, limit, packageName)
            return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
        }
    }

    inner class SendNotificationTool : SkillTool {
        override val name = "send_notification"
        override val description = "发送一条本地通知"
        override val parameters = mapOf(
            "title" to SkillParam("string", "通知标题", true),
            "text" to SkillParam("string", "通知内容", true),
            "importance" to SkillParam("string", "重要性：high/normal/low", false, "normal")
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val title = params["title"] as? String ?: return SkillResult(false, "", "缺少 title 参数")
            val text = params["text"] as? String ?: return SkillResult(false, "", "缺少 text 参数")
            val importance = params["importance"] as? String ?: "normal"

            val priority = when (importance.lowercase()) {
                "high" -> NotificationCompat.PRIORITY_HIGH
                "low" -> NotificationCompat.PRIORITY_LOW
                else -> NotificationCompat.PRIORITY_DEFAULT
            }

            val notificationId = (title.hashCode() + System.currentTimeMillis().toInt())
            val notification = NotificationCompat.Builder(context, "openclaw_skill")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)

            val cardJson = buildNotificationSendCard(title)
            return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
        }
    }

    inner class DeleteNotificationTool : SkillTool {
        override val name = "delete_notification"
        override val description = "删除指定通知（通过通知 ID）"
        override val parameters = mapOf(
            "notificationId" to SkillParam("string", "通知 ID", true)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val notificationId = params["notificationId"] as? String
                ?: return SkillResult(false, "", "缺少 notificationId 参数")

            // 尝试删除系统通知栏中的通知
            try {
                val id = notificationId.toIntOrNull()
                if (id != null) {
                    notificationManager.cancel(id)
                }
            } catch (_: Exception) {}

            // 同时从 SmartNotificationListener 中删除
            SmartNotificationListener.deleteNotification(notificationId)

            val cardJson = buildNotificationActionCard("delete", "通知已删除")
            return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
        }
    }

    inner class ClearNotificationsTool : SkillTool {
        override val name = "clear_notifications"
        override val description = "清空所有通知"
        override val parameters = mapOf(
            "packageName" to SkillParam("string", "只清空指定包名的通知（可选）", false)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            notificationManager.cancelAll()
            SmartNotificationListener.clearAll()
            val cardJson = buildNotificationActionCard("clear", "通知已清空")
            return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
        }
    }

    inner class MarkNotificationReadTool : SkillTool {
        override val name = "mark_notification_read"
        override val description = "标记通知为已读"
        override val parameters = mapOf(
            "notificationId" to SkillParam("string", "通知 ID", true)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val notificationId = params["notificationId"] as? String
                ?: return SkillResult(false, "", "缺少 notificationId 参数")

            SmartNotificationListener.markAsRead(notificationId)
            val cardJson = buildNotificationActionCard("mark_read", "已标记已读")
            return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
        }
    }

    // ==================== A2UI Card JSON 构建 ====================

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildNotificationListCard(notifications: List<SmartNotification>, limit: Int, packageName: String?): String {
        val filtered = if (packageName != null) {
            notifications.filter { it.packageName.contains(packageName, ignoreCase = true) }
        } else {
            notifications
        }
        val unreadCount = filtered.count { !it.isRead }
        val displayed = filtered.take(limit)
        
        val notificationsArray = JsonArray(
            displayed.map { n ->
                JsonObject(
                    mapOf(
                        "package" to JsonPrimitive(n.packageName),
                        "title" to JsonPrimitive(n.title),
                        "text" to JsonPrimitive(n.text),
                        "time" to JsonPrimitive(formatTimestamp(n.timestamp))
                    )
                )
            }
        )
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("notification_list"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("通知"),
                        "notifications" to notificationsArray,
                        "total" to JsonPrimitive(filtered.size),
                        "unread" to JsonPrimitive(unreadCount)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("🗑️ 清除所有"),
                                "action" to JsonPrimitive("clear_all")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildNotificationSendCard(title: String): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("notification_send"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("通知已发送"),
                        "notificationTitle" to JsonPrimitive(title)
                    )
                ),
                "actions" to JsonArray(emptyList())
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildNotificationActionCard(action: String, title: String): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("notification_action"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive(title),
                        "action" to JsonPrimitive(action)
                    )
                ),
                "actions" to JsonArray(emptyList())
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return try {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
        } catch (e: Exception) {
            ""
        }
    }

    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}
