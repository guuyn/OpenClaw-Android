package ai.openclaw.android.personalcenter.sources

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.personalcenter.ImportanceCalculator
import ai.openclaw.android.personalcenter.models.CenterItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 全局 PendingIntent requestCode 计数器 — 三源共用，避免碰撞
 */
private val requestCodeCounter = java.util.concurrent.atomic.AtomicInteger(10000)
internal fun nextRequestCode(): Int = requestCodeCounter.incrementAndGet()

/**
 * 通知数据源 — 包装现有 SmartNotificationListener
 */
class NotificationSource(private val context: Context) {

    private val TAG = "NotificationSource"

    fun observe(): Flow<List<CenterItem>> = flow {
        SmartNotificationListener.notifications.collect { notifications ->
            val items = notifications.map { notification ->
                val openIntent = createOpenIntent(context, notification.packageName)
                val item = CenterItem(
                    id = "notif_${notification.id}",
                    source = ItemSource.NOTIFICATION,
                    sourceApp = getAppName(notification.packageName),
                    importance = 0f,
                    title = notification.title,
                    body = notification.text,
                    timestamp = notification.timestamp,
                    isRead = notification.isRead,
                    openIntent = openIntent,
                )
                item.copy(importance = ImportanceCalculator.calculate(item))
            }
            emit(items)
        }
    }

    private fun createOpenIntent(context: Context, packageName: String): PendingIntent? {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    nextRequestCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "No launch intent for $packageName: ${e.message}")
            null
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
