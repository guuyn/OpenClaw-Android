package ai.openclaw.android.skill.builtin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TranslateSkill v2 A2UI card JSON format.
 */
class TranslateSkillV2Test {

    private val json = Json { ignoreUnknownKeys = true }
    private val skill = TranslateSkill()

    // ==================== Test 1: Basic translation card ====================

    @Test
    fun `returns v2 A2UI card format for translation`() {
        val cardJson = skill.buildTranslationCardV2(
            sourceText = "Hello",
            sourceLang = "en",
            targetText = "你好",
            targetLang = "zh-CN"
        )

        val wrapped = "[A2UI]$cardJson[/A2UI]"
        assertTrue("Should contain [A2UI] start tag", wrapped.contains("[A2UI]"))
        assertTrue("Should contain [/A2UI] end tag", wrapped.contains("[/A2UI]"))

        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("translation", element["type"]?.jsonPrimitive?.content)
    }

    // ==================== Test 2: Data fields ====================

    @Test
    fun `contains correct translation data fields`() {
        val cardJson = skill.buildTranslationCardV2(
            sourceText = "Hello World",
            sourceLang = "en",
            targetText = "你好世界",
            targetLang = "zh-CN",
            pronunciation = "ni hao shi jie"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)

        assertEquals("Hello World", data!!["sourceText"]?.jsonPrimitive?.content)
        assertEquals("en", data["sourceLang"]?.jsonPrimitive?.content)
        assertEquals("你好世界", data["targetText"]?.jsonPrimitive?.content)
        assertEquals("zh-CN", data["targetLang"]?.jsonPrimitive?.content)
        assertEquals("ni hao shi jie", data["pronunciation"]?.jsonPrimitive?.content)
    }

    // ==================== Test 3: Pronunciation is optional ====================

    @Test
    fun `pronunciation is optional and omitted when null`() {
        val cardJson = skill.buildTranslationCardV2(
            sourceText = "Good morning",
            sourceLang = "en",
            targetText = "早上好",
            targetLang = "zh-CN"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertNull("pronunciation should not be present when null", data!!["pronunciation"])
    }

    // ==================== Test 4: Action buttons ====================

    @Test
    fun `includes action buttons for speak and copy`() {
        val cardJson = skill.buildTranslationCardV2(
            sourceText = "Test",
            sourceLang = "en",
            targetText = "测试",
            targetLang = "zh-CN"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val actions = element["actions"]?.jsonArray
        assertNotNull("actions field should exist", actions)
        assertEquals("Should have 2 action buttons", 2, actions!!.size)

        // First button: speak
        val btn1 = actions[0].jsonObject
        assertEquals("🔊 朗读", btn1["label"]?.jsonPrimitive?.content)
        assertEquals("speak_target", btn1["action"]?.jsonPrimitive?.content)
        assertEquals("Secondary", btn1["style"]?.jsonPrimitive?.content)

        // Second button: copy
        val btn2 = actions[1].jsonObject
        assertEquals("📋 复制", btn2["label"]?.jsonPrimitive?.content)
        assertEquals("copy_translation", btn2["action"]?.jsonPrimitive?.content)
        assertEquals("Secondary", btn2["style"]?.jsonPrimitive?.content)
    }

    // ==================== Test 5: Valid JSON ====================

    @Test
    fun `card is valid JSON for various languages`() {
        val languages = listOf(
            Triple("Hello", "en", "こんにちは"),
            Triple("Hello", "en", "Bonjour"),
            Triple("Hello", "en", "안녕하세요"),
        )

        for ((text, lang, target) in languages) {
            val cardJson = skill.buildTranslationCardV2(
                sourceText = text,
                sourceLang = lang,
                targetText = target,
                targetLang = "ja"
            )

            // Should not throw
            val parsed = json.parseToJsonElement(cardJson)
            assertNotNull(parsed)
            assertEquals("translation", parsed.jsonObject["type"]?.jsonPrimitive?.content)
        }
    }

    // ==================== Test 6: JSON matches expected structure ====================

    @Test
    fun `card matches exact expected structure`() {
        val cardJson = skill.buildTranslationCardV2(
            sourceText = "Hello",
            sourceLang = "en",
            targetText = "你好",
            targetLang = "zh-CN",
            pronunciation = "ni hao"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("translation", element["type"]?.jsonPrimitive?.content)

        val data = element["data"]?.jsonObject!!
        assertEquals(5, data.size) // sourceText, sourceLang, targetText, targetLang, pronunciation

        val actions = element["actions"]?.jsonArray!!
        assertEquals(2, actions.size)
    }
}
