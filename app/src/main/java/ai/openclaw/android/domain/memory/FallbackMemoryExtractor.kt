package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MessageEntity

class FallbackMemoryExtractor : MemoryExtractorInterface {

    private val patterns = mapOf(
        MemoryType.PREFERENCE to listOf("喜欢", "偏好", "不要", "讨厌", "最爱", "偏好是"),
        MemoryType.FACT to listOf("我叫", "我的邮箱", "我的手机", "我住", "我是", "的电话是"),
        MemoryType.TASK to listOf("明天要", "记得", "别忘了", "待办", "提醒我", "下周"),
        MemoryType.DECISION to listOf("决定", "选择", "方案", "就用", "改为"),
        MemoryType.PROJECT to listOf("项目路径", "项目名", "使用.*框架", "仓库地址")
    )

    override suspend fun extractFromConversation(messages: List<MessageEntity>): Result<List<MemoryEntity>> = runCatching {
        if (messages.isEmpty()) return@runCatching emptyList()

        val userMessages = messages.filter { it.role.name == "USER" }.takeLast(10)
        val memories = mutableListOf<MemoryEntity>()

        for (msg in userMessages) {
            val content = msg.content
            for ((type, keywords) in patterns) {
                if (keywords.any { content.contains(it) }) {
                    memories.add(MemoryEntity(
                        content = content.take(200),
                        memoryType = type,
                        priority = estimatePriority(content),
                        source = "auto_fallback",
                        tags = extractTags(content),
                        createdAt = System.currentTimeMillis(),
                        lastAccessedAt = System.currentTimeMillis()
                    ))
                    break
                }
            }
        }

        memories.take(3)
    }

    override suspend fun extractFromUserInput(content: String, type: MemoryType?): Result<MemoryEntity> = runCatching {
        val memoryType = type ?: classifyType(content)
        val priority = if (content.contains("重要") || content.contains("必须")) 5 else 3

        MemoryEntity(
            content = content,
            memoryType = memoryType,
            priority = priority,
            source = "manual",
            tags = extractTags(content),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
    }

    private fun classifyType(content: String): MemoryType {
        return when {
            content.contains("喜欢") || content.contains("偏好") -> MemoryType.PREFERENCE
            content.contains("明天") || content.contains("记得") -> MemoryType.TASK
            content.contains("决定") || content.contains("选择") -> MemoryType.DECISION
            content.contains("项目") || content.contains("路径") -> MemoryType.PROJECT
            else -> MemoryType.FACT
        }
    }

    private fun extractTags(content: String): List<String> {
        val tags = mutableListOf<String>()
        if (content.contains("项目")) tags.add("项目")
        if (content.contains("工作")) tags.add("工作")
        if (content.contains("个人")) tags.add("个人")
        return tags
    }

    private fun estimatePriority(content: String): Int = when {
        content.contains("重要") || content.contains("必须") || content.contains("紧急") -> 5
        content.contains("决定") || content.contains("选择") -> 4
        else -> 3
    }
}
