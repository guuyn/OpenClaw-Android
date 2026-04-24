package ai.openclaw.android.personalcenter.sources

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import ai.openclaw.android.personalcenter.ImportanceCalculator
import ai.openclaw.android.personalcenter.models.CenterItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 短信数据源 — 复用 SMSSkill 已验证的查询逻辑
 */
class SmsSource(private val context: Context) {

    private val TAG = "SmsSource"

    fun observe(): Flow<List<CenterItem>> = callbackFlow {
        val projection = arrayOf("_id", "address", "body", "date")
        val uri = "content://sms/inbox".toUri()

        fun fetch(): List<CenterItem> {
            val now = System.currentTimeMillis()
            val cutoff = now - 7 * 24 * 60 * 60 * 1000L

            return try {
                val items = mutableListOf<CenterItem>()

                context.contentResolver.query(
                    uri, projection, "date >= ?", arrayOf(cutoff.toString()),
                    "date DESC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex("_id")
                    val addressIndex = cursor.getColumnIndex("address")
                    val bodyIndex = cursor.getColumnIndex("body")
                    val dateIndex = cursor.getColumnIndex("date")

                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idIndex)
                            val sender = cursor.getString(addressIndex) ?: "未知"
                            val body = cursor.getString(bodyIndex) ?: ""
                            val date = cursor.getLong(dateIndex)

                            if (body.isBlank()) continue

                            val senderName = resolveSenderName(context, sender)

                            // 打开短信对话（使用全局计数器避免 requestCode 碰撞）
                            val openIntent = try {
                                val smsUri = Uri.parse("sms:$sender")
                                val intent = Intent(Intent.ACTION_VIEW, smsUri).apply {
                                    putExtra("sms_body", "")
                                }
                                PendingIntent.getActivity(
                                    context,
                                    nextRequestCode(),
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create SMS open intent: ${e.message}")
                                null
                            }

                            val item = CenterItem(
                                id = "sms_$id",
                                source = ItemSource.SMS,
                                sourceApp = senderName,
                                importance = 0f,
                                title = senderName,
                                body = body,
                                timestamp = date,
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
                Log.w(TAG, "No SMS permission: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query SMS: ${e.message}", e)
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
            context.contentResolver.registerContentObserver(uri, true, observer)
            Log.d(TAG, "SmsSource registered")
            awaitClose {
                try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsSource observe error: ${e.message}")
            close(e)
        }
    }

    /** 复用 SMSSkill 的逻辑：通过联系人匹配发送者名称 */
    private fun resolveSenderName(context: Context, address: String): String {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(address)
            )
            context.contentResolver.query(
                uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
            address
        } catch (e: Exception) {
            address
        }
    }
}
