package ai.openclaw.android.util

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for CardGenerator — 兜底卡片生成工具
 *
 * 验证 generateInfoCard 和 ensureCardInResponse 的行为正确性。
 * 使用字符串断言而非 org.json.JSONObject（因 isReturnDefaultValues 干扰）。
 */
class CardGeneratorTest {

    @Test
    fun `generateInfoCard produces valid A2UI format`() {
        val result = CardGenerator.generateInfoCard("Hello World")

        // Must start and end with A2UI tags
        assertTrue("Should start with [A2UI]", result.startsWith("[A2UI]"))
        assertTrue("Should end with [/A2UI]", result.endsWith("[/A2UI]"))

        // Extract JSON part
        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")

        // Verify JSON structure via string matching
        assertTrue("Should contain type=info", jsonStr.contains("\"type\":\"info\""))
        assertTrue("Should contain data object", jsonStr.contains("\"data\":{"))
        assertTrue("Should contain icon=info", jsonStr.contains("\"icon\":\"info\""))
        assertTrue("Should contain content", jsonStr.contains("\"content\":\"Hello World\""))
        assertTrue("Should contain actions array", jsonStr.contains("\"actions\":["))
    }

    @Test
    fun `generateInfoCard includes summary for long text`() {
        val longText = "This is a very long text that exceeds one hundred characters in length. ".repeat(3)
        val result = CardGenerator.generateInfoCard(longText)

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should contain summary field", jsonStr.contains("\"summary\":"))
        assertTrue("Summary should contain first 100 chars of text",
            jsonStr.contains(longText.take(50)))
        assertTrue("Summary should end with ellipsis", jsonStr.contains("...\""))
    }

    @Test
    fun `generateInfoCard includes title when provided`() {
        val result = CardGenerator.generateInfoCard("Test content", "My Title")

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should contain title field", jsonStr.contains("\"title\":\"My Title\""))
    }

    @Test
    fun `generateInfoCard no title when not provided`() {
        val result = CardGenerator.generateInfoCard("Test content")

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        // After "data":{ should NOT contain "title":
        val dataStart = jsonStr.indexOf("\"data\":{")
        val nextKeyAfterData = jsonStr.indexOf("\"", dataStart + 8)
        val firstKeyInData = jsonStr.substring(nextKeyAfterData, jsonStr.indexOf("\"", nextKeyAfterData + 1))
        assertFalse("First key in data should not be title, got: $firstKeyInData", firstKeyInData == "title")
    }

    @Test
    fun `generateInfoCard has copy action`() {
        val result = CardGenerator.generateInfoCard("Test content")

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should contain copy action", jsonStr.contains("\"action\":\"copy\""))
        assertTrue("Should contain Secondary style", jsonStr.contains("\"style\":\"Secondary\""))
        assertTrue("Should contain copy label", jsonStr.contains("复制全文"))
    }

    @Test
    fun `ensureCardInResponse passes through existing cards`() {
        val existingCard = """[A2UI]{"type":"weather","data":{"title":"天气"},"actions":[]}[/A2UI]"""
        val result = CardGenerator.ensureCardInResponse(existingCard)

        assertEquals("Should return original card unchanged", existingCard, result)
    }

    @Test
    fun `ensureCardInResponse wraps plain text as InfoCard`() {
        val plainText = "The weather is nice today."
        val result = CardGenerator.ensureCardInResponse(plainText)

        assertTrue("Should wrap in A2UI tags", result.startsWith("[A2UI]"))
        assertTrue("Should end with [/A2UI]", result.endsWith("[/A2UI]"))

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should be info type", jsonStr.contains("\"type\":\"info\""))
        assertTrue("Should contain original text", jsonStr.contains("The weather is nice today."))
    }

    @Test
    fun `ensureCardInResponse with default title`() {
        val plainText = "Search results found."
        val result = CardGenerator.ensureCardInResponse(plainText, "搜索结果")

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should contain title", jsonStr.contains("\"title\":\"搜索结果\""))
    }

    @Test
    fun `generateInfoCard handles empty string`() {
        val result = CardGenerator.generateInfoCard("")

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        assertTrue("Should contain empty content", jsonStr.contains("\"content\":\"\""))
        assertFalse("Empty string should not have summary", jsonStr.contains("\"summary\":"))
    }

    @Test
    fun `ensureCardInResponse does not re-wrap already wrapped content`() {
        val alreadyWrapped = "[A2UI]{\"type\":\"info\",\"data\":{\"content\":\"test\"}}[/A2UI]"
        val result = CardGenerator.ensureCardInResponse(alreadyWrapped, "Should be ignored")

        assertEquals("Should not double-wrap", alreadyWrapped, result)
    }

    @Test
    fun `generateInfoCard handles special characters in content`() {
        val specialText = "Hello world & test value"
        val result = CardGenerator.generateInfoCard(specialText)

        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")
        // Should be valid JSON (can find the content)
        assertTrue("Should contain the special text", jsonStr.contains("Hello world"))
    }

    @Test
    fun `generateInfoCard output is valid JSON that can be parsed`() {
        val result = CardGenerator.generateInfoCard("Test")
        val jsonStr = result.removePrefix("[A2UI]").removeSuffix("[/A2UI]")

        // Verify JSON structure: must start with { and end with }
        assertTrue("JSON must start with {", jsonStr.trim().startsWith("{"))
        assertTrue("JSON must end with }", jsonStr.trim().endsWith("}"))

        // Verify balanced braces
        val openBraces = jsonStr.count { it == '{' }
        val closeBraces = jsonStr.count { it == '}' }
        assertEquals("Braces must be balanced", openBraces, closeBraces)

        // Verify balanced brackets
        val openBrackets = jsonStr.count { it == '[' }
        val closeBrackets = jsonStr.count { it == ']' }
        assertEquals("Brackets must be balanced", openBrackets, closeBrackets)
    }
}
