package ai.openclaw.android.personalcenter

import ai.openclaw.android.GatewayContract
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.personalcenter.models.*
import ai.openclaw.android.personalcenter.sources.ItemSource
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.regex.Pattern

/**
 * LLM 驱动的优先级分类器
 *
 * 架构：
 * - 有 LLM 时：批量发送 items 给 LLM，解析返回的 JSON，设置 priorityLevel/actionType/expiryTimestamp
 * - 无 LLM 时（fallback）：用 ImportanceCalculator 的规则打分，映射到 PriorityLevel
 * - LLM 超时 20 秒 fallback 到规则模式
 */
object PriorityClassifier {

    private val TAG = "PriorityClassifier"
    private val LLM_TIMEOUT_MS = 20_000L

    /** LLM 调用回调，由外部注入 */
    var llmClassifier: LlmClassifier? = null

    fun interface LlmClassifier {
        /**
         * 批量分类优先级
         * @param items 待分类的条目
         * @return Map<id, PriorityResult>
         */
        suspend fun classify(items: List<CenterItem>): Map<String, PriorityResult>
    }

    data class PriorityResult(
        val level: PriorityLevel,
        val action: ActionType,
        val expiryMinutes: Int,
        val reason: String
    )

    /**
     * 批量分类 — 主入口
     */
    suspend fun classifyBatch(items: List<CenterItem>): List<CenterItem> {
        if (items.isEmpty()) return emptyList()

        val results = try {
            withTimeout(LLM_TIMEOUT_MS) {
                llmClassifier?.classify(items) ?: runRuleBasedClassification(items)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "LLM classification timeout, falling back to rules")
            runRuleBasedClassification(items)
        } catch (e: Exception) {
            Log.w(TAG, "LLM classification failed: ${e.message}, falling back to rules")
            runRuleBasedClassification(items)
        }

        return items.map { item ->
            val result = results[item.id]
            if (result != null) {
                val expiryTs = if (result.expiryMinutes > 0) {
                    item.createdAt + result.expiryMinutes * 60_000L
                } else {
                    Long.MAX_VALUE
                }
                item.copy(
                    priorityLevel = result.level,
                    actionType = result.action,
                    expiryTimestamp = expiryTs
                )
            } else {
                item
            }
        }
    }

    /**
     * 基于规则的降级分类
     */
    private fun runRuleBasedClassification(items: List<CenterItem>): Map<String, PriorityResult> {
        val results = mutableMapOf<String, PriorityResult>()
        val now = System.currentTimeMillis()

        for (item in items) {
            val ageMs = now - item.timestamp
            val ageHours = ageMs / 3_600_000.0
            val text = (item.title + " " + item.body).lowercase()

            val result = when {
                // 未接来电：2 小时内 = urgent，否则 = reference
                (text.contains("未接") || text.contains("missed")) && item.source == ItemSource.CALL_LOG -> {
                    if (ageHours < 2) {
                        PriorityResult(PriorityLevel.URGENT, ActionType.REPLY, 120, "2小时内未接来电")
                    } else {
                        PriorityResult(PriorityLevel.REFERENCE, ActionType.NONE, 0, "过期未接来电")
                    }
                }
                // 验证码：30 分钟内 = urgent，否则 = reference
                text.contains("验证码") || text.contains("verification") || text.contains("otp") -> {
                    if (ageHours < 0.5) {
                        PriorityResult(PriorityLevel.URGENT, ActionType.INFO, 30, "验证码")
                    } else {
                        PriorityResult(PriorityLevel.REFERENCE, ActionType.NONE, 0, "过期验证码")
                    }
                }
                // 日历：即将开始的会议 = urgent，今天内 = today，已过 = reference
                item.source == ItemSource.CALENDAR -> {
                    val diff = item.timestamp - now
                    when {
                        diff in -3_600_000L..1_800_000L -> {
                            PriorityResult(PriorityLevel.URGENT, ActionType.INFO, 30, "即将开始的会议")
                        }
                        diff > 0 && diff < 24 * 3_600_000L -> {
                            PriorityResult(PriorityLevel.TODAY, ActionType.INFO, 1440, "今天的会议")
                        }
                        else -> {
                            PriorityResult(PriorityLevel.REFERENCE, ActionType.NONE, 0, "已过期日程")
                        }
                    }
                }
                // 银行/支付通知 = today
                text.contains("银行") || text.contains("bank") || text.contains("支付") ||
                text.contains("转账") || text.contains("扣款") || text.contains("到账") -> {
                    PriorityResult(PriorityLevel.TODAY, ActionType.INFO, 1440, "银行/支付通知")
                }
                // 紧急关键词 = urgent
                isUrgentKeyword(text) -> {
                    PriorityResult(PriorityLevel.URGENT, ActionType.INFO, 120, "紧急关键词")
                }
                // 重要关键词 = today
                isImportantKeyword(text) -> {
                    PriorityResult(PriorityLevel.TODAY, ActionType.INFO, 1440, "重要关键词")
                }
                // 社交消息 = today
                item.source == ItemSource.SMS -> {
                    PriorityResult(PriorityLevel.TODAY, ActionType.NONE, 1440, "短信")
                }
                // 其他 = reference
                else -> {
                    PriorityResult(PriorityLevel.REFERENCE, ActionType.NONE, 0, "普通通知")
                }
            }

            results[item.id] = result
        }

        return results
    }

