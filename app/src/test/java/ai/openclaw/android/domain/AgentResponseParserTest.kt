package ai.openclaw.android.domain

import ai.openclaw.android.test.TestDataFactory
import org.junit.Assert.*
import org.junit.Test

/**
 * AgentResponseParser 单元测试
 *
 * 验证 LLM 响应文本解析为 AgentResponse 的完整逻辑：
 * - 无 JSON → TEXT 类型回退
 * - 纯 TEXT 类型解析
 * - VOICE 类型解析
 * - BOTH 类型解析
 * - RichContent 解析（ListCard、InfoCard、CodeBlock）
 * - malformed JSON → 回退
 * - 空输入、边界情况
 */
class AgentResponseParserTest {

    private val parser = AgentResponseParser()

    // ==================== No JSON found ====================

    @Test
    fun `plain text without JSON returns TEXT type`() {
        val result = parser.parse("你好！我是 OpenClaw，有什么可以帮你的？")

        assertEquals(ResponseType.TEXT, result.type)
        assertNull(result.richContent)
        assertEquals("你好！我是 OpenClaw，有什么可以帮你的？", result.fallbackText)
    }

    @Test
    fun `plain text voiceText is truncated to 60 chars`() {
        val longText = "a".repeat(100)
        val result = parser.parse(longText)

        assertEquals(60, result.voiceText?.length)
        assertEquals(longText, result.fallbackText)
    }

    @Test
    fun `empty string returns TEXT with empty fallbackText`() {
        val result = parser.parse("")

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("", result.fallbackText)
        assertEquals("", result.voiceText)
    }

    @Test
    fun `only opening brace returns TEXT fallback`() {
        val result = parser.parse("Hello { world")

        // '{' found but no matching '}' after it → JSON extraction fails → fallback
        assertEquals(ResponseType.TEXT, result.type)
    }

    @Test
    fun `only closing brace returns TEXT fallback`() {
        val result = parser.parse("Hello } world")

        // No '{' found → fallback
        assertEquals(ResponseType.TEXT, result.type)
    }

    // ==================== TEXT type parsing ====================

    @Test
    fun `parse TEXT type response`() {
        val json = """{"type":"TEXT","voice_text":"语音播报","fallback_text":"完整回复"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("语音播报", result.voiceText)
        assertEquals("完整回复", result.fallbackText)
        assertNull(result.richContent)
    }

    @Test
    fun `parse TEXT type with text around JSON`() {
        val input = "这是一条AI回复\n\n{\"type\":\"TEXT\",\"fallback_text\":\"完整信息\"}\n需要更多帮助吗？"
        val result = parser.parse(input)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("完整信息", result.fallbackText)
    }

    @Test
    fun `parse TEXT type defaults when type field missing`() {
        val json = """{"fallback_text":"默认TEXT"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("默认TEXT", result.fallbackText)
    }

    @Test
    fun `parse TEXT with null voice_text`() {
        val json = """{"type":"TEXT","voice_text":null,"fallback_text":"回复"}"""
        val result = parser.parse(json)

        // JSON null should be treated as null (no voice text)
        assertNull(result.voiceText)
    }

    // ==================== VOICE type parsing ====================

    @Test
    fun `parse VOICE type response`() {
        val json = """{"type":"VOICE","voice_text":"这是语音内容","fallback_text":"文本回退"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.VOICE, result.type)
        assertEquals("这是语音内容", result.voiceText)
        assertEquals("文本回退", result.fallbackText)
        assertNull(result.richContent)
    }

    @Test
    fun `parse VOICE type lowercase`() {
        val json = """{"type":"voice","voice_text":"小写类型","fallback_text":"回退"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.VOICE, result.type)
    }

    @Test
    fun `parse VOICE without voiceText falls back to fallbackText`() {
        val json = """{"type":"VOICE","fallback_text":"只有文本"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.VOICE, result.type)
        assertNull(result.voiceText)
        assertEquals("只有文本", result.fallbackText)
    }

    // ==================== BOTH type parsing ====================

    @Test
    fun `parse BOTH type response`() {
        val json = """{"type":"BOTH","voice_text":"语音","fallback_text":"完整","rich_content":{"type":"list","data":{"title":"列表","items":["A","B"]}}}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.BOTH, result.type)
        assertEquals("语音", result.voiceText)
        assertEquals("完整", result.fallbackText)
        assertNotNull(result.richContent)
    }

