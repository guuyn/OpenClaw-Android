package ai.openclaw.android.domain

import ai.openclaw.android.test.TestDataFactory
import org.junit.Assert.*
import org.junit.Test

/**
 * ResponseRouter 单元测试
 *
 * 验证响应路由决策树覆盖所有分支：
 * - TEXT: 有屏幕+富内容 → RichText / 有屏幕 → PlainText / 无屏幕+TTS → Voice / 最后 → PlainText
 * - VOICE: 有TTS+未静音 → Voice / 无TTS → PlainText
 * - BOTH: 有屏幕+TTS → Mixed / 有屏幕无TTS → Mixed(null,rich) / 无屏幕+TTS → Voice / 最后 → PlainText
 */
class ResponseRouterTest {

    // ==================== Helper ====================

    private fun fullDevice(
        hasScreen: Boolean = true,
        hasTts: Boolean = true,
        hasRichText: Boolean = true,
        isAudioMuted: Boolean = false,
        hasStt: Boolean = true,
        hasNetwork: Boolean = true,
        isInteractive: Boolean = true
    ): DeviceCapabilities = DeviceCapabilities(
        hasScreen = hasScreen,
        hasTts = hasTts,
        hasStt = hasStt,
        hasRichText = hasRichText,
        hasNetwork = hasNetwork,
        isInteractive = isInteractive,
        isAudioMuted = isAudioMuted
    )

    // ==================== TEXT Routing ====================

