package ai.openclaw.android.gateway

import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.model.ImageContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mock 网关实现 — 返回预设的离线响应
 *
 * 用于测试模式，无需网络/API Key 即可验证：
 * - 消息发送与流式接收流程
 * - A2UI 卡片渲染
 * - Loading 状态
 * - 错误边界
 *
 * 通过 [MockScenario] 枚举切换不同测试场景。
 */
class MockGateway(
    private var scenario: MockScenario = MockScenario.PlainText
) : MessageGateway {

    /** 动态延迟，模拟网络耗时（毫秒），0 = 无延迟 */
    var responseDelayMs: Long = 800L

    /** 动态切换当前场景（运行时可由 ViewModel 或 UI 控制） */
    fun setScenario(newScenario: MockScenario) {
        scenario = newScenario
    }

    override fun sendMessage(text: String, images: List<ImageContent>?): Flow<SessionEvent> = flow {
        // 模拟网络延迟
        if (responseDelayMs > 0) {
            delay(responseDelayMs)
        }

        // 错误场景：直接抛出错误
        if (scenario == MockScenario.Error) {
            emit(SessionEvent.Error("模拟错误：网关连接失败"))
            return@flow
        }

        // 超时场景：只发几个 token 后报超时
        if (scenario == MockScenario.Timeout) {
            emit(SessionEvent.Token("正在处理"))
            delay(500)
            emit(SessionEvent.Token("你的"))
            delay(500)
            emit(SessionEvent.Token("请求..."))
            delay(2000)
            emit(SessionEvent.Error("请求超时：服务端响应时间过长"))
            return@flow
        }

        val events = scenario.buildEvents(text)
        for (event in events) {
            emit(event)
        }
    }

    override fun isReady(): Boolean = true

    companion object {
        /**
         * A2UI 标准协议 v0.10 — 天气卡片
         */
        const val A2UI_WEATHER_CARD = """[A2UI]{"version":"v0.10","createSurface":{"surfaceId":"weather_001","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"},"updateComponents":{"surfaceId":"weather_001","components":[{"id":"root","component":"Column","children":{"array":["header","temp","details","actions"]}},{"id":"header","component":"Text","text":"🌤️ 西安天气","variant":"h4"},{"id":"temp","component":"Text","text":"14°C 多云","variant":"h1"},{"id":"details","component":"Column","children":{"array":["humidity","wind","feels"]}},{"id":"humidity","component":"Text","text":"湿度: 45%","variant":"body"},{"id":"wind","component":"Text","text":"东南风 3级","variant":"body"},{"id":"feels","component":"Text","text":"体感温度: 12°C","variant":"body"},{"id":"actions","component":"Row","children":{"array":["btn_reminder","btn_detail"]}},{"id":"btn_reminder","component":"Button","text":"设置提醒","style":"Primary"},{"id":"btn_detail","component":"Button","text":"查看详情","style":"Secondary"}]}}[/A2UI]"""

        /**
         * A2UI 标准协议 v0.10 — 搜索结果卡片
         */
        const val A2UI_SEARCH_CARD = """[A2UI]{"version":"v0.10","createSurface":{"surfaceId":"search_001","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"},"updateComponents":{"surfaceId":"search_001","components":[{"id":"root","component":"Column","children":{"array":["header","result1","result2"]}},{"id":"header","component":"Text","text":"🔍 搜索结果","variant":"h4"},{"id":"result1","component":"Column","children":{"array":["r1_title","r1_snippet"]}},{"id":"r1_title","component":"Text","text":"OpenClaw 项目","variant":"h5"},{"id":"r1_snippet","component":"Text","text":"AI assistant framework for Android","variant":"body"},{"id":"result2","component":"Column","children":{"array":["r2_title","r2_snippet"]}},{"id":"r2_title","component":"Text","text":"OpenClaw GitHub","variant":"h5"},{"id":"r2_snippet","component":"Text","text":"GitHub repository for OpenClaw","variant":"body"}]}}[/A2UI]"""

        /**
         * A2UI 标准协议 v0.10 — 错误提示卡片
         */
        const val A2UI_ERROR_CARD = """[A2UI]{"version":"v0.10","createSurface":{"surfaceId":"error_001","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"},"updateComponents":{"surfaceId":"error_001","components":[{"id":"root","component":"Column","children":{"array":["icon","title","message","action"]}},{"id":"icon","component":"Text","text":"⚠️","variant":"h1"},{"id":"title","component":"Text","text":"操作失败","variant":"h4"},{"id":"message","component":"Text","text":"无法连接到服务器","variant":"body"},{"id":"action","component":"Button","text":"重试","style":"Danger"}]}}[/A2UI]"""
    }
}

/**
 * Mock 场景枚举
 *
 * 每个场景定义一组 SessionEvent 序列，模拟不同的 AI 响应模式。
 */
enum class MockScenario {
    /** 纯文本回复 — 基础流程验证 */
    PlainText,

    /** 天气卡片（A2UI v0.10）— 富卡片渲染验证 */
    WeatherCard,

