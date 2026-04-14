package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class ReminderSkill(private val context: Context) : Skill {
    override val id = "reminder"
    override val name = "提醒"
    override val description = "设置和管理提醒事项"
    override val version = "2.0.0"
    
    override val instructions = """
# Reminder Skill

设置定时提醒，支持相对时间和绝对时间。

## 用法
- set_reminder: 设置提醒（支持 "in 5 minutes" 或 "2024-01-01 10:00"）
- list_reminders: 列出所有待处理提醒
- cancel_reminder: 取消提醒
"""
    
    private val reminders = mutableMapOf<String, ReminderInfo>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    
    data class ReminderInfo(
        val id: String,
        val title: String,
        val message: String,
        val time: Long
    )
    
    override val tools: List<SkillTool> = listOf(
        // set_reminder tool
        object : SkillTool {
            override val name = "set_reminder"
            override val description = "设置一个提醒"
            override val parameters = mapOf(
                "title" to SkillParam("string", "提醒标题", true),
                "time" to SkillParam("string", "提醒时间（如 'in 5 minutes' 或 '2024-01-01 10:00'）", true),
                "message" to SkillParam("string", "提醒内容（可选）", false, "")
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val title = params["title"] as? String
                if (title.isNullOrBlank()) return SkillResult(false, "", "缺少 title 参数")
                
                val timeStr = (params["time"] as? String) 
                    ?: (params["trigger_time"] as? String)
                if (timeStr.isNullOrBlank()) return SkillResult(false, "", "缺少 time 参数")
                
                val message = (params["message"] as? String) 
                    ?: (params["content"] as? String)
                    ?: ""
                
                return try {
                    val triggerTime = parseTime(timeStr)
                    val id = UUID.randomUUID().toString().take(8)
                    
                    scheduleReminder(id, title, message, triggerTime)
                    
                    val timeDisplay = dateFormat.format(Date(triggerTime))
                    val relativeTime = computeRelativeTime(triggerTime)
                    val cardJson = buildReminderConfirmCardV2(title, message, timeDisplay, relativeTime)
                    SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                } catch (e: Exception) {
                    SkillResult(false, "", "设置提醒失败: ${e.message}")
                }
            }
            
            private fun parseTime(timeStr: String): Long {
                val now = System.currentTimeMillis()
                
                val relativeRegex = Regex("in (\\d+) (minute|minutes|hour|hours)", RegexOption.IGNORE_CASE)
                val relativeMatch = relativeRegex.find(timeStr)
                if (relativeMatch != null) {
                    val amount = relativeMatch.groupValues[1].toLong()
                    val unit = relativeMatch.groupValues[2].lowercase()
                    val multiplier = if (unit.startsWith("hour")) 60 * 60 * 1000L else 60 * 1000L
                    return now + amount * multiplier
                }
                
                return try {
                    dateFormat.parse(timeStr)?.time ?: throw IllegalArgumentException("Invalid time format")
                } catch (e: Exception) {
                    throw IllegalArgumentException("无法解析时间: $timeStr。支持格式: 'in 5 minutes' 或 '2024-01-01 10:00'")
                }
            }
            
            private fun scheduleReminder(id: String, title: String, message: String, triggerTime: Long) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra("reminder_id", id)
                    putExtra("reminder_title", title)
                    putExtra("reminder_message", message)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                
                // Use the outer class reminders map
                this@ReminderSkill.reminders[id] = ReminderInfo(id, title, message, triggerTime)
            }
        },
        
        // list_reminders tool
        object : SkillTool {
            override val name = "list_reminders"
            override val description = "列出所有待处理的提醒"
            override val parameters = emptyMap<String, SkillParam>()
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val now = System.currentTimeMillis()
                val activeReminders = this@ReminderSkill.reminders.values
                    .filter { it.time > now }
                    .sortedBy { it.time }
                
                if (activeReminders.isEmpty()) {
                    return SkillResult(true, "暂无待处理提醒")
                }
                
                val items = activeReminders.map { r ->
                    val timeDisplay = dateFormat.format(Date(r.time))
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(r.id),
                            "text" to JsonPrimitive(r.title),
                            "time" to JsonPrimitive(timeDisplay),
                            "status" to JsonPrimitive("pending")
                        )
                    )
                }
                
                val cardJson = buildReminderListCardV2(items)
                return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
            }
        },
        
        // cancel_reminder tool
        object : SkillTool {
            override val name = "cancel_reminder"
            override val description = "取消一个提醒"
            override val parameters = mapOf(
                "id" to SkillParam("string", "提醒ID", true)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val id = params["id"] as? String
                if (id.isNullOrBlank()) return SkillResult(false, "", "缺少 id 参数")
                
                val reminder = this@ReminderSkill.reminders.remove(id)
                if (reminder == null) {
                    return SkillResult(false, "", "未找到提醒: $id")
                }
                
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, ReminderReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                
                return SkillResult(true, "已取消提醒: ${reminder.title}")
            }
        }
    )
    
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}

    // ==================== v2 A2UI Card JSON 构建 ====================

    /** 计算相对时间描述 */
    internal fun computeRelativeTime(triggerTime: Long): String {
        val now = System.currentTimeMillis()
        val diff = triggerTime - now
        
        return when {
            diff < 0 -> "已过期"
            diff < 60 * 1000L -> "${diff / 1000}秒后"
            diff < 60 * 60 * 1000L -> "${diff / (60 * 1000L)}分钟后"
            diff < 24 * 60 * 60 * 1000L -> {
                val hours = diff / (60 * 60 * 1000L)
                val minutes = (diff % (60 * 60 * 1000L)) / (60 * 1000L)
                if (minutes > 0) "${hours}小时${minutes}分钟后" else "${hours}小时后"
            }
            else -> {
                val days = diff / (24 * 60 * 60 * 1000L)
                "${days}天后"
            }
        }
    }

    /** 构建提醒确认卡片 v2 */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildReminderConfirmCardV2(
        text: String,
        message: String = "",
        time: String,
        relativeTime: String
    ): String {
        val dataMap = mutableMapOf<String, JsonElement>(
            "title" to JsonPrimitive("✅ 已设置提醒"),
            "text" to JsonPrimitive(text),
            "time" to JsonPrimitive(time),
            "relativeTime" to JsonPrimitive(relativeTime)
        )
        if (message.isNotBlank()) {
            dataMap["message"] = JsonPrimitive(message)
        }

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("reminder"),
                "layout" to JsonPrimitive("reminder_confirm"),
                "data" to JsonObject(dataMap),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("✏️ 修改"),
                                "action" to JsonPrimitive("edit_reminder"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("🗑️ 取消"),
                                "action" to JsonPrimitive("cancel_reminder"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    /** 构建提醒列表卡片 v2 */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildReminderListCardV2(items: List<JsonObject>): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("reminder"),
                "layout" to JsonPrimitive("reminder_list"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("提醒列表"),
                        "count" to JsonPrimitive(items.size),
                        "items" to JsonArray(items)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("➕ 新建提醒"),
                                "action" to JsonPrimitive("add_reminder"),
                                "style" to JsonPrimitive("Primary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }
}
