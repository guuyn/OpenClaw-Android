package ai.openclaw.android.util

import ai.openclaw.android.data.model.MessageEntity

object CompressionPrompts {
    val SUMMARIZE_SYSTEM = """
你是一个会话摘要助手。请将以下对话历史压缩成简洁的摘要。

要求：
1. 保留关键决策和结论
2. 保留用户偏好和重要信息
3. 保留未完成的任务或待办事项
4. 使用要点列表格式
5. 控制在 200 字以内

输出格式：
## 关键信息
- ...
## 用户偏好
- ...
## 待办事项
- ...
""".trimIndent()

    fun buildPrompt(messages: List<MessageEntity>): String {
        val history = messages.joinToString("\n") { 
            "${it.role.name}: ${it.content}" 
        }
        return "请压缩以下对话：\n\n$history"
    }
}