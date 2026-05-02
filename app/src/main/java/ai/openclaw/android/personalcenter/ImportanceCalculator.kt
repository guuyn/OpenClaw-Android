package ai.openclaw.android.personalcenter

import ai.openclaw.android.personalcenter.models.CenterItem
import ai.openclaw.android.personalcenter.sources.ItemSource

/**
 * 重要程度计算器
 * importance = baseScore × recencyWeight
 */
object ImportanceCalculator {

    // ========== 基础分 ==========

    fun calculateBaseScore(item: CenterItem): Float = when (item.source) {
        ItemSource.NOTIFICATION -> notificationBaseScore(item)
        ItemSource.CALENDAR -> calendarBaseScore(item)
        ItemSource.SMS -> smsBaseScore(item)
        ItemSource.CALL_LOG -> callLogBaseScore(item)
    }

    private fun notificationBaseScore(item: CenterItem): Float {
        val text = (item.title + " " + item.body).lowercase()
        return when {
            isUrgentKeyword(text) -> 0.95f
            isImportantKeyword(text) -> 0.70f
            else -> 0.40f
        }
    }

    private fun calendarBaseScore(item: CenterItem): Float {
        val now = System.currentTimeMillis()
        val diff = item.timestamp - now
        return when {
            diff < 0 -> 0.80f          // 已开始/进行中
            diff < 3_600_000L -> 0.85f // 1 小时内
            diff < 24 * 3_600_000L -> 0.80f // 今天内
            diff < 7 * 24 * 3_600_000L -> 0.60f // 本周内
            else -> 0.30f
        }
    }

    private fun smsBaseScore(item: CenterItem): Float {
        val text = (item.title + " " + item.body).lowercase()
        return when {
            isUrgentKeyword(text) -> 0.90f
            isSmsImportantKeyword(text) -> 0.85f
            else -> 0.50f
        }
    }

    private fun callLogBaseScore(item: CenterItem): Float {
        val text = (item.title + " " + item.body).lowercase()
        return when {
            text.contains("未接") || text.contains("missed") -> 0.95f
            text.contains("拒接") -> 0.80f
            text.contains("来电") || text.contains("incoming") -> 0.75f
            text.contains("去电") || text.contains("outgoing") -> 0.50f
            else -> 0.40f
        }
    }

    // ========== 时效衰减 ==========

    /**
     * 时间衰减因子：越新的内容权重越高
     */
    fun recencyWeight(item: CenterItem): Float {
        val ageMs = System.currentTimeMillis() - item.timestamp
        val ageHours = ageMs / 3_600_000.0
        return when {
            ageHours < 1 -> 1.0f
            ageHours < 6 -> 0.85f
            ageHours < 24 -> 0.60f
            ageHours < 72 -> 0.35f
            ageHours < 168 -> 0.15f
            else -> 0.08f
        }
    }

    // ========== 最终打分 ==========

    fun calculate(item: CenterItem): Float {
        val base = calculateBaseScore(item)
        val weight = recencyWeight(item)
        return (base * weight).coerceIn(0f, 1f)
    }

    // ========== 关键词库 ==========

    private val URGENT_KEYWORDS = listOf(
        "电话", "来电", "通话", "missed call",
        "视频通话", "语音通话",
        "紧急", "urgent", "emergency",
        "验证码", "verification code", "otp",
    )

    private val IMPORTANT_KEYWORDS = listOf(
        "邮件", "email", "mail",
        "日历", "calendar", "会议", "meeting",
        "提醒", "reminder",
        "工作", "work",
        "支付", "转账", "付款", "payment",
    )

    private val SMS_IMPORTANT_KEYWORDS = listOf(
        "验证码", "verification", "otp",
        "银行", "bank", "账户", "account",
        "快递", "delivery", "包裹",
        "支付", "payment", "账单",
        "密码", "password",
    )

    private fun isUrgentKeyword(text: String): Boolean =
        URGENT_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private fun isImportantKeyword(text: String): Boolean =
        IMPORTANT_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private fun isSmsImportantKeyword(text: String): Boolean =
        SMS_IMPORTANT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
}
