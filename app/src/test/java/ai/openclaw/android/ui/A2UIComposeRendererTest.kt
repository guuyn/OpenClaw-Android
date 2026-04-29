package ai.openclaw.android.ui

import org.junit.Test
import org.junit.Assert.*

/**
 * A2UI 协议解析与转换工具的 JVM 单元测试。
 *
 * 测试覆盖：
 * - [A2UI]...[/A2UI] 标记提取（单段、多段、无标记、不完整标记）
 * - 标准协议检测（v0.8/v0.9/v0.10 各类操作名）
 * - JSONL 规范化（createSurface + updateComponents 拆分）
 * - 旧格式 → 标准协议转换（weather/search/location/error 等）
 * - JSON 转义与显示值安全转换
 * - 错误降级（非法 JSON、空输入、格式不匹配）
 *
 * 使用 MockDataProvider 中的测试数据。
 */
class A2UIComposeRendererTest {

    // ==================== extractA2UIJsons ====================

    @Test
    fun `extract single A2UI JSON from markup`() {
        val content = "[A2UI]{\"type\":\"weather\",\"data\":{}}[/A2UI]"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
        assertEquals("{\"type\":\"weather\",\"data\":{}}", jsons[0])
    }

    @Test
    fun `extract multiple A2UI JSONs from markup`() {
        val content = "[A2UI]{\"a\":1}[/A2UI]some text[A2UI]{\"b\":2}[/A2UI]"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(2, jsons.size)
        assertEquals("{\"a\":1}", jsons[0])
        assertEquals("{\"b\":2}", jsons[1])
    }

    @Test
    fun `extract returns empty list when no A2UI tags`() {
        val content = "Hello, this is plain text without any A2UI markup."
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertTrue(jsons.isEmpty())
    }

    @Test
    fun `extract returns empty when only start tag present`() {
        val content = "[A2UI]{\"broken\":true}"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertTrue(jsons.isEmpty())
    }

    @Test
    fun `extract returns empty when only end tag present`() {
        val content = "{\"broken\":true}[/A2UI]"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertTrue(jsons.isEmpty())
    }

    @Test
    fun `extract trims whitespace from extracted JSON`() {
        val content = "[A2UI]  \n  {\"trimmed\":true}  \n  [/A2UI]"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
        assertEquals("{\"trimmed\":true}", jsons[0])
    }

    @Test
    fun `extract empty string returns empty list`() {
        val jsons = A2UIParseUtils.extractA2UIJsons("")
        assertTrue(jsons.isEmpty())
    }

    @Test
    fun `extract from MockDataProvider weather card`() {
        val content = ai.openclaw.android.MockDataProvider.weatherCardMessage.content
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
        assertTrue(jsons[0].contains("\"type\":\"weather\""))
    }

    @Test
    fun `extract from MockDataProvider standard protocol`() {
        val content = ai.openclaw.android.MockDataProvider.standardProtocolMessage.content
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
        assertTrue(jsons[0].contains("\"version\":\"v0.10\""))
    }

    @Test
    fun `extract from MockDataProvider error card`() {
        val content = ai.openclaw.android.MockDataProvider.errorMessage.content
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
        assertTrue(jsons[0].contains("\"type\":\"error\""))
    }

    @Test
    fun `extract from plain text returns empty`() {
        val content = ai.openclaw.android.MockDataProvider.plainTextMessage.content
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertTrue(jsons.isEmpty())
    }

    // ==================== isStandardProtocol ====================

