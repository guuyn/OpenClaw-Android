package ai.openclaw.android.domain

/**
 * 多轮自我反思策略
 *
 * 基于实验结论：
 * - 固定提示词第3轮饱和（25%变化率）
 * - 角色切换策略持续有效（第3轮75%变化率）
 * - 最优轮次：2轮（初始+1次反思）
 *
 * @see <a href="https://github.com/kyegomez/OpenMythos">OpenMythos 循环深度 Transformer</a>
 */
enum class ReflectionStrategy {
    /** 不反思，直接返回（简单问题） */
    NONE,

    /** 1轮反思：初始回答 + 检查员角色（中等问题） */
    SINGLE,

    /** 2轮角色切换：初始 + 检查员 + 批评者（复杂问题） */
    DOUBLE;

    companion object {
        /** 根据问题复杂度自动选择策略 */
        fun autoSelect(question: String): ReflectionStrategy {
            // 简单问候/短查询 → 不需要反思
            if (question.length < 15 || isSimpleQuery(question)) {
                return NONE
            }
            // 包含推理/解释关键词 → 需要多轮
            if (containsAny(question, listOf("如何", "为什么", "解释", "分析", "比较", "方案", "设计"))) {
                return DOUBLE
            }
            // 中等复杂度
            if (containsAny(question, listOf("怎么", "什么", "哪里", "谁", "何时"))) {
                return SINGLE
            }
            // 默认单轮反思
            return SINGLE
        }

        private fun isSimpleQuery(text: String): Boolean {
            val simplePatterns = listOf("你好", "哈喽", "hi", "hello", "在吗", "谢谢", "好的", "嗯")
            return simplePatterns.any { text.contains(it, ignoreCase = true) }
        }

        private fun containsAny(text: String, keywords: List<String>): Boolean {
            return keywords.any { text.contains(it) }
        }
    }
}

/**
 * 反思角色配置
 */
data class ReflectionConfig(
    val strategy: ReflectionStrategy = ReflectionStrategy.SINGLE,
    val roles: List<ReflectionRole> = listOf(ReflectionRole.CHECKER, ReflectionRole.CRITIC)
) {
    companion object {
        fun defaultFor(strategy: ReflectionStrategy): ReflectionConfig {
            return when (strategy) {
                ReflectionStrategy.NONE -> ReflectionConfig(strategy, emptyList())
                ReflectionStrategy.SINGLE -> ReflectionConfig(strategy, listOf(ReflectionRole.CHECKER))
                ReflectionStrategy.DOUBLE -> ReflectionConfig(
                    strategy,
                    listOf(ReflectionRole.CHECKER, ReflectionRole.CRITIC)
                )
            }
        }
    }
}

/**
 * 反思角色
 */
enum class ReflectionRole {
    /** 检查员：严格检查逻辑正确性 */
    CHECKER,

    /** 批评者：挑剔地找出所有问题 */
    CRITIC,

    /** 优化师：在正确的基础上优化表达 */
    OPTIMIZER;

    /** 生成该角色的反思提示词 */
    fun buildPrompt(question: String, answer: String): String {
        return when (this) {
            CHECKER -> """
你是一个严格的逻辑检查员。请仔细检查以下回答：

问题：$question

你的回答：
$answer

重点检查：
1. 推理步骤是否严密？有没有逻辑跳跃？
2. 结论是否从前提中合理推出？
3. 有没有遗漏重要条件或边界情况？
4. 计算/推导过程是否正确？

⚠️ 重要规则：
- 如果发现问题，请直接输出修正后的完整回答（不要输出分析报告）
- 如果回答已经很好，请直接输出原文或微调后的版本
- 禁止输出"检查通过"、"逻辑检查报告"、"发现的问题"等分析性文字
- 你唯一应该输出的就是改进后的回答本身
- 如果原回答包含 [A2UI]...[/A2UI] 卡片，必须原样保留，不得改为纯文本
""".trimIndent()

            CRITIC -> """
你是一个挑剔的批评者。你的任务是审查并改进以下回答：

问题：$question

你的回答：
$answer

审查方向：
- 逻辑漏洞
- 遗漏的条件或边界情况
- 不准确的表述
- 可以更好的地方

⚠️ 重要规则：
- 如果发现问题，请直接输出改进后的完整回答（不要输出审查报告）
- 如果确实没有问题，请直接输出原文
- 禁止输出"逻辑检查报告"、"发现的问题"、"检查结论"等分析性文字
- 禁止输出"❌ 检查未通过"、"⚠️"等评价性标记
- 你唯一应该输出的就是改进后的回答本身
- 🔥 如果原回答包含 [A2UI]...[/A2UI] 卡片，必须完整保留 A2UI 格式，不得改为纯文本描述
""".trimIndent()

            OPTIMIZER -> """
你是一个优化专家。以下回答基本正确，但请优化它：

问题：$question

你的回答：
$answer

优化方向：
1. 表达是否更清晰易懂？
2. 结构是否更合理？
3. 有没有遗漏的边界情况？
4. 能否给出更完整/更精确的答案？
5. 语言是否自然流畅？

⚠️ 重要规则：
- 请直接输出优化后的完整回答（不要输出优化说明）
- 禁止输出"优化建议"、"优化方向"、"改进如下"等说明性文字
- 你唯一应该输出的就是优化后的回答本身
- 🔥 如果原回答包含 [A2UI]...[/A2UI] 卡片，必须完整保留 A2UI 格式，不得改为纯文本描述
""".trimIndent()
        }
    }
}
