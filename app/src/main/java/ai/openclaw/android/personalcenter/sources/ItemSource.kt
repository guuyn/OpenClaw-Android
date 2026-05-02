package ai.openclaw.android.personalcenter.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 信息来源类型
 */
enum class ItemSource(
    val defaultIcon: ImageVector,
    val defaultLabel: String
) {
    NOTIFICATION(Icons.Default.Notifications, "通知"),
    CALENDAR(Icons.Default.Event, "日历"),
    SMS(Icons.Default.Message, "短信"),
    CALL_LOG(Icons.Default.Phone, "通话");

    companion object {
        /** 根据包名推测来源 */
        fun fromPackageName(packageName: String): ItemSource = when {
            packageName.contains("calendar", ignoreCase = true) -> CALENDAR
            packageName.contains("sms", ignoreCase = true) ||
            packageName.contains("mms", ignoreCase = true) -> SMS
            else -> NOTIFICATION
        }
    }
}
