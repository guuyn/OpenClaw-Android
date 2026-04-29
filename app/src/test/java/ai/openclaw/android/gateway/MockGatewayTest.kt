package ai.openclaw.android.gateway

import ai.openclaw.android.agent.SessionEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * MockGateway 单元测试
 *
 * 验证所有 Mock 场景的预设响应行为：
 * - 文本回复
 * - A2UI 卡片
 * - 工具调用
 * - 错误模拟
 * - 超时模拟
 */
class MockGatewayTest {

    @Test
    fun `PlainText scenario emits tokens then complete`() = runBlocking {
        val gateway = MockGateway(MockScenario.PlainText)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("你好").toList()

        assertTrue("Should emit at least 2 events", events.size >= 2)
        assertTrue("First event should be Token", events[0] is SessionEvent.Token)
        assertTrue("Last event should be Complete", events.last() is SessionEvent.Complete)
        val complete = events.last() as SessionEvent.Complete
        assertTrue("Complete text should contain user input", complete.fullText.contains("你好"))
    }

    @Test
    fun `WeatherCard scenario emits A2UI weather card`() = runBlocking {
        val gateway = MockGateway(MockScenario.WeatherCard)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("天气").toList()

        val complete = events.last() as SessionEvent.Complete
        assertTrue("Should contain A2UI markup", complete.fullText.contains("[A2UI]"))
        assertTrue("Should contain weather data", complete.fullText.contains("天气"))
        assertTrue("Should contain v0.10 protocol", complete.fullText.contains("v0.10"))
    }

    @Test
    fun `SearchCard scenario emits A2UI search card`() = runBlocking {
        val gateway = MockGateway(MockScenario.SearchCard)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("搜索").toList()

        val complete = events.last() as SessionEvent.Complete
        assertTrue("Should contain A2UI markup", complete.fullText.contains("[A2UI]"))
        assertTrue("Should contain search results", complete.fullText.contains("搜索结果"))
    }

    @Test
    fun `ErrorCard scenario emits A2UI error card`() = runBlocking {
        val gateway = MockGateway(MockScenario.ErrorCard)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("出错").toList()

        val complete = events.last() as SessionEvent.Complete
        assertTrue("Should contain error card", complete.fullText.contains("操作失败"))
        assertTrue("Should contain A2UI markup", complete.fullText.contains("[A2UI]"))
    }

    @Test
    fun `MixedContent scenario emits tool execution events`() = runBlocking {
        val gateway = MockGateway(MockScenario.MixedContent)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("搜索 OpenClaw").toList()

        val hasToolExecuting = events.any { it is SessionEvent.ToolExecuting }
        val hasToolResult = events.any { it is SessionEvent.ToolResult }
        assertTrue("Should include tool execution", hasToolExecuting)
        assertTrue("Should include tool result", hasToolResult)
        assertTrue("Last event should be Complete", events.last() is SessionEvent.Complete)
    }

    @Test
    fun `Error scenario emits Error event`() = runBlocking {
        val gateway = MockGateway(MockScenario.Error)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("触发错误").toList()

        assertEquals("Should emit exactly 1 event", 1, events.size)
        assertTrue("Event should be Error", events[0] is SessionEvent.Error)
        val error = events[0] as SessionEvent.Error
        assertEquals("Error message should match", "模拟错误：网关连接失败", error.message)
    }

    @Test
    fun `Timeout scenario emits tokens then Error`() = runBlocking {
        val gateway = MockGateway(MockScenario.Timeout)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("超时测试").toList()

        assertTrue("Should emit multiple events", events.size >= 2)
        val hasTokens = events.any { it is SessionEvent.Token }
        val hasError = events.any { it is SessionEvent.Error }
        assertTrue("Should include tokens before timeout", hasTokens)
        assertTrue("Should end with error", hasError)
        val lastEvent = events.last()
        assertTrue("Last event should be Error", lastEvent is SessionEvent.Error)
    }

    @Test
    fun `JsonlA2UI scenario emits JSONL format A2UI`() = runBlocking {
        val gateway = MockGateway(MockScenario.JsonlA2UI)
        gateway.responseDelayMs = 0

        val events = gateway.sendMessage("JSONL 测试").toList()

        val complete = events.last() as SessionEvent.Complete
        assertTrue("Should contain JSONL payload", complete.fullText.contains("[A2UI]"))
        assertTrue("Should contain v0.10 protocol", complete.fullText.contains("v0.10"))
    }

    @Test
    fun `isReady always returns true`() {
        val gateway = MockGateway(MockScenario.PlainText)
        assertTrue("MockGateway should always be ready", gateway.isReady())
    }

    @Test
    fun `setScenario switches behavior at runtime`() = runBlocking {
        val gateway = MockGateway(MockScenario.PlainText)
        gateway.responseDelayMs = 0

        // Switch to error scenario
        gateway.setScenario(MockScenario.Error)

        val events = gateway.sendMessage("测试切换").toList()

        assertEquals("Should emit exactly 1 error event", 1, events.size)
        assertTrue("Event should be Error", events[0] is SessionEvent.Error)
    }

    @Test
    fun `responseDelayMs controls latency`() = runBlocking {
        val gateway = MockGateway(MockScenario.PlainText)
        gateway.responseDelayMs = 50

        val start = System.currentTimeMillis()
        gateway.sendMessage("延迟测试").toList()
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Should have delayed at least 40ms (allowing for jitter)", elapsed >= 40)
    }

    @Test
    fun `A2UI weather card contains required fields`() {
        val card = MockGateway.A2UI_WEATHER_CARD
        assertTrue("Should contain surface definition", card.contains("createSurface"))
        assertTrue("Should contain components", card.contains("updateComponents"))
        assertTrue("Should contain weather temp", card.contains("14"))
        assertTrue("Should contain humidity", card.contains("45"))
    }

    @Test
    fun `A2UI search card contains required fields`() {
        val card = MockGateway.A2UI_SEARCH_CARD
        assertTrue("Should contain surface definition", card.contains("createSurface"))
        assertTrue("Should contain search results", card.contains("搜索结果"))
    }

    @Test
    fun `A2UI error card contains required fields`() {
        val card = MockGateway.A2UI_ERROR_CARD
        assertTrue("Should contain error title", card.contains("操作失败"))
        assertTrue("Should contain retry button", card.contains("重试"))
    }
}
