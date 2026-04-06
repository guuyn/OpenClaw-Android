package ai.openclaw.android.domain.memory

import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.util.LocalLLMClient
import ai.openclaw.android.util.Message
import ai.openclaw.android.util.MemoryExtractionPrompts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement

class MemoryExtractor(private val llmClient: LocalLLMClient) {
    
    suspend fun extractFromConversation(
        messages: List<MessageEntity>
    ): Result<List<MemoryEntity>> = runCatching {
        if (messages.isEmpty()) return@runCatching emptyList()
        
        val conversation = messages.takeLast(10).joinToString("\n") {
            "${it.role}: ${it.content}"
        }
        
        val prompt = "${MemoryExtractionPrompts.SYSTEM_PROMPT}\n\n对话：\n$conversation"
        
        val response = llmClient.chat(
            listOf(Message(role = "user", content = prompt))
        ).getOrThrow()
        
        parseMemories(response.choices.first().message.content ?: "")
    }
    
    suspend fun extractFromUserInput(
        content: String,
        type: MemoryType? = null
    ): Result<MemoryEntity> = runCatching {
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
    
    private fun parseMemories(json: String): List<MemoryEntity> {
        return try {
            // 提取 JSON 部分
            val jsonStart = json.indexOf("{")
            val jsonEnd = json.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
            
            val cleanJson = json.substring(jsonStart, jsonEnd)
            val jsonObject = Json.parseToJsonElement(cleanJson).jsonObject
            val memoriesArray = jsonObject["memories"]?.jsonArray ?: return emptyList()
            
            memoriesArray.map { memoryElement ->
                val memoryObj = memoryElement.jsonObject
                MemoryEntity(
                    content = memoryObj["content"]?.jsonPrimitive?.content ?: "",
                    memoryType = MemoryType.valueOf(memoryObj["type"]?.jsonPrimitive?.content ?: "FACT"),
                    priority = memoryObj["priority"]?.jsonPrimitive?.int ?: 3,
                    source = "auto",
                    tags = memoryObj["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    createdAt = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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
}