    @Test
    fun `parse BOTH type lowercase`() {
        val json = """{"type":"both","voice_text":"v","fallback_text":"t"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.BOTH, result.type)
    }

    // ==================== RichContent parsing ====================

    @Test
    fun `parse ListCard rich_content`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"list","data":{"title":"搜索结果","items":["结果1","结果2","结果3"]}}}"""
        val result = parser.parse(json)

        assertNotNull(result.richContent)
        val listCard = result.richContent as RichContent.ListCard
        assertEquals("搜索结果", listCard.title)
        assertEquals(3, listCard.items.size)
        assertEquals("结果1", listCard.items[0])
        assertEquals("结果3", listCard.items[2])
    }

    @Test
    fun `parse InfoCard rich_content`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"card","data":{"title":"信息标题","body":"信息正文内容"}}}"""
        val result = parser.parse(json)

        assertNotNull(result.richContent)
        val infoCard = result.richContent as RichContent.InfoCard
        assertEquals("信息标题", infoCard.title)
        assertEquals("信息正文内容", infoCard.body)
    }

    @Test
    fun `parse CodeBlock rich_content`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"code","data":{"language":"kotlin","code":"fun main() {}"}}}"""
        val result = parser.parse(json)

        assertNotNull(result.richContent)
        val codeBlock = result.richContent as RichContent.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("fun main() {}", codeBlock.code)
    }

    @Test
    fun `unknown rich_content type returns null richContent`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"unknown_type","data":{"key":"value"}}}"""
        val result = parser.parse(json)

        assertNull(result.richContent)
    }

    @Test
    fun `null rich_content returns null richContent`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":null}"""
        val result = parser.parse(json)

        assertNull(result.richContent)
    }

    @Test
    fun `missing rich_content key returns null richContent`() {
        val json = """{"type":"TEXT","fallback_text":"t"}"""
        val result = parser.parse(json)

        assertNull(result.richContent)
    }

    @Test
    fun `rich_content with empty data uses defaults`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"list","data":{}}}"""
        val result = parser.parse(json)

        assertNotNull(result.richContent)
        val listCard = result.richContent as RichContent.ListCard
        assertEquals("", listCard.title)
        assertTrue(listCard.items.isEmpty())
    }

    @Test
    fun `rich_content without data key returns null richContent`() {
        val json = """{"type":"TEXT","fallback_text":"t","rich_content":{"type":"card"}}"""
        val result = parser.parse(json)

        // RichContent.fromJson requires both type and data; without data, returns null
        assertNull(result.richContent)
    }

    // ==================== Malformed JSON ====================

    @Test
    fun `malformed JSON falls back to TEXT`() {
        val input = "This is { broken json: true, missing quotes }"
        val result = parser.parse(input)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals(input, result.fallbackText)
    }

    @Test
    fun `JSON with extra text before returns parsed result`() {
        val input = "Here's the response: {\"type\":\"VOICE\",\"voice_text\":\"hi\",\"fallback_text\":\"text\"}"
        val result = parser.parse(input)

        assertEquals(ResponseType.VOICE, result.type)
        assertEquals("hi", result.voiceText)
    }

    @Test
    fun `JSON with trailing text after returns parsed result`() {
        val input = "{\"type\":\"TEXT\",\"fallback_text\":\"parsed\"} And more text after"
        val result = parser.parse(input)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("parsed", result.fallbackText)
    }

    @Test
    fun `multiple JSON objects — extracts first to last brace span`() {
        val input = """{"a":1} middle {"type":"TEXT","fallback_text":"second"}"""
        // This extracts from first { to last }, so it will try to parse the whole span
        // which won't be valid JSON → fallback
        val result = parser.parse(input)

        // The entire span {"a":1} middle {"type":"TEXT","fallback_text":"second"} is not valid JSON
        assertEquals(ResponseType.TEXT, result.type)
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty JSON object returns TEXT with empty fallback`() {
        val result = parser.parse("{}")

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("{}", result.fallbackText)
    }

    @Test
    fun `JSON with only type field`() {
        val json = """{"type":"VOICE"}"""
        val result = parser.parse(json)

        assertEquals(ResponseType.VOICE, result.type)
        assertEquals(json, result.fallbackText)
    }

    @Test
    fun `voiceText truncated when very long in plain text fallback`() {
        val longVoice = "x".repeat(200)
        val json = """{"type":"TEXT","voice_text":"$longVoice","fallback_text":"short"}"""
        val result = parser.parse(json)

        // voiceText from JSON is NOT truncated — only plain text fallback voiceText is
        assertEquals(longVoice, result.voiceText)
    }

    @Test
    fun `fallbackText blank falls back to full text`() {
        val json = """{"type":"TEXT","fallback_text":""}"""
        val result = parser.parse(json)

        assertEquals(json, result.fallbackText)
    }

    @Test
    fun `unicode content in JSON parses correctly`() {
        val json = """{"type":"TEXT","voice_text":"こんにちは","fallback_text":"你好世界 🌍"}"""
        val result = parser.parse(json)

        assertEquals("こんにちは", result.voiceText)
        assertEquals("你好世界 🌍", result.fallbackText)
    }

    @Test
    fun `very long text without JSON still works`() {
        val longText = "a".repeat(10000)
        val result = parser.parse(longText)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals(60, result.voiceText?.length)
        assertEquals(10000, result.fallbackText.length)
    }

    @Test
    fun `single character input`() {
        val result = parser.parse("x")

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("x", result.fallbackText)
    }

    // ==================== Complex real-world scenarios ====================

    @Test
    fun `parse realistic TEXT response with weather info`() {
        val input = """西安的天气如下：