    @Test
    fun `detects v0_10 standard protocol with createSurface`() {
        val json = """{"version":"v0.10","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `detects standard protocol with updateComponents`() {
        val json = """{"version":"v0.10","updateComponents":{"surfaceId":"s1","components":[]}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `detects standard protocol with updateDataModel`() {
        val json = """{"version":"v0.10","updateDataModel":{"surfaceId":"s1","path":"/","value":1}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `detects standard protocol with deleteSurface`() {
        val json = """{"version":"v0.10","deleteSurface":{"surfaceId":"s1"}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `detects standard protocol with surfaceUpdate`() {
        val json = """{"version":"v0.10","surfaceUpdate":{"surfaceId":"s1"}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `detects standard protocol with beginRendering`() {
        val json = """{"version":"v0.10","beginRendering":{"surfaceId":"s1"}}"""
        assertTrue(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `legacy weather format is NOT standard protocol`() {
        val json = """{"type":"weather","data":{"city":"西安","temperature":"20"},"actions":[]}"""
        assertFalse(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `legacy search_result format is NOT standard protocol`() {
        val json = """{"type":"search_result","data":{"title":"搜索","query":"test","items":[]}}"""
        assertFalse(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `legacy location format is NOT standard protocol`() {
        val json = """{"type":"location","data":{"address":"test","latitude":"34.0","longitude":"108.0"}}"""
        assertFalse(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `legacy error format is NOT standard protocol`() {
        val json = """{"type":"error","data":{"icon":"warning","title":"错误","message":"测试"}}"""
        assertFalse(A2UIParseUtils.isStandardProtocol(json))
    }

    @Test
    fun `empty string is NOT standard protocol`() {
        assertFalse(A2UIParseUtils.isStandardProtocol(""))
    }

    // ==================== normalizeToJsonL ====================

    @Test
    fun `normalize splits createSurface and updateComponents into separate lines`() {
        val json = """{"version":"v0.10","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"},"updateComponents":{"surfaceId":"s1","components":[{"id":"root","component":"Text","text":"Hello"}]}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"createSurface\""))
        assertTrue(lines[1].contains("\"updateComponents\""))
    }

    @Test
    fun `normalize preserves version in each line`() {
        val json = """{"version":"v0.10","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"},"updateComponents":{"surfaceId":"s1","components":[]}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            assertTrue(line.contains("\"version\":\"v0.10\""))
        }
    }

    @Test
    fun `normalize handles v0_8 version`() {
        val json = """{"version":"v0.8","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"},"updateComponents":{"surfaceId":"s1","components":[]}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            assertTrue(line.contains("\"version\":\"v0.8\""))
        }
    }

    @Test
    fun `normalize handles v0_9 version`() {
        val json = """{"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"},"updateComponents":{"surfaceId":"s1","components":[]}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            assertTrue(line.contains("\"version\":\"v0.9\""))
        }
    }

    @Test
    fun `normalize with only createSurface produces single line`() {
        val json = """{"version":"v0.10","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"createSurface\""))
    }

