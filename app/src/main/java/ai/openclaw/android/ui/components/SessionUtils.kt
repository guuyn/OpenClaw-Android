package ai.openclaw.android.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相对时间格式化
 * 刚刚 / 5分钟前 / 3小时前 / 昨天 / 3天前 / 04-25
 */
fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 172_800_000 -> "昨天"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> {
            val format = SimpleDateFormat("MM-dd", Locale.getDefault())
            format.format(Date(timestampMs))
        }
    }
}
