package ai.openclaw.android.domain

/**
 * 多轮自我反思策略（2026-04-24 重构）
 *
 * 重构原因：
 * - 旧版默认开启反思，导致大量简单查询也被反思拖慢
 * - 空字符串会覆盖好答案
 * - 没有超时保护
 * - A2UI 卡片格式经常被反思破坏
 *
 * 核心原则：
 * 1. 默认关闭，按需开启
 * 2. 反思失败时绝不覆盖原答案
 * 3. 单轮有超时限制
 * 4. 变更 < 阈值时早停
 */
enum class ReflectionStrategy {
    /** 默认：不反思，直接返回 */
    NONE,

    /** 轻量反思：1 轮检查员角色，用于需要一定质量保障的场景 */
    LIGHT;

    companion object {
        /**
         * 根据问题复杂度自动选择策略
         * 注意：大部分场景默认 NONE，只有高价值场景才开启反思
         */
        fun autoSelect(question: String): ReflectionStrategy {
            if (isSimpleQuery(question)) return NONE
            // 只有明确的「设计」「方案」「比较」等复杂场景才反思
            if (containsAny(question, listOf("设计方案", "架构设计", "比较分析", "优缺点"))) {
                return LIGHT
            }
            return NONE
        }

        private fun isSimpleQuery(text: String): Boolean {
            if (text.length < 10) return true
            val simplePatterns = listOf("你好", "哈喽", "hi", "hello", "在吗", "谢谢", "好的", "嗯", "知道了", "明白")
            return simplePatterns.any { text.contains(it, ignoreCase = true) }
        }

        private fun containsAny(text: String, keywords: List<String>): Boolean {
            return keywords.any { text.contains(it) }
        }
    }
}

/**
 * 反思配置
 */
data class ReflectionConfig(
    val strategy: ReflectionStrategy = ReflectionStrategy.NONE,
    /** 单轮超时（毫秒），默认 15 秒 */
    val timeoutMs: Long = 15_000L,
    /** 最小变化率阈值，低于此值早停 */
    val minChangeRate: Double = 0.05,
    /** 是否保护 A2UI 格式：检查反思后 [A2UI] 标签是否完整 */
    val protectA2UI: Boolean = true
) {
    companion object {
        fun defaultFor(strategy: ReflectionStrategy): ReflectionConfig {
            return when (strategy) {
                ReflectionStrategy.NONE -> ReflectionConfig(strategy)
                ReflectionStrategy.LIGHT -> ReflectionConfig(strategy)
            }
        }
    }
}

/**
 * 反思结果
 */
data class ReflectionResult(
    val refinedContent: String,
    val changed: Boolean,
    val changeRate: Double,
    val roundsCompleted: Int,
    val a2uiPreserved: Boolean
) {
    companion object {
        fun unchanged(original: String) = ReflectionResult(
            refinedContent = original,
            changed = false,
            changeRate = 0.0,
            roundsCompleted = 0,
            a2uiPreserved = true
        )
    }
}

/**
 * 反思角色（简化为单一检查员，避免多轮角色切换导致的累积错误）
 */
enum class ReflectionRole {
    /** 检查员：严格检查逻辑正确性，保留原样或微调 */
    CHECKER;

    /** 生成该角色的反思提示词 */
    fun buildPrompt(question: String, answer: String): String {
        return """
你是一个严格的逻辑检查员。请仔细检查以下回答：

问题：$question

待检查的回答：
$answer

你的任务：
1. 检查是否有事实错误或逻辑漏洞
2. 检查是否有遗漏的重要条件
3. 如果回答已经正确完整，请直接返回原文
4. 如果发现问题，请直接输出修正后的完整回答

⚠️ 严格规则：
- 禁止输出"检查通过"、"逻辑检查报告"等分析性文字
- 禁止输出"❌"、"⚠️"等评价性标记
- 你唯一应该输出的就是改进后的回答本身
- 🔥 如果回答包含 [A2UI]...[/A2UI] 标签，必须完整保留，不得删除或改为纯文本
""".trimIndent()
    }
}

/**
 * 反思工具函数
 */
object ReflectionUtils {

    /** 计算两段文本的变化率（基于字符差异） */
    fun changeRate(original: String, refined: String): Double {
        if (original.isEmpty() && refined.isEmpty()) return 0.0
        if (original.isEmpty() || refined.isEmpty()) return 1.0
        val maxLen = maxOf(original.length, refined.length)
        val diff = levenshteinDistance(original, refined)
        return diff.toDouble() / maxLen
    }

    /** 轻量 Levenshtein 距离（用于短文本） */
    private fun levenshteinDistance(a: String, b: String): Int {
        // 截断到前 500 字符，避免长文本计算开销
        val sa = a.take(500)
        val sb = b.take(500)
        if (sa.isEmpty()) return sb.length
        if (sb.isEmpty()) return sa.length

        val prev = IntArray(sb.length + 1) { it }
        val curr = IntArray(sb.length + 1)

        for (i in 1..sa.length) {
            curr[0] = i
            for (j in 1..sb.length) {
                val cost = if (sa[i - 1] == sb[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, sb.length + 1)
        }
        return prev[sb.length]
    }

    /** 检查 A2UI 格式是否完整 */
    fun isA2UIPreserved(original: String, refined: String): Boolean {
        val origHasA2UI = original.contains("[A2UI]") && original.contains("[/A2UI]")
        val refinedHasA2UI = refined.contains("[A2UI]") && refined.contains("[/A2UI]")
        // 如果原版有 A2UI，反思后也必须有
        if (origHasA2UI) return refinedHasA2UI
        return true
    }
}