    @Test
    fun `normalize with only updateComponents produces single line`() {
        val json = """{"version":"v0.10","updateComponents":{"surfaceId":"s1","components":[]}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"updateComponents\""))
    }

    @Test
    fun `normalize with updateDataModel produces correct line`() {
        val json = """{"version":"v0.10","updateDataModel":{"surfaceId":"s1","path":"/","value":42}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"updateDataModel\""))
    }

    @Test
    fun `normalize returns original for JSON with no known fields`() {
        val json = """{"type":"weather","data":{}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        assertEquals(json, result)
    }

    @Test
    fun `normalize returns original for invalid JSON`() {
        val json = "{ invalid json }"
        val result = A2UIParseUtils.normalizeToJsonL(json)
        assertEquals(json, result)
    }

    @Test
    fun `normalize with all three operation types`() {
        val json = """{"version":"v0.10","createSurface":{"surfaceId":"s1","catalogId":"https://example.com"},"updateComponents":{"surfaceId":"s1","components":[]},"updateDataModel":{"surfaceId":"s1","path":"/data","value":"test"}}"""
        val result = A2UIParseUtils.normalizeToJsonL(json)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
    }

    // ==================== convertLegacyCardToProtocol ====================

    @Test
    fun `convert weather card to standard protocol`() {
        val json = """{"type":"weather","data":{"title":"西安天气","city":"西安","condition":"多云","temperature":"14"},"actions":[{"label":"设置提醒","action":"set_reminder","style":"Primary"}]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "test_surface")
        assertNotNull(result)
        assertTrue(result!!.contains("\"createSurface\""))
        assertTrue(result.contains("\"updateComponents\""))
        assertTrue(result.contains("\"surfaceId\":\"test_surface\""))
        assertTrue(result.contains("\"component\":\"Column\""))
        assertTrue(result.contains("\"component\":\"Text\""))
        assertTrue(result.contains("\"component\":\"Button\""))
    }

    @Test
    fun `convert search_result card to standard protocol`() {
        val json = """{"type":"search_result","data":{"title":"搜索结果","query":"OpenClaw","items":[]},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "search_001")
        assertNotNull(result)
        assertTrue(result!!.contains("\"updateComponents\""))
        assertTrue(result.contains("\"surfaceId\":\"search_001\""))
    }

    @Test
    fun `convert location card to standard protocol`() {
        val json = """{"type":"location","data":{"title":"位置","address":"陕西省西安市","latitude":"34.2","longitude":"108.9"},"actions":[{"label":"导航","action":"navigate","style":"Primary"}]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "loc_001")
        assertNotNull(result)
        assertTrue(result!!.contains("\"surfaceId\":\"loc_001\""))
        assertTrue(result.contains("陕西省西安市"))
    }

    @Test
    fun `convert error card to standard protocol`() {
        val json = """{"type":"error","data":{"icon":"warning","title":"操作失败","message":"无法连接"},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "err_001")
        assertNotNull(result)
        assertTrue(result!!.contains("操作失败"))
    }

    @Test
    fun `convert translation card to standard protocol`() {
        val json = """{"type":"translation","data":{"sourceText":"Hello","targetText":"你好","sourceLang":"en","targetLang":"zh"},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "trans_001")
        assertNotNull(result)
        assertTrue(result!!.contains("Hello"))
        assertTrue(result.contains("你好"))
    }

    @Test
    fun `convert reminder card to standard protocol`() {
        val json = """{"type":"reminder","data":{"title":"提醒","items":[]},"actions":[{"label":"新建","action":"new","style":"Primary"}]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "rem_001")
        assertNotNull(result)
        assertTrue(result!!.contains("提醒"))
    }

    @Test
    fun `convert calendar card to standard protocol`() {
        val json = """{"type":"calendar","data":{"title":"日程","date":"2026-04-14","items":[]},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "cal_001")
        assertNotNull(result)
        assertTrue(result!!.contains("日程"))
    }

    @Test
    fun `convert card without type returns null`() {
        val json = """{"data":{"title":"test"},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "test")
        assertNull(result)
    }

    @Test
    fun `convert invalid JSON returns null`() {
        val json = "{ invalid }"
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "test")
        assertNull(result)
    }

    @Test
    fun `convert card without data still works`() {
        val json = """{"type":"info","actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "info_001")
        assertNotNull(result)
        // Should have header with type-based title
        assertTrue(result!!.contains("\"component\":\"Text\""))
    }

    @Test
    fun `convert card with Secondary style button uses borderless variant`() {
        val json = """{"type":"info","data":{"title":"测试"},"actions":[{"label":"取消","action":"cancel","style":"Secondary"}]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "info_001")
        assertNotNull(result)
        assertTrue(result!!.contains("\"variant\":\"borderless\""))
    }

    @Test
    fun `convert card with Primary style button uses primary variant`() {
        val json = """{"type":"info","data":{"title":"测试"},"actions":[{"label":"确认","action":"confirm","style":"Primary"}]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "info_001")
        assertNotNull(result)
        assertTrue(result!!.contains("\"variant\":\"primary\""))
    }

    // ==================== esc (JSON escaping) ====================

    @Test
    fun `esc escapes backslash`() {
        assertEquals("hello\\\\world", A2UIParseUtils.esc("hello\\world"))
    }

    @Test
    fun `esc escapes double quotes`() {
        assertEquals("hello\\\"world", A2UIParseUtils.esc("hello\"world"))
    }

    @Test
    fun `esc escapes newlines`() {
        assertEquals("hello\\nworld", A2UIParseUtils.esc("hello\nworld"))
    }

    @Test
    fun `esc escapes tabs`() {
        assertEquals("hello\\tworld", A2UIParseUtils.esc("hello\tworld"))
    }

    @Test
    fun `esc escapes carriage returns`() {
        assertEquals("hello\\rworld", A2UIParseUtils.esc("hello\rworld"))
    }

    @Test
    fun `esc leaves plain text unchanged`() {
        assertEquals("hello world", A2UIParseUtils.esc("hello world"))
    }

    @Test
    fun `esc removes control characters`() {
        // ASCII 0x01 (SOH) should be filtered
        val input = "hello\u0001world"
        val result = A2UIParseUtils.esc(input)
        assertFalse(result.contains('\u0001'.toString()))
    }

    @Test
    fun `esc handles chinese characters`() {
        assertEquals("你好世界", A2UIParseUtils.esc("你好世界"))
    }

    // ==================== safeDisplayValue ====================

    @Test
    fun `safeDisplayValue handles null`() {
        assertEquals("", A2UIParseUtils.safeDisplayValue(null))
    }

    @Test
    fun `safeDisplayValue handles string`() {
        assertEquals("hello", A2UIParseUtils.safeDisplayValue("hello"))
    }

    @Test
    fun `safeDisplayValue handles number`() {
        assertEquals("42", A2UIParseUtils.safeDisplayValue(42))
    }

    @Test
    fun `safeDisplayValue handles double`() {
        assertEquals("3.14", A2UIParseUtils.safeDisplayValue(3.14))
    }

    @Test
    fun `safeDisplayValue handles boolean`() {
        assertEquals("true", A2UIParseUtils.safeDisplayValue(true))
        assertEquals("false", A2UIParseUtils.safeDisplayValue(false))
    }

    @Test
    fun `safeDisplayValue handles list`() {
        assertEquals("a, b, c", A2UIParseUtils.safeDisplayValue(listOf("a", "b", "c")))
    }

    @Test
    fun `safeDisplayValue handles map`() {
        val result = A2UIParseUtils.safeDisplayValue(mapOf("name" to "John", "age" to 30))
        assertTrue(result.contains("name"))
        assertTrue(result.contains("John"))
    }

    @Test
    fun `safeDisplayValue truncates very long strings`() {
        val longStr = "a".repeat(600)
        val result = A2UIParseUtils.safeDisplayValue(longStr)
        assertTrue(result.length <= 502) // 500 + "…"
    }

    @Test
    fun `safeDisplayValue handles object references`() {
        // Object references like "java.lang.Object@1234" should return empty
        val obj = Any()
        val result = A2UIParseUtils.safeDisplayValue(obj)
        // Object.toString() contains @ and is short
        if (obj.toString().contains('@') && obj.toString().length < 50) {
            assertEquals("", result)
        }
    }

    // ==================== typeTitle ====================

    @Test
    fun `typeTitle maps all known types`() {
        assertEquals("🌤️ 天气", A2UIParseUtils.typeTitle("weather"))
        assertEquals("🔍 搜索结果", A2UIParseUtils.typeTitle("search_result"))
        assertEquals("🌐 翻译", A2UIParseUtils.typeTitle("translation"))
        assertEquals("⏰ 提醒", A2UIParseUtils.typeTitle("reminder"))
        assertEquals("📅 日程", A2UIParseUtils.typeTitle("calendar"))
        assertEquals("📍 位置", A2UIParseUtils.typeTitle("location"))
        assertEquals("⚠️ 错误", A2UIParseUtils.typeTitle("error"))
        assertEquals("ℹ️ 信息", A2UIParseUtils.typeTitle("info"))
        assertEquals("📋 摘要", A2UIParseUtils.typeTitle("summary"))
        assertEquals("👤 联系人", A2UIParseUtils.typeTitle("contact"))
        assertEquals("💬 短信", A2UIParseUtils.typeTitle("sms"))
        assertEquals("📱 应用", A2UIParseUtils.typeTitle("app"))
        assertEquals("⚙️ 设置", A2UIParseUtils.typeTitle("settings"))
    }

    @Test
    fun `typeTitle capitalizes unknown types`() {
        assertEquals("Custom", A2UIParseUtils.typeTitle("custom"))
        assertEquals("Unknown", A2UIParseUtils.typeTitle("unknown"))
    }

    // ==================== extractSurfaceId ====================

    @Test
    fun `extractSurfaceId from createSurface`() {
        val json = """{"createSurface":{"surfaceId":"my_surface","catalogId":"https://example.com"}}"""
        assertEquals("my_surface", A2UIParseUtils.extractSurfaceId(json))
    }

    @Test
    fun `extractSurfaceId from updateComponents fallback`() {
        val json = """{"updateComponents":{"surfaceId":"fallback_surface","components":[]}}"""
        assertEquals("fallback_surface", A2UIParseUtils.extractSurfaceId(json))
    }

    @Test
    fun `extractSurfaceId returns generated id for null`() {
        val result = A2UIParseUtils.extractSurfaceId(null)
        assertTrue(result.startsWith("chat_"))
    }

    @Test
    fun `extractSurfaceId returns generated id for invalid JSON`() {
        val result = A2UIParseUtils.extractSurfaceId("{invalid}")
        assertTrue(result.startsWith("chat_"))
    }

    @Test
    fun `extractSurfaceId returns generated id when no surface fields`() {
        val json = """{"type":"weather","data":{}}"""
        val result = A2UIParseUtils.extractSurfaceId(json)
        assertTrue(result.startsWith("chat_"))
    }

    // ==================== MockDataProvider Integration ====================

    @Test
    fun `all MockDataProvider scenarios with A2UI content are parseable`() {
        val scenarios = ai.openclaw.android.MockDataProvider.getAllScenarios()
        val a2uiMessages = scenarios.filter { it.content.contains("[A2UI]") }

        for (msg in a2uiMessages) {
            val jsons = A2UIParseUtils.extractA2UIJsons(msg.content)
            assertTrue("Failed to extract A2UI JSON from: ${msg.content.take(50)}", jsons.isNotEmpty())

            val json = jsons[0]
            if (A2UIParseUtils.isStandardProtocol(json)) {
                val normalized = A2UIParseUtils.normalizeToJsonL(json)
                val lines = normalized.lines().filter { it.isNotBlank() }
                assertTrue("Standard protocol should produce at least 1 line", lines.isNotEmpty())
            } else {
                // Legacy format should be convertible
                val surfaceId = A2UIParseUtils.extractSurfaceId(json)
                val converted = A2UIParseUtils.convertLegacyCardToProtocol(json, surfaceId)
                assertNotNull("Legacy format should be convertible: ${json.take(50)}", converted)
            }
        }
    }

    @Test
    fun `plain text message produces no A2UI JSON`() {
        val jsons = A2UIParseUtils.extractA2UIJsons(ai.openclaw.android.MockDataProvider.plainTextMessage.content)
        assertTrue(jsons.isEmpty())
    }

    @Test
    fun `standard protocol message is detected as standard`() {
        val jsons = A2UIParseUtils.extractA2UIJsons(ai.openclaw.android.MockDataProvider.standardProtocolMessage.content)
        assertEquals(1, jsons.size)
        assertTrue(A2UIParseUtils.isStandardProtocol(jsons[0]))
    }

    @Test
    fun `weather card message is detected as legacy`() {
        val jsons = A2UIParseUtils.extractA2UIJsons(ai.openclaw.android.MockDataProvider.weatherCardMessage.content)
        assertEquals(1, jsons.size)
        assertFalse(A2UIParseUtils.isStandardProtocol(jsons[0]))
    }

    // ==================== Edge cases ====================

    @Test
    fun `extract handles nested A2UI-like text in JSON`() {
        // JSON containing "[A2UI]" as text value should still extract correctly
        val content = "[A2UI]{\"type\":\"info\",\"data\":{\"content\":\"See [A2UI] tag\"}}[/A2UI]"
        val jsons = A2UIParseUtils.extractA2UIJsons(content)
        assertEquals(1, jsons.size)
    }

    @Test
    fun `esc handles multiple special characters`() {
        val input = "a\"b\nc\td\\e"
        val result = A2UIParseUtils.esc(input)
        assertEquals("a\\\"b\\nc\\td\\\\e", result)
    }

    @Test
    fun `convert with empty actions array produces no button components`() {
        val json = """{"type":"info","data":{"title":"无按钮"},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "test")
        assertNotNull(result)
        assertFalse(result!!.contains("\"component\":\"Button\""))
    }

    @Test
    fun `convert with empty data produces only header`() {
        val json = """{"type":"info","data":{},"actions":[]}"""
        val result = A2UIParseUtils.convertLegacyCardToProtocol(json, "test")
        assertNotNull(result)
        // Should have header but no body
        assertTrue(result!!.contains("\"id\":\"header\""))
    }
}
