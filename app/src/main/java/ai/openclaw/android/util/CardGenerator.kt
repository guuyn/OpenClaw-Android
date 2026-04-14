package ai.openclaw.android.util

import kotlinx.serialization.json.*

/**
 * CardGenerator — 兜底卡片生成工具
 *
 * 用途：当技能返回普通文本而非结构化 A2UI 卡片时，自动将文本包装为 InfoCard，
 * 确保所有回复都能被 A2UI 渲染引擎正确处理。
 *
 * 使用场景：
 * 1. 技能实现不完整，未返回卡片格式
 * 2. LLM 未按 prompt 要求输出卡片 JSON
 * 3. 工具调用返回纯文本错误信息
 */
object CardGenerator {

    private val jsonEncoder = Json { encodeDefaults = true }

    /**
     * 将普通文本/工具结果转为 InfoCard JSON
     *
     * @param text 原始文本内容
     * @param title 可选标题，不提供时不展示标题行
     * @return 带 [A2UI] 标签的完整卡片 JSON 字符串
     */
    fun generateInfoCard(text: String, title: String? = null): String {
        // 超过 100 字符时截取摘要，卡片更紧凑
        val summary = if (text.length > 100) text.take(100) + "..." else null

        val jsonObject = buildJsonObject {
            put("type", JsonPrimitive("info"))
            put("data", buildJsonObject {
                title?.let { put("title", JsonPrimitive(it)) }
                put("icon", JsonPrimitive("info"))
                put("content", JsonPrimitive(text))
                summary?.let { put("summary", JsonPrimitive(it)) }
            })
            put("actions", JsonArray(listOf(
                buildJsonObject {
                    put("label", JsonPrimitive("\uD83D\uDCCB 复制全文"))
                    put("action", JsonPrimitive("copy"))
                    put("style", JsonPrimitive("Secondary"))
                }
            )))
        }

        // Use Json.encodeToString for proper JSON output
        val jsonStr = jsonEncoder.encodeToString(JsonObject.serializer(), jsonObject)
        return "[A2UI]$jsonStr[/A2UI]"
    }

    /**
     * 检查回复中是否包含 [A2UI] 卡片标签
     * 如果不包含，自动包装为 InfoCard
     *
     * @param response LLM 或技能的原始回复
     * @param defaultTitle 兜底卡片标题（可选）
     * @return 如果已有卡片则原样返回，否则返回 InfoCard 包装后的结果
     */
    fun ensureCardInResponse(response: String, defaultTitle: String? = null): String {
        if (response.contains("[A2UI]") && response.contains("[/A2UI]")) {
            return response
        }
        return generateInfoCard(response, defaultTitle)
    }
}
