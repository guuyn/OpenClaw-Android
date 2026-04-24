package ai.openclaw.android.personalcenter.sources

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import ai.openclaw.android.personalcenter.ImportanceCalculator
import ai.openclaw.android.personalcenter.models.CenterItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 日历数据源 — 复用 CalendarSkill 已验证的查询逻辑
 */
class CalendarSource(private val context: Context) {

    private val TAG = "CalendarSource"

    fun observe(): Flow<List<CenterItem>> = callbackFlow {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION
        )

        fun fetch(): List<CenterItem> {
            val now = System.currentTimeMillis()
            val startTime = now - 24 * 60 * 60 * 1000L // 过去1天
            val endTime = now + 7 * 24 * 60 * 60 * 1000L // 未来7天
            val uri = CalendarContract.Events.CONTENT_URI

            return try {
                val items = mutableListOf<CenterItem>()
                val selection = "(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?)"
                val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

                context.contentResolver.query(
                    uri, projection, selection, selectionArgs,
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(CalendarContract.Events._ID)
                    val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                    val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                    val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)

                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idIndex)
                            val title = cursor.getString(titleIndex) ?: "无标题"
                            val dtStart = cursor.getLong(startIndex)
                            val dtEnd = cursor.getLong(endIndex)

                            // 打开日历事件（使用全局计数器避免 requestCode 碰撞）
                            val openIntent = try {
                                val eventUri = CalendarContract.Events.CONTENT_URI.buildUpon()
                                    .appendPath(id.toString())
                                    .build()
                                val intent = Intent(Intent.ACTION_VIEW, eventUri)
                                PendingIntent.getActivity(
                                    context,
                                    nextRequestCode(),
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create calendar open intent: ${e.message}")
                                null
                            }

                            val item = CenterItem(
                                id = "cal_$id",
                                source = ItemSource.CALENDAR,
                                sourceApp = "日历",
                                importance = 0f,
                                title = title,
                                body = if (dtEnd > 0) {
                                    val duration = (dtEnd - dtStart) / 60_000
                                    "⏱ ${duration}分钟"
                                } else {
                                    "日程提醒"
                                },
                                timestamp = dtStart,
                                isRead = dtStart < now,
                                openIntent = openIntent,
                            )
                            items.add(item.copy(importance = ImportanceCalculator.calculate(item)))
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping invalid row: ${e.message}")
                        }
                    }
                }
                items
            } catch (e: SecurityException) {
                Log.w(TAG, "No calendar permission: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query calendar: ${e.message}", e)
                emptyList()
            }
        }

        try {
            trySend(fetch())
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(fetch())
                }
            }
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI, true, observer
            )
            Log.d(TAG, "CalendarSource registered")
            awaitClose {
                try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CalendarSource observe error: ${e.message}")
            close(e)
        }
    }
}