    private val URGENT_KEYWORDS = listOf(
        "电话", "来电", "通话", "missed call",
        "视频通话", "语音通话",
        "紧急", "urgent", "emergency",
    )

    private val IMPORTANT_KEYWORDS = listOf(
        "邮件", "email", "mail",
        "日历", "calendar", "会议", "meeting",
        "提醒", "reminder",
        "工作", "work",
        "支付", "转账", "付款", "payment",
    )

    private fun isUrgentKeyword(text: String): Boolean =
        URGENT_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private fun isImportantKeyword(text: String): Boolean =
        IMPORTANT_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    // ========== LLM Prompt ==========

    private fun buildPrompt(items: List<CenterItem>): String {
        val itemsText = items.joinToString("\n") { item ->
            "[${item.id}] ${item.sourceApp} | ${item.title} | ${item.body.take(100)}"
        }

        return """
你是手机助手的优先级分析引擎。评估以下消息，判断用户是否需要采取行动。

对每条消息返回 JSON：
[{"id":"xxx", "level":"urgent|today|reference", "action":"reply|act|info|none", "expiry_minutes":30, "reason":"一句话"}]

评估标准：
- urgent: 需要立即行动且时间敏感。未接来电(2小时内)、验证码(30分钟内)、即将到来的会议(30分钟内开始)
- today: 今天内需要关注但不紧急。今天内的会议、银行/支付通知、社交消息
- reference: 纯信息记录，无需行动。广告、系统通知、过期消息、几天前的未接来电

关键原则：
1. 几天前的未接来电 = reference（不是urgent）
2. 验证码超过30分钟 = reference
3. 纯通知类消息 = reference
4. 已发生的会议 = reference
5. 只有"需要用户行动且时间敏感"的才是 urgent

消息列表：
$itemsText

只返回JSON，不要其他文字。
""".trimIndent()
    }

    // ========== JSON 解析 ==========

    private val RESULT_PATTERN = Pattern.compile(
        "\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"level\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"action\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"expiry_minutes\"\\s*:\\s*(\\d+)"
    )

    fun parseLLMResponse(response: String): Map<String, PriorityResult> {
        val results = mutableMapOf<String, PriorityResult>()
        val matcher = RESULT_PATTERN.matcher(response)

        while (matcher.find()) {
            try {
                val id = matcher.group(1) ?: continue
                val levelStr = matcher.group(2) ?: continue
                val actionStr = matcher.group(3) ?: continue
                val expiryMinutes = matcher.group(4)?.toIntOrNull() ?: 0

                val level = when (levelStr.lowercase()) {
                    "urgent" -> PriorityLevel.URGENT
                    "today" -> PriorityLevel.TODAY
                    "reference" -> PriorityLevel.REFERENCE
                    else -> PriorityLevel.UNKNOWN
                }

                val action = when (actionStr.lowercase()) {
                    "reply" -> ActionType.REPLY
                    "act" -> ActionType.ACT
                    "info" -> ActionType.INFO
                    else -> ActionType.NONE
                }

                // 提取 reason（可选）
                val reason = extractReason(response, id)

                results[id] = PriorityResult(level, action, expiryMinutes, reason)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse LLM result: ${e.message}")
            }
        }

        return results
    }

    private fun extractReason(response: String, id: String): String {
        val reasonPattern = Pattern.compile(
            "\"id\"\\s*:\\s*\"${Pattern.quote(id)}\".*?\"reason\"\\s*:\\s*\"([^\"]+)\""
        )
        val matcher = reasonPattern.matcher(response)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    /**
     * 构建 LLM 评估器（供 ViewModel 注入使用）
     */
    fun createLlmEvaluator(contract: GatewayContract): LlmClassifier {
        return LlmClassifier { items ->
            val prompt = buildPrompt(items)
            val response = StringBuilder()

            contract.sendMessage(prompt).collect { event ->
                when (event) {
                    is SessionEvent.Token -> response.append(event.text)
                    is SessionEvent.Complete -> {}
                    is SessionEvent.Error -> throw RuntimeException(event.message)
                    else -> {}
                }
            }

            parseLLMResponse(response.toString())
        }
    }

    fun clearCache() {
        // No caching for now
    }
}
