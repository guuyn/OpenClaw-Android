# Notification Skill 实施计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 创建 NotificationSkill，暴露获取/发送/删除通知的能力给 LLM Agent

**Architecture:** NotificationSkill 使用 NotificationManager 发送通知，通过 SmartNotificationListener 获取和删除系统通知

**Tech Stack:** Kotlin, Android NotificationManager, NotificationListenerService

---

## Task 1: 创建 NotificationSkill

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/skill/builtin/NotificationSkill.kt`
- Test: `app/src/test/java/ai/openclaw/android/skill/builtin/NotificationSkillTest.kt`

### Step 1: 创建 NotificationSkill

```kotlin
package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import ai.openclaw.android.notification.SmartNotification
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.notification.NotificationCategory
import kotlinx.coroutines.flow.first

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
    
    // ==================== Tools ====================
    
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
            
            var notifications = SmartNotificationListener.notifications.value
            if (!includeRead) {
                notifications = notifications.filter { !it.isRead }
            }
            packageName?.let {
                notifications = notifications.filter { it.packageName.contains(it, ignoreCase = true) }
            }
            
            val result = notifications.take(limit).map { n ->
                mapOf(
                    "id" to n.id,
                    "package" to n.packageName,
                    "title" to n.title,
                    "text" to n.text,
                    "timestamp" to n.timestamp,
                    "category" to n.category.name,
                    "isRead" to n.isRead
                )
            }
            
            return SkillResult(true, formatNotificationList(result, notifications.size), "")
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
            
            return SkillResult(true, "通知已发送: $title", "")
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
            
            // 删除系统通知栏中的通知（尝试解析为数字 ID）
            try {
                val id = notificationId.toIntOrNull()
                if (id != null) {
                    notificationManager.cancel(id)
                }
            } catch (_: Exception) {}
            
            // 同时从 SmartNotificationListener 中删除
            SmartNotificationListener.deleteNotification(notificationId)
            
            return SkillResult(true, "通知已删除: $notificationId", "")
        }
    }
    
    inner class ClearNotificationsTool : SkillTool {
        override val name = "clear_notifications"
        override val description = "清空所有通知"
        override val parameters = mapOf(
            "packageName" to SkillParam("string", "只清空指定包名的通知（可选）", false)
        )
        
        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val packageName = params["packageName"] as? String
            
            if (packageName != null) {
                notificationManager.cancelAll()
                SmartNotificationListener.clearAll()
                return SkillResult(true, "已清空 $packageName 的通知", "")
            }
            
            notificationManager.cancelAll()
            SmartNotificationListener.clearAll()
            return SkillResult(true, "所有通知已清空", "")
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
            return SkillResult(true, "已标记为已读", "")
        }
    }
    
    // ==================== Helpers ====================
    
    private fun formatNotificationList(notifications: List<Map<String, Any>>, total: Int): String {
        if (notifications.isEmpty()) {
            return "当前没有通知"
        }
        val sb = StringBuilder()
        sb.append("通知列表（共 $total 条）\n\n")
        notifications.forEachIndexed { index, n ->
            sb.append("${index + 1}. [${n["category"]}] ${n["title"]}\n")
            sb.append("   来源: ${n["package"]}\n")
            sb.append("   内容: ${n["text"]}\n")
            sb.append("   状态: ${if (n["isRead"] == true) "已读" else "未读"}\n\n")
        }
        return sb.toString()
    }
    
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}
```

### Step 2: 注册到 GatewayManager

在 GatewayManager.kt 的 `initializeComponents()` 和 `reconfigureModel()` 中添加：
```kotlin
registerSkill(NotificationSkill(service))
```

### Step 3: 测试

```kotlin
class NotificationSkillTest {
    @Test fun `skill has correct metadata`() { ... }
    @Test fun `has 5 tools`() { ... }
    @Test fun `list notifications tool has correct parameters`() { ... }
    @Test fun `send notification tool has correct parameters`() { ... }
    @Test fun `delete notification tool has correct parameters`() { ... }
}
```

### Step 4: 提交

---

## Task 2: 最终验证

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

提交。
