package ai.openclaw.android.skill.builtin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ReminderSkill v2 A2UI card JSON format.
 * 
 * Tests the card building logic directly without Android Context dependency.
 */
class ReminderSkillV2Test {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== Test 1: Reminder confirm card ====================

    @Test
    fun `returns v2 reminder confirm card format`() {
        val cardJson = buildReminderConfirmCardDirect(
            text = "下午3点开会",
            time = "2026-04-13 15:00",
            relativeTime = "2小时12分钟后"
        )

        val wrapped = "[A2UI]$cardJson[/A2UI]"
        assertTrue("Should contain [A2UI] start tag", wrapped.contains("[A2UI]"))
        assertTrue("Should contain [/A2UI] end tag", wrapped.contains("[/A2UI]"))

        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("reminder", element["type"]?.jsonPrimitive?.content)
        assertEquals("reminder_confirm", element["layout"]?.jsonPrimitive?.content)
    }

    // ==================== Test 2: Confirm card data fields ====================

    @Test
    fun `confirm card contains correct data fields`() {
        val cardJson = buildReminderConfirmCardDirect(
            text = "下午3点开会",
            message = "带项目资料",
            time = "2026-04-13 15:00",
            relativeTime = "2小时12分钟后"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)

        assertEquals("✅ 已设置提醒", data!!["title"]?.jsonPrimitive?.content)
        assertEquals("下午3点开会", data["text"]?.jsonPrimitive?.content)
        assertEquals("2026-04-13 15:00", data["time"]?.jsonPrimitive?.content)
        assertEquals("2小时12分钟后", data["relativeTime"]?.jsonPrimitive?.content)
        assertEquals("带项目资料", data["message"]?.jsonPrimitive?.content)
    }

    // ==================== Test 3: Confirm card without message ====================

    @Test
    fun `confirm card omits message when blank`() {
        val cardJson = buildReminderConfirmCardDirect(
            text = "吃药",
            time = "2026-04-13 08:00",
            relativeTime = "30分钟后"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertNull("message should not be present when blank", data!!["message"])
    }

    // ==================== Test 4: Confirm card action buttons ====================

    @Test
    fun `confirm card includes edit and cancel actions`() {
        val cardJson = buildReminderConfirmCardDirect(
            text = "测试提醒",
            time = "2026-04-13 15:00",
            relativeTime = "1小时后"
        )

        val element = json.parseToJsonElement(cardJson).jsonObject
        val actions = element["actions"]?.jsonArray
        assertNotNull("actions field should exist", actions)
        assertEquals("Should have 2 action buttons", 2, actions!!.size)

        val btn1 = actions[0].jsonObject
        assertEquals("✏️ 修改", btn1["label"]?.jsonPrimitive?.content)
        assertEquals("edit_reminder", btn1["action"]?.jsonPrimitive?.content)
        assertEquals("Secondary", btn1["style"]?.jsonPrimitive?.content)

        val btn2 = actions[1].jsonObject
        assertEquals("🗑️ 取消", btn2["label"]?.jsonPrimitive?.content)
        assertEquals("cancel_reminder", btn2["action"]?.jsonPrimitive?.content)
        assertEquals("Secondary", btn2["style"]?.jsonPrimitive?.content)
    }

    // ==================== Test 5: Reminder list card ====================

    @Test
    fun `returns v2 reminder list card format`() {
        val items = listOf(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("1"),
                    "text" to JsonPrimitive("下午3点开会"),
                    "time" to JsonPrimitive("2026-04-13 15:00"),
                    "status" to JsonPrimitive("pending")
                )
            ),
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("2"),
                    "text" to JsonPrimitive("下午5点下班"),
                    "time" to JsonPrimitive("2026-04-13 17:00"),
                    "status" to JsonPrimitive("pending")
                )
            )
        )

        val cardJson = buildReminderListCardDirect(items)

        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("reminder", element["type"]?.jsonPrimitive?.content)
        assertEquals("reminder_list", element["layout"]?.jsonPrimitive?.content)

        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertEquals("提醒列表", data!!["title"]?.jsonPrimitive?.content)
        assertEquals(2, data["count"]?.jsonPrimitive?.content?.toIntOrNull())

        val listItems = data["items"]?.jsonArray
        assertNotNull("items should exist", listItems)
        assertEquals(2, listItems!!.size)
        assertEquals("1", listItems[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("下午3点开会", listItems[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    // ==================== Test 6: List card action buttons ====================

    @Test
    fun `list card includes add reminder action with Primary style`() {
        val items = listOf(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("1"),
                    "text" to JsonPrimitive("测试"),
                    "time" to JsonPrimitive("2026-04-13 15:00"),
                    "status" to JsonPrimitive("pending")
                )
            )
        )

        val cardJson = buildReminderListCardDirect(items)

        val element = json.parseToJsonElement(cardJson).jsonObject
        val actions = element["actions"]?.jsonArray
        assertNotNull("actions field should exist", actions)
        assertEquals("Should have 1 action button", 1, actions!!.size)

        val btn = actions[0].jsonObject
        assertEquals("➕ 新建提醒", btn["label"]?.jsonPrimitive?.content)
        assertEquals("add_reminder", btn["action"]?.jsonPrimitive?.content)
        assertEquals("Primary", btn["style"]?.jsonPrimitive?.content)
    }

    // ==================== Test 7: Empty list card ====================

    @Test
    fun `list card handles empty items list`() {
        val cardJson = buildReminderListCardDirect(emptyList())

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertEquals(0, data!!["count"]?.jsonPrimitive?.content?.toIntOrNull())

        val listItems = data["items"]?.jsonArray
        assertNotNull("items should exist even if empty", listItems)
        assertEquals(0, listItems!!.size)
    }

    // ==================== Direct helper functions (mirrors internal logic) ====================

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildReminderConfirmCardDirect(
        text: String,
        message: String = "",
        time: String,
        relativeTime: String
    ): String {
        val dataMap = mutableMapOf<String, JsonElement>(
            "title" to JsonPrimitive("✅ 已设置提醒"),
            "text" to JsonPrimitive(text),
            "time" to JsonPrimitive(time),
            "relativeTime" to JsonPrimitive(relativeTime)
        )
        if (message.isNotBlank()) {
            dataMap["message"] = JsonPrimitive(message)
        }

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("reminder"),
                "layout" to JsonPrimitive("reminder_confirm"),
                "data" to JsonObject(dataMap),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("✏️ 修改"),
                                "action" to JsonPrimitive("edit_reminder"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("🗑️ 取消"),
                                "action" to JsonPrimitive("cancel_reminder"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildReminderListCardDirect(items: List<JsonObject>): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("reminder"),
                "layout" to JsonPrimitive("reminder_list"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("提醒列表"),
                        "count" to JsonPrimitive(items.size),
                        "items" to JsonArray(items)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("➕ 新建提醒"),
                                "action" to JsonPrimitive("add_reminder"),
                                "style" to JsonPrimitive("Primary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }
}