    /** 搜索结果卡片（A2UI v0.10）— 列表类卡片验证 */
    SearchCard,

    /** 错误卡片（A2UI v0.10）— 错误边界验证 */
    ErrorCard,

    /** 混合内容（文本 + 工具调用 + A2UI）— 完整流程验证 */
    MixedContent,

    /** 网络错误 — 错误边界与重试流程验证 */
    Error,

    /** 超时场景 — loading 状态与超时处理验证 */
    Timeout,

    /** JSONL 格式 A2UI — 标准协议验证 */
    JsonlA2UI;

    /**
     * 构建该场景对应的 SessionEvent 序列
     */
    fun buildEvents(userText: String): List<SessionEvent> = when (this) {
        PlainText -> plainTextEvents(userText)
        WeatherCard -> weatherCardEvents()
        SearchCard -> searchCardEvents()
        ErrorCard -> errorCardEvents()
        MixedContent -> mixedContentEvents(userText)
        Error -> errorEvents()
        Timeout -> emptyList() // 超时场景在 flow 中直接处理
        JsonlA2UI -> jsonlA2UIEvents()
    }

    private fun plainTextEvents(userText: String): List<SessionEvent> = listOf(
        SessionEvent.Token("你好"),
        SessionEvent.Token("！"),
        SessionEvent.Token("我是"),
        SessionEvent.Token(" OpenClaw"),
        SessionEvent.Token("，"),
        SessionEvent.Token("你的 AI 助手"),
        SessionEvent.Token("。"),
        SessionEvent.Token("\n\n"),
        SessionEvent.Token("你刚才说"),
        SessionEvent.Token("：" + userText.take(30)),
        SessionEvent.Complete("你好！我是 OpenClaw，你的 AI 助手。\n\n你刚才说：${userText.take(30)}")
    )

    private fun weatherCardEvents(): List<SessionEvent> {
        val a2ui = MockGateway.A2UI_WEATHER_CARD
        return listOf(
            SessionEvent.Token("西安的天气如下：\n\n"),
            SessionEvent.Token(a2ui),
            SessionEvent.Complete("西安的天气如下：\n\n$a2ui")
        )
    }

    private fun searchCardEvents(): List<SessionEvent> {
        val a2ui = MockGateway.A2UI_SEARCH_CARD
        return listOf(
            SessionEvent.Token("以下是搜索结果：\n\n"),
            SessionEvent.Token(a2ui),
            SessionEvent.Token("\n\n需要我帮你做更多搜索吗？"),
            SessionEvent.Complete("以下是搜索结果：\n\n$a2ui\n\n需要我帮你做更多搜索吗？")
        )
    }

    private fun errorCardEvents(): List<SessionEvent> {
        val a2ui = MockGateway.A2UI_ERROR_CARD
        return listOf(
            SessionEvent.Token(a2ui),
            SessionEvent.Complete(a2ui)
        )
    }

    private fun mixedContentEvents(userText: String): List<SessionEvent> {
        val a2ui = MockGateway.A2UI_SEARCH_CARD
        return listOf(
            SessionEvent.Token("好的，"),
            SessionEvent.Token("让我来搜索"),
            SessionEvent.Token("「${userText.take(20)}」"),
            SessionEvent.Token("...\n\n"),
            SessionEvent.ToolExecuting("search_web"),
            SessionEvent.ToolResult("search_web", """{"results": 2}"""),
            SessionEvent.Token("搜索完成！找到以下结果：\n\n"),
            SessionEvent.Token(a2ui),
            SessionEvent.Token("\n\n"),
            SessionEvent.Token("需要我帮你做更多操作吗？"),
            SessionEvent.Complete("好的，让我来搜索「${userText.take(20)}」...\n\n搜索完成！找到以下结果：\n\n$a2ui\n\n需要我帮你做更多操作吗？")
        )
    }

    private fun errorEvents(): List<SessionEvent> = listOf(
        SessionEvent.Error("模拟错误：网关连接失败")
    )

    private fun jsonlA2UIEvents(): List<SessionEvent> {
        // JSONL 格式 — 每行一个 A2UI 操作
        val jsonlLines = listOf(
            """{"version":"v0.10","createSurface":{"surfaceId":"jsonl_test","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"}}""",
            """{"updateComponents":{"surfaceId":"jsonl_test","components":[{"id":"root","component":"Column","children":{"array":["title","desc","btn"]}},{"id":"title","component":"Text","text":"JSONL 协议测试","variant":"h4"},{"id":"desc","component":"Text","text":"这是一个使用 JSONL 格式传输的 A2UI 卡片","variant":"body"},{"id":"btn","component":"Button","text":"确认","style":"Primary"}]}}}"""
        )
        val jsonlPayload = "[A2UI]" + jsonlLines.joinToString("\n") + "[/A2UI]"
        return listOf(
            SessionEvent.Token("这是一条 JSONL 格式的 A2UI 响应：\n\n"),
            SessionEvent.Token(jsonlPayload),
            SessionEvent.Complete("这是一条 JSONL 格式的 A2UI 响应：\n\n$jsonlPayload")
        )
    }
}
