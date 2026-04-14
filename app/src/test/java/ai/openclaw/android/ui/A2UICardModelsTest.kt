package ai.openclaw.android.ui

import org.junit.Test
import org.junit.Assert.*

class A2UICardModelsTest {

    @Test
    fun `parse v2 weather card JSON`() {
        val json = """{"type":"weather","data":{"title":"西安天气","city":"西安","condition":"多云","temperature":"14","feelsLike":"12","humidity":"45","wind":"东南风 3级","forecast":[{"day":"周二","icon":"rainy","condition":"小雨","high":"16","low":"11"}]}}"""

        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")
        assertEquals(1, card.size)
        assertTrue(card[0] is MessageSegment.A2UICard)
        val a2uiCard = (card[0] as MessageSegment.A2UICard).card
        assertEquals("weather", a2uiCard.type)
        assertEquals("西安", a2uiCard.rawData["city"])
        assertEquals("14", a2uiCard.rawData["temperature"])
    }

    @Test
    fun `parse v2 card with actions`() {
        val json = """{"type":"weather","data":{"city":"西安","temperature":"14"},"actions":[{"label":"提醒","action":"set_reminder","style":"Primary"}]}"""

        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")
        val a2uiCard = (card[0] as MessageSegment.A2UICard).card
        assertEquals(1, a2uiCard.actions.size)
        assertEquals("提醒", a2uiCard.actions[0].label)
        assertEquals(ButtonStyle.Primary, a2uiCard.actions[0].style)
    }

    @Test
    fun `parse v1 legacy format fallback`() {
        val legacy = "weather\ncity: 西安\ntemperature: 14"

        val card = A2UICardParser.parse("[A2UI]$legacy[/A2UI]")
        assertEquals(1, card.size)
        val a2uiCard = (card[0] as MessageSegment.A2UICard).card
        assertEquals("weather", a2uiCard.type)
        assertEquals("西安", a2uiCard.rawData["city"])
    }

    @Test
    fun `parse mixed text and cards`() {
        val content = "以下是天气信息：\n[A2UI]{\"type\":\"weather\",\"data\":{\"city\":\"西安\"}}[/A2UI]\n希望对你有帮助"

        val segments = A2UICardParser.parse(content)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.A2UICard)
        assertTrue(segments[2] is MessageSegment.Text)
    }

    @Test
    fun `parse action confirm card with risk level`() {
        val json = """{"type":"action_confirm","data":{"title":"确认发送","icon":"send","riskLevel":"high","description":"已准备好发送消息","details":{"联系人":"张三","消息":"晚上6点吃饭"},"warning":"此操作不可撤销"}}"""

        val card = (A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard).card
        val confirmData = card.asActionConfirmCard()
        assertNotNull(confirmData)
        assertEquals(RiskLevel.High, confirmData!!.riskLevel)
        assertEquals("张三", confirmData.details["联系人"])
        assertEquals("此操作不可撤销", confirmData.warning)
    }

    @Test
    fun `parse translation card`() {
        val json = """{"type":"translation","data":{"sourceText":"Hello","sourceLang":"en","targetText":"你好","targetLang":"zh-CN","pronunciation":"ni hao"}}"""

        val card = (A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard).card
        val transData = card.asTranslationCard()
        assertNotNull(transData)
        assertEquals("Hello", transData!!.sourceText)
        assertEquals("你好", transData.targetText)
        assertEquals("ni hao", transData.pronunciation)
    }

    @Test
    fun `parse error card`() {
        val json = """{"type":"error","data":{"icon":"warning","title":"无法获取天气","message":"网络错误","suggestion":"请检查网络"}}"""

        val card = (A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard).card
        val errorData = card.asErrorCard()
        assertNotNull(errorData)
        assertEquals("无法获取天气", errorData!!.title)
        assertEquals("网络错误", errorData.message)
    }

    @Test
    fun `parse invalid JSON falls back to text`() {
        val content = "[A2UI]not json at all[/A2UI]"
        val segments = A2UICardParser.parse(content)
        assertTrue(segments.isNotEmpty())
    }
}
