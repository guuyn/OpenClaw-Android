package ai.openclaw.android.personalcenter.sources

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import ai.openclaw.android.personalcenter.ImportanceCalculator
import ai.openclaw.android.personalcenter.models.CenterItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 通话记录数据源 — 读取 CallLog.Calls，按时间倒序返回
 *
 * 数据来源：系统通话记录 ContentProvider
 * 字段：号码、联系人姓名（查联系人）、通话类型（来电/去电/未接/拒接）、时长、时间
 */
class CallLogSource(private val context: Context) {

    private val TAG = "CallLogSource"

    fun observe(): Flow<List<CenterItem>> = callbackFlow {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val limit = 100 // 最多读取最近 100 条

        fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        fun fetch(): List<CenterItem> {
            if (!hasPermission()) {
                Log.w(TAG, "No READ_CALL_LOG permission, returning empty")
                return emptyList()
            }

            val now = System.currentTimeMillis()
            val cutoff = now - 7 * 24 * 60 * 60 * 1000L // 最近 7 天

            return try {
                val items = mutableListOf<CenterItem>()

                Log.d(TAG, "[DEBUG] cutoff=$cutoff")

                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    "${CallLog.Calls.DATE} >= ?",
                    arrayOf(cutoff.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )

                if (cursor == null) {
                    Log.w(TAG, "[DEBUG] query returned null cursor")
                    return emptyList()
                }

                Log.d(TAG, "[DEBUG] cursor.count=${cursor.count}")

                cursor.use {
                    val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                    val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idIndex)
                            val number = cursor.getString(numberIndex) ?: "未知号码"
                            val cachedName = cursor.getString(nameIndex)
                            val type = cursor.getInt(typeIndex)
                            val date = cursor.getLong(dateIndex)
                            val duration = cursor.getLong(durationIndex)

                            // 尝试从联系人解析姓名
                            val displayName = if (!cachedName.isNullOrBlank()) {
                                cachedName
                            } else {
                                resolveContactName(context, number)
                            }

                            // 通话类型映射
                            val (typeLabel, typeIcon) = when (type) {
                                CallLog.Calls.MISSED_TYPE -> "未接来电" to "📵"
                                CallLog.Calls.INCOMING_TYPE -> "来电" to "📞"
                                CallLog.Calls.OUTGOING_TYPE -> "去电" to "📱"
                                CallLog.Calls.REJECTED_TYPE -> "拒接" to "🚫"
                                CallLog.Calls.BLOCKED_TYPE -> "已拦截" to "🔇"
                                else -> "通话" to "📞"
                            }

                            val durationText = when {
                                type == CallLog.Calls.MISSED_TYPE -> "未接听"
                                duration == 0L -> "响铃未接听"
                                duration < 60 -> "${duration}秒"
                                else -> "${duration / 60}分${duration % 60}秒"
                            }

                            // 打开通话记录的 Intent
                            val openIntent = try {
                                val callUri = Uri.parse("tel:$number")
                                val intent = Intent(Intent.ACTION_VIEW, callUri)
                                PendingIntent.getActivity(
                                    context,
                                    nextRequestCode(),
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create call open intent: ${e.message}")
                                null
                            }

                            val item = CenterItem(
                                id = "call_$id",
                                source = ItemSource.CALL_LOG,
                                sourceApp = "电话",
                                importance = 0f,
                                title = "$typeIcon $typeLabel · $displayName",
                                body = "$number · $durationText",
                                timestamp = date,
                                openIntent = openIntent,
                            )
                            items.add(item.copy(importance = ImportanceCalculator.calculate(item)))
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping invalid call log row: ${e.message}")
                        }
                    }
                }

                Log.d(TAG, "Fetched ${items.size} call log items")
                items
            } catch (e: SecurityException) {
                Log.w(TAG, "No call log permission: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query call log: ${e.message}", e)
                emptyList()
            }
        }

        // 持续轮询：未授权时每 2 秒检查一次，授权后每 30 秒刷新
        var consecutiveSuccess = 0
        while (true) {
            val result = fetch()
            trySend(result)
            if (!hasPermission()) {
                // 未授权时快速轮询（每 2 秒），直到用户授权
                delay(2000)
            } else {
                // 连续 3 次成功获取数据后，降低频率到每 30 秒
                consecutiveSuccess++
                if (consecutiveSuccess >= 3) {
                    delay(30_000)
                } else {
                    delay(5000) // 授权后前几次稍微频繁一点
                }
            }
        }
    }

    /**
     * 通过电话号码查联系人姓名
     */
    private fun resolveContactName(context: Context, number: String): String {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
            // 号码脱敏显示：138****1234
            if (number.length >= 7) {
                number.take(3) + "****" + number.takeLast(4)
            } else {
                number
            }
        } catch (e: Exception) {
            number
        }
    }
}
