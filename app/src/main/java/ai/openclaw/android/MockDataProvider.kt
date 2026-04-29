package ai.openclaw.android

import ai.openclaw.android.domain.RichContent

/**
 * Mock 数据提供者，包含 5 种典型的测试场景
 */
object MockDataProvider {

    /**
     * 场景1: 纯文本回复
     */
    val plainTextMessage = ChatMessage(
        role = "assistant",
        content = "你好！我是 OpenClaw，有什么可以帮你的？这是一个纯文本回复的示例。"
    )

    /**
     * 场景2: 天气卡片（A2UICard 格式）
     */
    val weatherCardMessage = ChatMessage(
        role = "assistant",
        content = "西安的天气如下：\n" +
                "[A2UI]{\"type\":\"weather\",\"data\":{\"title\":\"西安天气\",\"city\":\"西安\"," +
                "\"condition\":\"多云\",\"temperature\":\"14\",\"feelsLike\":\"12\"," +
                "\"humidity\":\"45\",\"wind\":\"东南风 3级\"}," +
                "\"actions\":[{\"label\":\"设置提醒\",\"action\":\"set_reminder\",\"style\":\"Primary\"}]}" +
                "[/A2UI]"
    )

    /**
     * 场景3: A2UI 标准协议（v0.10 JSONL）
     */
    val standardProtocolMessage = ChatMessage(
        role = "assistant",
        content = "[A2UI]{\"version\":\"v0.10\"," +
                "\"createSurface\":{\"surfaceId\":\"test_001\",\"catalogId\":\"https://a2ui.org/specification/v0_10/standard_catalog.json\"}," +
                "\"updateComponents\":{\"surfaceId\":\"test_001\",\"components\":[" +
                "{\"id\":\"root\",\"component\":\"Column\",\"children\":{\"array\":[\"header\",\"body\"]}}," +
                "{\"id\":\"header\",\"component\":\"Text\",\"text\":\"测试卡片\",\"variant\":\"h4\"}," +
                "{\"id\":\"body\",\"component\":\"Text\",\"text\":\"这是一条标准协议测试数据\",\"variant\":\"body\"}" +
                "]}}" +
                "[/A2UI]"
    )

    /**
     * 场景4: 混合内容（文本 + 卡片）
     */
    val mixedContentMessage = ChatMessage(
        role = "assistant",
        content = "以下是搜索结果：\n" +
                "[A2UI]{\"type\":\"search_result\",\"data\":{\"title\":\"搜索结果\",\"query\":\"OpenClaw\"," +
                "\"items\":[{\"title\":\"OpenClaw 项目\",\"url\":\"https://example.com\",\"snippet\":\"AI assistant\",\"source\":\"GitHub\"}]}}" +
                "[/A2UI]\n\n需要我帮你做更多搜索吗？"
    )

    /**
     * 场景5: 错误/异常数据
     */
    val errorMessage = ChatMessage(
        role = "assistant",
        content = "[A2UI]{\"type\":\"error\",\"data\":{\"icon\":\"warning\",\"title\":\"操作失败\"," +
                "\"message\":\"无法连接到服务器\",\"suggestion\":\"请检查网络设置\"}}" +
                "[/A2UI]"
    )

    /**
     * 获取完整的测试场景消息列表
     */
    fun getAllScenarios(): List<ChatMessage> = listOf(
        ChatMessage(role = "user", content = "你好"),
        plainTextMessage,
        ChatMessage(role = "user", content = "西安天气怎么样"),
        weatherCardMessage,
        ChatMessage(role = "user", content = "测试 A2UI"),
        standardProtocolMessage,
        ChatMessage(role = "user", content = "混合内容展示"),
        mixedContentMessage,
        ChatMessage(role = "user", content = "出错看看"),
        errorMessage
    )
}