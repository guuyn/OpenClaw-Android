package ai.openclaw.android.test

import ai.openclaw.android.ChatMessage
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.domain.AgentResponse
import ai.openclaw.android.domain.ResponseType
import ai.openclaw.android.domain.RichContent
import ai.openclaw.android.gateway.MockGateway
import ai.openclaw.android.gateway.MockScenario
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.ui.A2UICard
import ai.openclaw.android.ui.CardAction
import ai.openclaw.android.ui.ButtonStyle
import ai.openclaw.android.ui.MessageSegment
import java.util.UUID

/**
 * 测试数据工厂
 *
 * 提供可复用的测试数据，避免在每个测试中重复构建对象。
 * 所有数据均为确定性（无随机性），确保测试可重复。
 */
object TestDataFactory {

    // ==================== ChatMessage ====================

    fun createUserMessage(
        id: String = "user_$counter",
        content: String = "你好",
        images: List<ImageContent>? = null
    ): ChatMessage = ChatMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = 1000L + counter * 100,
        images = images
    )

    fun createAssistantMessage(
        id: String = "ai_$counter",
        content: String = "你好！有什么可以帮你的？"
    ): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = 2000L + counter * 100
    )

    fun createA2UIMessage(
        id: String = "a2ui_$counter",
        a2uiContent: String = "[A2UI]{\"type\":\"weather\",\"data\":{\"title\":\"天气\",\"city\":\"西安\",\"temperature\":\"20\"}}[/A2UI]"
    ): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = a2uiContent,
        timestamp = 3000L + counter * 100
    )

    fun createMixedMessage(
        id: String = "mixed_$counter"
    ): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = "这是文本部分\n[A2UI]{\"type\":\"info\",\"data\":{\"title\":\"信息\",\"content\":\"详情\"}}[/A2UI]\n这是后续文本",
        timestamp = 4000L + counter * 100
    )

    fun createEmptyAssistantMessage(id: String = "empty_$counter"): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = 5000L + counter * 100
    )

    fun createErrorMessage(id: String = "error_$counter"): ChatMessage = ChatMessage(
        id = id,
        role = "assistant",
        content = "错误: 连接失败",
        timestamp = 6000L + counter * 100
    )

    // ==================== SessionEvent ====================

    fun tokenEvent(text: String = "token"): SessionEvent.Token = SessionEvent.Token(text)

    fun completeEvent(fullText: String = "complete response"): SessionEvent.Complete =
        SessionEvent.Complete(fullText)

    fun errorEvent(message: String = "test error"): SessionEvent.Error =
        SessionEvent.Error(message)

    fun toolExecutingEvent(name: String = "search_web"): SessionEvent.ToolExecuting =
        SessionEvent.ToolExecuting(name)

    fun toolResultEvent(name: String = "search_web", result: String = "{}"): SessionEvent.ToolResult =
        SessionEvent.ToolResult(name, result)

    fun reflectionStartEvent(role: String = "thought"): SessionEvent.ReflectionStart =
        SessionEvent.ReflectionStart(role)

    fun reflectionCompleteEvent(thought: String = "refined answer"): SessionEvent.ReflectionComplete =
        SessionEvent.ReflectionComplete(thought)

    fun streamingResponse(vararg tokens: String): List<SessionEvent> =
        tokens.map { SessionEvent.Token(it) } + SessionEvent.Complete(tokens.joinToString(""))

    // ==================== AgentResponse ====================

    fun textResponse(
        fallbackText: String = "这是纯文本回复",
        voiceText: String = "这是纯文本回复"
    ): AgentResponse = AgentResponse(
        type = ResponseType.TEXT,
        voiceText = voiceText,
        richContent = null,
        fallbackText = fallbackText
    )

    fun voiceResponse(
        voiceText: String = "语音回复内容",
        fallbackText: String = "这是完整的文本回复"
    ): AgentResponse = AgentResponse(
        type = ResponseType.VOICE,
        voiceText = voiceText,
        richContent = null,
        fallbackText = fallbackText
    )

    fun bothResponse(
        voiceText: String = "语音播报",
        fallbackText: String = "完整回复",
        richContent: RichContent? = null
    ): AgentResponse = AgentResponse(
        type = ResponseType.BOTH,
        voiceText = voiceText,
        richContent = richContent,
        fallbackText = fallbackText
    )

    fun richTextResponse(
        richContent: RichContent = RichContent.ListCard("搜索结果", listOf("结果1", "结果2")),
        fallbackText: String = "搜索结果如下：结果1、结果2"
    ): AgentResponse = AgentResponse(
        type = ResponseType.TEXT,
        voiceText = fallbackText.take(60),
        richContent = richContent,
        fallbackText = fallbackText
    )

    // ==================== CardAction ====================

    fun cardAction(
        label: String = "确认",
        action: String = "confirm",
        style: ButtonStyle = ButtonStyle.Primary
    ): CardAction = CardAction(
        label = label,
        action = action,
        style = style
    )

    fun setReminderAction(): CardAction = cardAction("设置提醒", "set_reminder", ButtonStyle.Primary)
    fun retryAction(): CardAction = cardAction("重试", "retry", ButtonStyle.Primary)
    fun resendAction(): CardAction = cardAction("重发", "resend", ButtonStyle.Primary)
    fun cancelAction(): CardAction = cardAction("取消", "cancel", ButtonStyle.Secondary)
    fun confirmAction(): CardAction = cardAction("确认", "confirm", ButtonStyle.Primary)
    fun customAction(label: String = "自定义"): CardAction = cardAction(label, "custom_action", ButtonStyle.Secondary)
    fun emptyLabelAction(action: String = "unknown"): CardAction = cardAction("", action, ButtonStyle.Secondary)

    // ==================== ImageContent ====================

    fun imageContent(
        base64: String = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
        mediaType: String = "image/jpeg",
        description: String? = "测试图片"
    ): ImageContent = ImageContent(
        base64 = base64,
        mediaType = mediaType,
        description = description
    )

    // ==================== Message List Scenarios ====================

    fun conversationWithUserMessages(count: Int = 3): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until count) {
            messages.add(createUserMessage(id = "u$i", content = "用户消息 $i"))
            messages.add(createAssistantMessage(id = "a$i", content = "AI回复 $i"))
        }
        return messages
    }

    fun conversationWithA2UI(): List<ChatMessage> = listOf(
        createUserMessage(id = "u1", content = "天气怎么样"),
        createA2UIMessage(
            id = "a1",
            a2uiContent = MockGateway.A2UI_WEATHER_CARD
        )
    )

    fun conversationWithToolCalls(): List<ChatMessage> = listOf(
        createUserMessage(id = "u1", content = "搜索一下"),
        createAssistantMessage(
            id = "a1",
            content = "正在搜索...\n[调用工具: search_web...]\n搜索完成！"
        )
    )

    // ==================== RichContent ====================

    fun listCard(title: String = "列表", vararg items: String): RichContent.ListCard =
        RichContent.ListCard(title, items.toList())

    fun infoCard(title: String = "信息", body: String = "详情内容"): RichContent.InfoCard =
        RichContent.InfoCard(title, body)

    fun codeBlock(language: String = "kotlin", code: String = "fun main() {}"): RichContent.CodeBlock =
        RichContent.CodeBlock(language, code)

    // ==================== JSON Response Text (for parseAgentResponse) ====================

    fun jsonAgentResponseText(
        type: String = "TEXT",
        voiceText: String? = null,
        fallbackText: String = "完整回复",
        richContent: Map<String, Any>? = null
    ): String = buildString {
        append("这是一条AI回复\n\n")
        append("{")
        append("\"type\":\"$type\"")
        if (voiceText != null) append(",\"voice_text\":\"$voiceText\"")
        append(",\"fallback_text\":\"$fallbackText\"")
        if (richContent != null) {
            append(",\"rich_content\":{")
            append("\"type\":\"${richContent["type"]}\"")
            val data = richContent["data"] as? Map<*, *>
            if (data != null) {
                append(",\"data\":{")
                data.entries.joinTo(this, ",") { "\"${it.key}\":\"${it.value}\"" }
                append("}")
            }
            append("}")
        }
        append("}")
    }

    // ==================== A2UI Content Strings ====================

    fun weatherA2UI(): String =
        """[A2UI]{"type":"weather","data":{"title":"西安天气","city":"西安","condition":"晴","temperature":"22"},"actions":[{"label":"设置提醒","action":"set_reminder","style":"Primary"}]}[/A2UI]"""

    fun searchA2UI(): String =
        """[A2UI]{"type":"search_result","data":{"title":"搜索结果","query":"OpenClaw","items":[]},"actions":[]}[/A2UI]"""

    fun standardProtocolA2UI(surfaceId: String = "test_001"): String =
        """[A2UI]{"version":"v0.10","createSurface":{"surfaceId":"$surfaceId","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"},"updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"root","component":"Column","children":{"array":["text"]}},{"id":"text","component":"Text","text":"测试内容","variant":"body"}]}}[/A2UI]"""

    fun errorA2UI(): String =
        """[A2UI]{"type":"error","data":{"icon":"warning","title":"操作失败","message":"连接超时"},"actions":[]}[/A2UI]"""

    // ==================== Counter for unique IDs ====================

    private var counter = 0
        get() = field++
}