    @Test
    fun `TEXT with screen + richContent + hasRichText → RichText`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasRichText = true))
        val response = TestDataFactory.richTextResponse(
            richContent = TestDataFactory.listCard("结果", "A", "B"),
            fallbackText = "完整回复"
        )

        val result = router.route(response)

        assertTrue("Should be RichText", result is Deliverable.RichText)
        val richText = result as Deliverable.RichText
        val listCard = richText.content as RichContent.ListCard
        assertEquals("结果", listCard.title)
    }

    @Test
    fun `TEXT with screen but no richContent → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = true))
        val response = TestDataFactory.textResponse(fallbackText = "纯文本")

        val result = router.route(response)

        assertTrue("Should be PlainText", result is Deliverable.PlainText)
        assertEquals("纯文本", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `TEXT with screen but richContent null → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasRichText = true))
        val response = AgentResponse(
            type = ResponseType.TEXT,
            voiceText = null,
            richContent = null,
            fallbackText = "回复"
        )

        val result = router.route(response)

        assertTrue("Should be PlainText", result is Deliverable.PlainText)
        assertEquals("回复", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `TEXT with screen but no richText capability → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasRichText = false))
        val response = TestDataFactory.richTextResponse(
            richContent = TestDataFactory.infoCard("信息", "详情")
        )

        val result = router.route(response)

        assertTrue("Should be PlainText without rich text capability", result is Deliverable.PlainText)
    }

    @Test
    fun `TEXT with no screen + TTS available → Voice`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = true, isAudioMuted = false))
        val response = TestDataFactory.textResponse(
            voiceText = "语音播报",
            fallbackText = "完整回复"
        )

        val result = router.route(response)

        assertTrue("Should be Voice when no screen but TTS available", result is Deliverable.Voice)
        // Router uses voiceText first (preferred for TTS), falls back to fallbackText
        assertEquals("语音播报", (result as Deliverable.Voice).text)
    }

    @Test
    fun `TEXT with no screen + no TTS → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = false))
        val response = TestDataFactory.textResponse(fallbackText = "最后手段")

        val result = router.route(response)

        assertTrue("Should be PlainText as last resort", result is Deliverable.PlainText)
        assertEquals("最后手段", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `TEXT with no screen + TTS but audio muted → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = true, isAudioMuted = true))
        val response = TestDataFactory.textResponse(fallbackText = "静音状态")

        val result = router.route(response)

        assertTrue("Should be PlainText when audio is muted", result is Deliverable.PlainText)
        assertEquals("静音状态", (result as Deliverable.PlainText).text)
    }

    // ==================== VOICE Routing ====================

    @Test
    fun `VOICE with TTS + not muted → Voice`() {
        val router = ResponseRouter(fullDevice(hasTts = true, isAudioMuted = false))
        val response = TestDataFactory.voiceResponse(
            voiceText = "朗读内容",
            fallbackText = "文本回退"
        )

        val result = router.route(response)

        assertTrue("Should be Voice", result is Deliverable.Voice)
        assertEquals("朗读内容", (result as Deliverable.Voice).text)
    }

    @Test
    fun `VOICE with TTS but audio muted → PlainText`() {
        val router = ResponseRouter(fullDevice(hasTts = true, isAudioMuted = true))
        val response = TestDataFactory.voiceResponse(voiceText = "静音", fallbackText = "回退文本")

        val result = router.route(response)

        assertTrue("Should be PlainText when muted", result is Deliverable.PlainText)
        assertEquals("回退文本", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `VOICE without TTS → PlainText`() {
        val router = ResponseRouter(fullDevice(hasTts = false))
        val response = TestDataFactory.voiceResponse(fallbackText = "无TTS")

        val result = router.route(response)

        assertTrue("Should be PlainText without TTS", result is Deliverable.PlainText)
        assertEquals("无TTS", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `VOICE with null voiceText falls back to fallbackText`() {
        val router = ResponseRouter(fullDevice(hasTts = true, isAudioMuted = false))
        val response = AgentResponse(
            type = ResponseType.VOICE,
            voiceText = null,
            richContent = null,
            fallbackText = "回退文本"
        )

        val result = router.route(response)

        assertTrue(result is Deliverable.Voice)
        assertEquals("回退文本", (result as Deliverable.Voice).text)
    }

    // ==================== BOTH Routing ====================

    @Test
    fun `BOTH with screen + TTS + not muted → Mixed with voice and rich`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasTts = true, isAudioMuted = false))
        val richContent = TestDataFactory.infoCard("信息", "详情")
        val response = TestDataFactory.bothResponse(
            voiceText = "语音",
            fallbackText = "完整",
            richContent = richContent
        )

        val result = router.route(response)

        assertTrue("Should be Mixed", result is Deliverable.Mixed)
        val mixed = result as Deliverable.Mixed
        // Note: routeBoth passes richContent directly, not voiceText
        assertEquals(richContent, mixed.rich)
    }

    @Test
    fun `BOTH with screen but no TTS → Mixed with null voice`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasTts = false))
        val richContent = TestDataFactory.listCard("列表")
        val response = TestDataFactory.bothResponse(
            voiceText = "语音",
            richContent = richContent
        )

        val result = router.route(response)

        assertTrue("Should be Mixed with null voice", result is Deliverable.Mixed)
        val mixed = result as Deliverable.Mixed
        assertNull("voice should be null when no TTS", mixed.voice)
    }

    @Test
    fun `BOTH with screen + TTS + muted → Mixed with null voice`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasTts = true, isAudioMuted = true))
        val richContent = TestDataFactory.codeBlock("kotlin", "fun main(){}")
        val response = TestDataFactory.bothResponse(
            voiceText = "语音",
            richContent = richContent
        )

        val result = router.route(response)

        assertTrue("Should be Mixed", result is Deliverable.Mixed)
        val mixed = result as Deliverable.Mixed
        assertNull("voice should be null when muted", mixed.voice)
        assertEquals(richContent, mixed.rich)
    }

    @Test
    fun `BOTH no screen + TTS + not muted → Voice`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = true, isAudioMuted = false))
        val response = TestDataFactory.bothResponse(
            voiceText = "纯语音",
            fallbackText = "回退"
        )

        val result = router.route(response)

        assertTrue("Should be Voice without screen", result is Deliverable.Voice)
        assertEquals("纯语音", (result as Deliverable.Voice).text)
    }

    @Test
    fun `BOTH no screen + no TTS → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = false))
        val response = TestDataFactory.bothResponse(fallbackText = "最后手段")

        val result = router.route(response)

        assertTrue("Should be PlainText as last resort", result is Deliverable.PlainText)
        assertEquals("最后手段", (result as Deliverable.PlainText).text)
    }

    @Test
    fun `BOTH no screen + TTS + muted → PlainText`() {
        val router = ResponseRouter(fullDevice(hasScreen = false, hasTts = true, isAudioMuted = true))
        val response = TestDataFactory.bothResponse(fallbackText = "静音无屏幕")

        val result = router.route(response)

        assertTrue("Should be PlainText when muted and no screen", result is Deliverable.PlainText)
    }

    // ==================== RichContent types ====================

    @Test
    fun `RichContent ListCard is preserved in RichText deliverable`() {
        val router = ResponseRouter(fullDevice())
        val listCard = TestDataFactory.listCard("任务列表", "任务1", "任务2", "任务3")
        val response = TestDataFactory.richTextResponse(richContent = listCard)

        val result = router.route(response) as Deliverable.RichText

        val content = result.content as RichContent.ListCard
        assertEquals("任务列表", content.title)
        assertEquals(3, content.items.size)
        assertEquals("任务1", content.items[0])
    }

    @Test
    fun `RichContent InfoCard is preserved in RichText deliverable`() {
        val router = ResponseRouter(fullDevice())
        val infoCard = TestDataFactory.infoCard("标题", "正文内容")
        val response = TestDataFactory.richTextResponse(richContent = infoCard)

        val result = router.route(response) as Deliverable.RichText

        val content = result.content as RichContent.InfoCard
        assertEquals("标题", content.title)
        assertEquals("正文内容", content.body)
    }

    @Test
    fun `RichContent CodeBlock is preserved in RichText deliverable`() {
        val router = ResponseRouter(fullDevice())
        val codeBlock = TestDataFactory.codeBlock("java", "System.out.println(\"Hello\");")
        val response = TestDataFactory.richTextResponse(richContent = codeBlock)

        val result = router.route(response) as Deliverable.RichText

        val content = result.content as RichContent.CodeBlock
        assertEquals("java", content.language)
        assertEquals("System.out.println(\"Hello\");", content.code)
    }

    // ==================== Mixed deliverable variations ====================

    @Test
    fun `Mixed with null rich and null voice`() {
        val router = ResponseRouter(fullDevice(hasScreen = true, hasTts = false))
        val response = AgentResponse(
            type = ResponseType.BOTH,
            voiceText = null,
            richContent = null,
            fallbackText = "回退"
        )

        val result = router.route(response)

        assertTrue("Should be Mixed", result is Deliverable.Mixed)
        val mixed = result as Deliverable.Mixed
        assertNull(mixed.voice)
        assertNull(mixed.rich)
    }

    // ==================== Full device profile scenarios ====================

    @Test
    fun `FULL profile device routes TEXT to RichText when richContent exists`() {
        val router = ResponseRouter(DeviceCapabilities(
            hasScreen = true, hasTts = true, hasStt = true,
            hasRichText = true, hasNetwork = true, isInteractive = true
        ))
        val response = TestDataFactory.richTextResponse()

        val result = router.route(response)

        assertTrue(result is Deliverable.RichText)
    }

    @Test
    fun `VOICE_ONLY profile routes TEXT to Voice`() {
        val router = ResponseRouter(DeviceCapabilities(
            hasScreen = false, hasTts = true, hasStt = false,
            hasRichText = false, hasNetwork = true
        ))
        val response = TestDataFactory.textResponse(fallbackText = "语音回复")

        val result = router.route(response)

        assertTrue(result is Deliverable.Voice)
    }

    @Test
    fun `SCREEN_ONLY profile routes TEXT to PlainText`() {
        val router = ResponseRouter(DeviceCapabilities(
            hasScreen = true, hasTts = false, hasStt = false,
            hasRichText = true, hasNetwork = true
        ))
        val response = TestDataFactory.textResponse(fallbackText = "屏幕文本")

        val result = router.route(response)

        assertTrue(result is Deliverable.PlainText)
    }

    @Test
    fun `MINIMAL profile routes everything to PlainText`() {
        val router = ResponseRouter(DeviceCapabilities(
            hasScreen = false, hasTts = false, hasStt = false,
            hasRichText = false, hasNetwork = false
        ))

        val textResult = router.route(TestDataFactory.textResponse(fallbackText = "min"))
        val voiceResult = router.route(TestDataFactory.voiceResponse(fallbackText = "min"))
        val bothResult = router.route(TestDataFactory.bothResponse(fallbackText = "min"))

        assertTrue("TEXT → PlainText", textResult is Deliverable.PlainText)
        assertTrue("VOICE → PlainText", voiceResult is Deliverable.PlainText)
        assertTrue("BOTH → PlainText", bothResult is Deliverable.PlainText)
    }
}
