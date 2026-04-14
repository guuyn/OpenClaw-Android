package ai.openclaw.android

import ai.openclaw.android.ui.A2UICardParser
import ai.openclaw.android.ui.MessageSegment
import org.junit.Test
import org.junit.Assert.*

class ChatScreenParserTest {

    @Test
    fun `v2 JSON parsing via A2UICardParser`() {
        val json = """{"type":"weather","data":{"city":"西安","temperature":"14"}}"""
        val segments = A2UICardParser.parse("[A2UI]$json[/A2UI]")
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.A2UICard)
        val card = (segments[0] as MessageSegment.A2UICard).card
        assertEquals("weather", card.type)
    }

    @Test
    fun `v1 backward compatible parsing`() {
        val legacy = "weather\ncity: 西安\ntemperature: 14"
        val segments = A2UICardParser.parse("[A2UI]$legacy[/A2UI]")
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.A2UICard)
    }

    @Test
    fun `plain text without A2UI tags`() {
        val content = "这是一条普通消息"
        val segments = A2UICardParser.parse(content)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
    }

    @Test
    fun `mixed text and A2UI cards`() {
        val content = "前半段[A2UI]{\"type\":\"weather\",\"data\":{\"city\":\"北京\"}}[/A2UI]后半段"
        val segments = A2UICardParser.parse(content)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.A2UICard)
        assertTrue(segments[2] is MessageSegment.Text)
        assertEquals("前半段", (segments[0] as MessageSegment.Text).text)
        assertEquals("后半段", (segments[2] as MessageSegment.Text).text)
    }

    @Test
    fun `multiple A2UI cards in one message`() {
        val content = "[A2UI]{\"type\":\"weather\",\"data\":{\"city\":\"北京\"}}[/A2UI][A2UI]{\"type\":\"search\",\"data\":{\"query\":\"test\"}}[/A2UI]"
        val segments = A2UICardParser.parse(content)
        assertEquals(2, segments.size)
        assertTrue(segments[0] is MessageSegment.A2UICard)
        assertTrue(segments[1] is MessageSegment.A2UICard)
        assertEquals("weather", (segments[0] as MessageSegment.A2UICard).card.type)
        assertEquals("search", (segments[1] as MessageSegment.A2UICard).card.type)
    }

    @Test
    fun `v2 card with actions`() {
        val json = """{"type":"action_confirm","data":{"title":"确认操作"},"actions":[{"label":"确认","action":"confirm","style":"Primary"}]}"""
        val segments = A2UICardParser.parse("[A2UI]$json[/A2UI]")
        assertEquals(1, segments.size)
        val card = (segments[0] as MessageSegment.A2UICard).card
        assertEquals("action_confirm", card.type)
        assertEquals(1, card.actions.size)
        assertEquals("确认", card.actions[0].label)
    }
}
