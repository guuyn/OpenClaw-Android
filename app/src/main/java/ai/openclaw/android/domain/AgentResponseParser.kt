package ai.openclaw.android.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AgentResponseParser — 将 LLM 响应文本解析为结构化的 AgentResponse。
 *
 * 使用 kotlinx-serialization-json（非 org.json），确保在 JVM 单元测试中可用。
 *
 * 解析策略：
 * 1. 在文本中查找第一个 '{' 和最后一个 '}'
 * 2. 提取中间部分作为 JSON 字符串
 * 3. 解析 type, voice_text, rich_content, fallback_text 字段
 * 4. 解析失败或无 JSON 时回退为 TEXT 类型
 */
class AgentResponseParser {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Parse LLM response text into an AgentResponse.
     *
     * Attempts to find a JSON object in the response text. If parsing fails
     * or no JSON is found, falls back to a TEXT response with the full text
     * as fallback_text.
     */
    fun parse(text: String): AgentResponse {
        // Try to extract JSON from the response
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            // No JSON found — treat as plain text
            return AgentResponse(
                type = ResponseType.TEXT,
                voiceText = text.take(60),
                richContent = null,
                fallbackText = text
            )
        }

        return try {
            val jsonStr = text.substring(jsonStart, jsonEnd + 1)
            val element = json.parseToJsonElement(jsonStr).jsonObject

            val typeStr = element["type"]?.jsonPrimitive?.content ?: "TEXT"
            val type = when (typeStr.uppercase()) {
                "VOICE" -> ResponseType.VOICE
                "BOTH" -> ResponseType.BOTH
                else -> ResponseType.TEXT
            }

            val voiceText = element["voice_text"]?.let { voiceElement ->
                if (voiceElement is JsonPrimitive) {
                    val content = voiceElement.content
                    // JSON null becomes string "null"; filter it out
                    if (content.isNotEmpty() && content != "null") content else null
                } else null
            }
            val fallbackText = element["fallback_text"]?.jsonPrimitive?.content ?: text

            // Parse rich_content if present
            val richContent = element["rich_content"]?.let { rcElement ->
                if (rcElement is JsonObject) {
                    val rcType = rcElement["type"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                    val rcData = rcElement["data"]?.let { dataElement ->
                        if (dataElement is JsonObject) {
                            flattenJsonObject(dataElement)
                        } else null
                    }
                    RichContent.fromJson(rcType, rcData)
                } else null
            }

            AgentResponse(
                type = type,
                voiceText = voiceText,
                richContent = richContent,
                fallbackText = fallbackText.ifBlank { text }
            )
        } catch (_: Exception) {
            AgentResponse(
                type = ResponseType.TEXT,
                voiceText = text.take(60),
                richContent = null,
                fallbackText = text
            )
        }
    }

    /**
     * Flatten a JsonObject into a Map<String, Any>.
     * Handles nested objects, arrays, and primitives.
     */
    private fun flattenJsonObject(obj: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, value) in obj) {
            map[key] = when (value) {
                is JsonPrimitive -> valueToJsonPrimitive(value)
                is JsonObject -> flattenJsonObject(value)
                is kotlinx.serialization.json.JsonArray -> value.map {
                    when (it) {
                        is JsonPrimitive -> valueToJsonPrimitive(it)
                        is JsonObject -> flattenJsonObject(it)
                        else -> it.toString()
                    }
                }
                else -> value.toString()
            }
        }
        return map
    }

    private fun valueToJsonPrimitive(value: JsonPrimitive): Any {
        if (value.isString) return value.content
        val content = value.content
        // Try to parse as number
        return content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    }
}
