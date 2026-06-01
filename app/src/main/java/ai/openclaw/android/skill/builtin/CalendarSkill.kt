package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class CalendarSkill(private val context: Context) : Skill {
    override val id = "calendar"
    override val name = "日程"
    override val description = "管理日历事件和日程"
    override val version = "2.0.0"
    
    override val instructions = """
# Calendar Skill

管理日历事件，查看和添加日程。

## 用法
- list_events: 列出日程（默认今天到7天后）
- add_event: 添加日程事件

## A2UI 卡片输出格式（参考）
[A2UI]{"type":"calendar","data":{"title":"日程","date":"日期","items":[{"title":"事件","time":"时间","location":"地点","color":"#4A90D9"}]},"actions":[{"label":"📅 添加到日历","action":"add_calendar","style":"Secondary"}]}[/A2UI]
"""
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
    
    override val tools: List<SkillTool> = listOf(
        // list_events tool
        object : SkillTool {
            override val name = "list_events"
            override val description = "列出日历事件"
            override val parameters = mapOf(
                "days" to SkillParam("number", "查询天数（默认7天）", false, 7)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val days = (params["days"] as? Number)?.toInt() ?: 7
                
                return try {
                    val events = queryEvents(days)
                    if (events.isEmpty()) {
                        val cardJson = buildCalendarCardV2(
                            title = "日程",
                            date = "未来 $days 天",
                            items = emptyList()
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        val items = events.map { e ->
                            CalendarCardItem(
                                title = e.title,
                                time = displayFormat.format(Date(e.startTime)),
                                location = "",
                                color = "#4A90D9"
                            )
                        }
                        val cardJson = buildCalendarCardV2(
                            title = "日程",
                            date = "未来 $days 天",
                            items = items
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    }
                } catch (e: SecurityException) {
                    SkillResult(false, "", "需要日历权限")
                } catch (e: Exception) {
                    SkillResult(false, "", "查询失败: ${e.message}")
                }
            }
            
            private fun queryEvents(days: Int): List<EventInfo> {
                val events = mutableListOf<EventInfo>()
                val resolver = context.contentResolver
                
                val startTime = System.currentTimeMillis()
                val endTime = startTime + days * 24 * 60 * 60 * 1000L
                
                val uri = CalendarContract.Events.CONTENT_URI
                val projection = arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.DESCRIPTION
                )
                
                val selection = "(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?)"
                val selectionArgs = arrayOf(startTime.toString(), endTime.toString())
                
                val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Events.DTSTART} ASC")
                
                cursor?.use {
                    val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
                    val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
                    val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
                    val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
                    
                    while (it.moveToNext()) {
                        events.add(EventInfo(
                            id = it.getLong(idIndex),
                            title = it.getString(titleIndex) ?: "无标题",
                            startTime = it.getLong(startIndex),
                            endTime = it.getLong(endIndex)
                        ))
                    }
                }
                
                return events
            }
        },
        
        // add_event tool
        object : SkillTool {
            override val name = "add_event"
            override val description = "添加日历事件"
            override val parameters = mapOf(
                "title" to SkillParam("string", "事件标题", true),
                "start_time" to SkillParam("string", "开始时间（格式: yyyy-MM-dd HH:mm）", true),
                "end_time" to SkillParam("string", "结束时间（格式: yyyy-MM-dd HH:mm）", true),
                "description" to SkillParam("string", "事件描述（可选）", false, "")
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val title = params["title"] as? String
                if (title.isNullOrBlank()) return SkillResult(false, "", "缺少 title 参数")
                
                val startTimeStr = params["start_time"] as? String
                if (startTimeStr.isNullOrBlank()) return SkillResult(false, "", "缺少 start_time 参数")
                
                val endTimeStr = params["end_time"] as? String
                if (endTimeStr.isNullOrBlank()) return SkillResult(false, "", "缺少 end_time 参数")
                
                val description = (params["description"] as? String) ?: ""
                
                return try {
                    val startTime = dateFormat.parse(startTimeStr)?.time
                        ?: return SkillResult(false, "", "无法解析开始时间")
                    
                    val endTime = dateFormat.parse(endTimeStr)?.time
                        ?: return SkillResult(false, "", "无法解析结束时间")
                    
                    val eventId = insertEvent(title, startTime, endTime, description)
                    
                    if (eventId > 0) {
                        val items = listOf(
                            CalendarCardItem(
                                title = title,
                                time = "$startTimeStr - $endTimeStr",
                                location = description,
                                color = "#4CAF50"
                            )
                        )
                        val cardJson = buildCalendarCardV2(
                            title = "已添加日程",
                            date = startDateStr(startTimeStr),
                            items = items
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        SkillResult(false, "", "添加事件失败")
                    }
                } catch (e: SecurityException) {
                    SkillResult(false, "", "需要日历权限")
                } catch (e: Exception) {
                    SkillResult(false, "", "添加失败: ${e.message}")
                }
            }
            
            private fun insertEvent(title: String, startTime: Long, endTime: Long, description: String): Long {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, getDefaultCalendarId())
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.DTSTART, startTime)
                    put(CalendarContract.Events.DTEND, endTime)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                return uri?.lastPathSegment?.toLong() ?: -1
            }
            
            private fun getDefaultCalendarId(): Long {
                val resolver = context.contentResolver
                val uri = CalendarContract.Calendars.CONTENT_URI
                val projection = arrayOf(CalendarContract.Calendars._ID)
                
                val cursor = resolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        return it.getLong(0)
                    }
                }
                return 1L // Default fallback
            }
        }
    )
    
    data class EventInfo(
        val id: Long,
        val title: String,
        val startTime: Long,
        val endTime: Long
    )

    data class CalendarCardItem(
        val title: String,
        val time: String,
        val location: String,
        val color: String
    )
    
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}

    // ==================== v2 A2UI Card JSON 构建 ====================

    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildCalendarCardV2(
        title: String,
        date: String,
        items: List<CalendarCardItem>
    ): String {
        val itemsJson = items.map { item ->
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive(item.title),
                    "time" to JsonPrimitive(item.time),
                    "location" to JsonPrimitive(item.location),
                    "color" to JsonPrimitive(item.color)
                )
            )
        }

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("calendar"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive(title),
                        "date" to JsonPrimitive(date),
                        "items" to JsonArray(itemsJson)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📅 添加到日历"),
                                "action" to JsonPrimitive("add_calendar"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    private fun startDateStr(startTimeStr: String): String {
        // Extract date part from "yyyy-MM-dd HH:mm"
        val parts = startTimeStr.split(" ")
        return if (parts.isNotEmpty()) parts[0] else startTimeStr
    }
}
