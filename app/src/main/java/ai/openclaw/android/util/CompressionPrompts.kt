package ai.openclaw.android.util

import ai.openclaw.android.data.model.MessageEntity
import kotlin.math.min

object CompressionPrompts {

    /**
     * 首次压缩系统 prompt
     * 要求 LLM 按四类输出结构化摘要，便于后续增量合并
     */
    val STRUCTURED_SUMMARIZE_SYSTEM = """
你是一个会话摘要助手。请将对话历史压缩为结构化摘要。

必须按以下四个类别输出（每类用 ## 标题，用 - 列表项）：

## 关键决策
- 对话中做出的重要决定、确定的方案选择
- 只保留已确定的决策，不保留讨论过程

## 用户偏好
- 用户表达的个人偏好、习惯、风格要求
- 包括明确说出的偏好和从上下文中可推断的偏好

## 待办事项
- 对话中提到但尚未完成的任务
- 不包括已完成的事项

## 事实信息
- 重要的事实、数据、项目信息、技术选型
- 不属于以上三类的其他重要信息

规则：
1. 每个类别如果没有内容，输出"（无）"，不要省略类别标题
2. 每条要点一句话，不超过 30 字
3. 删除寒暄、重复、已解决的讨论
4. 总字数控制在 300 字以内
5. 只输出结构化摘要，不要解释你的过程
""".trimIndent()

    /**
     * 增量压缩系统 prompt
     * 专门用于合并旧摘要 + 新对话
     */
    val INCREMENTAL_SUMMARIZE_SYSTEM = """
你是一个会话摘要合并助手。你需要将旧摘要和新对话合并为更新后的摘要。

输入：
- 旧摘要：已有的结构化摘要（包含关键决策/用户偏好/待办事项/事实信息四类）
- 新对话：最近产生的对话内容

输出：更新后的完整结构化摘要，格式与旧摘要相同。

合并规则：
1. 【关键决策】旧摘要中的决策保留，新对话中新增的决策也加入。如果新决策覆盖了旧决策，用新的替换旧的。
2. 【用户偏好】合并所有偏好。新对话中如果用户改变了偏好，用新的替换旧的。
3. 【待办事项】已完成的事项从待办中移除。新对话中新增的未完成事项加入。
4. 【事实信息】合并所有重要事实。过时的事实可以删除。
5. 每个类别如果没有内容，输出"（无）"。
6. 每条要点一句话，不超过 30 字。
7. 总字数控制在 300 字以内。
8. 只输出结构化摘要，不要解释你的过程。
""".trimIndent()

    /**
     * 保留旧 prompt 作为向后兼容（暂未删除的引用可过渡）
     * @deprecated 使用 STRUCTURED_SUMMARIZE_SYSTEM
     */
    @Deprecated("Use STRUCTURED_SUMMARIZE_SYSTEM instead", ReplaceWith("STRUCTURED_SUMMARIZE_SYSTEM"))
    val SUMMARIZE_SYSTEM = STRUCTURED_SUMMARIZE_SYSTEM

    /**
     * 构建首次压缩 prompt
     */
    fun buildFirstCompressPrompt(messages: List<MessageEntity>): String {
        val history = messages.joinToString("\n") {
            "${it.role.name}: ${it.content.take(500)}"
        }
        return "请压缩以下对话为结构化摘要：\n\n$history"
    }

    /**
     * 构建增量压缩 prompt
     */
    fun buildIncrementalPrompt(
        previousSummary: String,
        newMessages: List<MessageEntity>
    ): String {
        val newHistory = newMessages.joinToString("\n") {
            "${it.role.name}: ${it.content.take(500)}"
        }
        return """旧摘要：
$previousSummary

新对话：
$newHistory

请合并旧摘要和新对话，输出更新后的结构化摘要：""".trimIndent()
    }

    /**
     * 构建首次压缩 prompt（旧接口，向后兼容）
     * @deprecated 使用 buildFirstCompressPrompt
     */
    @Deprecated("Use buildFirstCompressPrompt instead", ReplaceWith("buildFirstCompressPrompt(messages)"))
    fun buildPrompt(messages: List<MessageEntity>): String {
        return buildFirstCompressPrompt(messages)
    }
}