{"type":"TEXT","voice_text":"西安今天多云，14度","fallback_text":"西安今天天气多云，气温14°C，体感12°C，湿度45%，东南风3级。","rich_content":{"type":"card","data":{"title":"西安天气","body":"多云 14°C，湿度45%"}}}

需要帮你设置提醒吗？"""
        val result = parser.parse(input)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("西安今天多云，14度", result.voiceText)
        assertTrue(result.fallbackText.contains("西安今天天气"))
        assertNotNull(result.richContent)
    }

    @Test
    fun `parse response with JSON embedded in markdown code block`() {
        val input = """我的回复是：

```json
{"type":"TEXT","fallback_text":"代码块中的JSON"}
```

希望这对你有帮助。"""
        val result = parser.parse(input)

        assertEquals(ResponseType.TEXT, result.type)
        assertEquals("代码块中的JSON", result.fallbackText)
    }

    @Test
    fun `parse BOTH with all fields populated`() {
        val json = """{
            "type": "BOTH",
            "voice_text": "简要播报：今天天气不错",
            "fallback_text": "今天北京晴转多云，最高气温25度，最低15度。空气质量良，适合户外活动。另外提醒：下午可能有雷阵雨，请带好雨具。",
            "rich_content": {
                "type": "list",
                "data": {
                    "title": "天气预报",
                    "items": ["晴转多云 15-25°C", "空气质量: 良", "下午可能有雷阵雨"]
                }
            }
        }"""
        val result = parser.parse(json)

        assertEquals(ResponseType.BOTH, result.type)
        assertEquals("简要播报：今天天气不错", result.voiceText)
        assertTrue(result.fallbackText.contains("北京"))
        val listCard = result.richContent as RichContent.ListCard
        assertEquals("天气预报", listCard.title)
        assertEquals(3, listCard.items.size)
    }

    // ==================== Integration with TestDataFactory ====================

    @Test
    fun `parse using TestDataFactory JSON response text`() {
        val text = TestDataFactory.jsonAgentResponseText(
            type = "BOTH",
            voiceText = "语音测试",
            fallbackText = "完整测试回复",
            richContent = mapOf(
                "type" to "list",
                "data" to mapOf("title" to "测试", "items" to listOf("A", "B"))
            )
        )

        val result = parser.parse(text)

        assertEquals(ResponseType.BOTH, result.type)
        assertEquals("语音测试", result.voiceText)
        assertEquals("完整测试回复", result.fallbackText)
        assertNotNull(result.richContent)
    }
}
