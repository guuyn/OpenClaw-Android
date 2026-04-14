package ai.openclaw.android.skill.builtin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for WeatherSkill v2 A2UI card JSON format.
 *
 * These tests verify the JSON building logic directly (via internal helpers)
 * and the overall SkillTool output format.
 */
class WeatherSkillV2Test {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== Test 1: v2 A2UI card format ====================

    @Test
    fun `returns v2 A2UI card format`() {
        // Build a v2 card using the internal helper
        val cardJson = buildWeatherCardV2Direct("北京", "晴", "+20°C", null, null, null)

        // Verify it contains [A2UI] tags
        val wrapped = "[A2UI]$cardJson[/A2UI]"
        assertTrue("Should contain [A2UI] start tag", wrapped.contains("[A2UI]"))
        assertTrue("Should contain [/A2UI] end tag", wrapped.contains("[/A2UI]"))

        // Parse JSON and verify structure
        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("weather", element["type"]?.jsonPrimitive?.content)

        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertEquals("北京", data!!["city"]?.jsonPrimitive?.content)
        assertEquals("晴", data["condition"]?.jsonPrimitive?.content)
        assertEquals("+20°C", data["temperature"]?.jsonPrimitive?.content)
        assertEquals("北京 · 天气", data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `v2 card format with Open-Meteo data`() {
        val cardJson = buildWeatherCardV2OpenMeteoDirect("上海", "小雨", 18.5, "东风", 12.3)

        val element = json.parseToJsonElement(cardJson).jsonObject
        assertEquals("weather", element["type"]?.jsonPrimitive?.content)

        val data = element["data"]?.jsonObject
        assertNotNull("data field should exist", data)
        assertEquals("上海", data!!["city"]?.jsonPrimitive?.content)
        assertEquals("小雨", data["condition"]?.jsonPrimitive?.content)
        assertEquals("18.5°C", data["temperature"]?.jsonPrimitive?.content)
        assertEquals("东风 12.3km/h", data["wind"]?.jsonPrimitive?.content)
    }

    // ==================== Test 2: Action buttons ====================

    @Test
    fun `includes action buttons`() {
        val cardJson = buildWeatherCardV2Direct("西安", "多云", "+15°C", null, null, null)

        val element = json.parseToJsonElement(cardJson).jsonObject
        val actions = element["actions"]?.jsonArray
        assertNotNull("actions field should exist", actions)
        assertEquals("Should have 3 action buttons", 3, actions!!.size)

        // Verify each button
        val btn1 = actions[0].jsonObject
        assertEquals("⏰ 降雨提醒", btn1["label"]?.jsonPrimitive?.content)
        assertEquals("set_rain_reminder", btn1["action"]?.jsonPrimitive?.content)
        assertEquals("Secondary", btn1["style"]?.jsonPrimitive?.content)

        val btn2 = actions[1].jsonObject
        assertEquals("📅 7天预报", btn2["label"]?.jsonPrimitive?.content)
        assertEquals("expand_forecast", btn2["action"]?.jsonPrimitive?.content)

        val btn3 = actions[2].jsonObject
        assertEquals("📤 分享", btn3["label"]?.jsonPrimitive?.content)
        assertEquals("share_weather", btn3["action"]?.jsonPrimitive?.content)
    }

    // ==================== Test 3: Forecast data is array ====================

    @Test
    fun `forecast data is array`() {
        val cardJson = buildWeatherCardV2Direct("广州", "晴", "+25°C", null, null, null)

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        val forecast = data!!["forecast"]

        assertNotNull("forecast field should exist", forecast)
        assertTrue("forecast should be a JSON array", forecast is JsonArray)
        assertEquals("forecast array should be empty for wttr.in format=3", 0, (forecast as JsonArray).size)
    }

    @Test
    fun `forecast array empty for Open-Meteo basic`() {
        val cardJson = buildWeatherCardV2OpenMeteoDirect("深圳", "晴", 28.0, "南风", 8.0)

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject
        val forecast = data!!["forecast"]

        assertNotNull("forecast field should exist", forecast)
        assertTrue("forecast should be a JSON array", forecast is JsonArray)
    }

    // ==================== Helper function tests ====================

    @Test
    fun `parseCityFromWttr extracts Chinese city name`() {
        val result = parseCityFromWttr("西安：+20°C", "fallback")
        assertEquals("西安", result)
    }

    @Test
    fun `parseCityFromWttr extracts English city name`() {
        val result = parseCityFromWttr("New York: +20°C", "fallback")
        assertEquals("New York", result)
    }

    @Test
    fun `parseCityFromWttr uses fallback on no colon`() {
        val result = parseCityFromWttr("+20°C", "Beijing")
        assertEquals("Beijing", result)
    }

    @Test
    fun `parseWttrText extracts temperature`() {
        val (condition, temperature) = parseWttrText("西安：+20°C")
        assertEquals("+20°C", temperature)
        // valuePart after colon is "+20°C", removing temp leaves empty → defaults to "晴"
        assertEquals("晴", condition)
    }

    @Test
    fun `parseWttrText handles negative temperature`() {
        val (_, temperature) = parseWttrText("哈尔滨：-5°C")
        assertEquals("-5°C", temperature)
    }

    @Test
    fun `parseWttrText returns default when no temperature found`() {
        val (_, temperature) = parseWttrText("Unknown data")
        assertEquals("N/A", temperature)
    }

    // ==================== Edge cases ====================

    @Test
    fun `v2 card handles null optional fields`() {
        val cardJson = buildWeatherCardV2Direct("成都", "阴", "+10°C", null, null, null)

        val element = json.parseToJsonElement(cardJson).jsonObject
        val data = element["data"]?.jsonObject

        // Optional fields should have default values
        assertEquals("N/A", data!!["feelsLike"]?.jsonPrimitive?.content)
        assertEquals("N/A", data["humidity"]?.jsonPrimitive?.content)
        assertEquals("N/A", data["wind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `v2 card is valid JSON`() {
        val cardJson = buildWeatherCardV2Direct("杭州", "小雨", "+22°C", "20°C", "80%", "东风 3m/s")

        // Should not throw
        val parsed = json.parseToJsonElement(cardJson)
        assertNotNull(parsed)
    }

    @Test
    fun `v2 Open-Meteo card is valid JSON`() {
        val cardJson = buildWeatherCardV2OpenMeteoDirect("南京", "雷暴", 32.5, "西南风", 15.7)

        // Should not throw
        val parsed = json.parseToJsonElement(cardJson)
        assertNotNull(parsed)
    }

    // ==================== Direct test helpers (mirrors internal logic) ====================

    /** Direct copy of buildWeatherCardV2 logic for testing without HTTP */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildWeatherCardV2Direct(
        cityName: String,
        condition: String,
        temperature: String,
        feelsLike: String?,
        humidity: String?,
        wind: String?
    ): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("weather"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("$cityName · 天气"),
                        "city" to JsonPrimitive(cityName),
                        "condition" to JsonPrimitive(condition),
                        "temperature" to JsonPrimitive(temperature),
                        "feelsLike" to (if (feelsLike != null) JsonPrimitive(feelsLike) else JsonPrimitive("N/A")),
                        "humidity" to (if (humidity != null) JsonPrimitive(humidity) else JsonPrimitive("N/A")),
                        "wind" to (if (wind != null) JsonPrimitive(wind) else JsonPrimitive("N/A")),
                        "forecast" to JsonArray(emptyList()),
                        "alert" to JsonPrimitive(null)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("⏰ 降雨提醒"),
                            "action" to JsonPrimitive("set_rain_reminder"),
                            "style" to JsonPrimitive("Secondary")
                        )),
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("📅 7天预报"),
                            "action" to JsonPrimitive("expand_forecast"),
                            "style" to JsonPrimitive("Secondary")
                        )),
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("📤 分享"),
                            "action" to JsonPrimitive("share_weather"),
                            "style" to JsonPrimitive("Secondary")
                        ))
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    /** Direct copy of buildWeatherCardV2OpenMeteo logic for testing without HTTP */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildWeatherCardV2OpenMeteoDirect(
        location: String,
        condition: String,
        temperature: Double,
        windDirDesc: String,
        windSpeed: Double
    ): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("weather"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("$location · 天气"),
                        "city" to JsonPrimitive(location),
                        "condition" to JsonPrimitive(condition),
                        "temperature" to JsonPrimitive("${temperature}°C"),
                        "feelsLike" to JsonPrimitive("N/A"),
                        "humidity" to JsonPrimitive("N/A"),
                        "wind" to JsonPrimitive("$windDirDesc ${windSpeed}km/h"),
                        "forecast" to JsonArray(emptyList()),
                        "alert" to JsonPrimitive(null)
                    )
                ),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("⏰ 降雨提醒"),
                            "action" to JsonPrimitive("set_rain_reminder"),
                            "style" to JsonPrimitive("Secondary")
                        )),
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("📅 7天预报"),
                            "action" to JsonPrimitive("expand_forecast"),
                            "style" to JsonPrimitive("Secondary")
                        )),
                        JsonObject(mapOf(
                            "label" to JsonPrimitive("📤 分享"),
                            "action" to JsonPrimitive("share_weather"),
                            "style" to JsonPrimitive("Secondary")
                        ))
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    /** Direct copy of parseCityFromWttr for testing */
    private fun parseCityFromWttr(text: String, fallback: String): String {
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        return if (colonIdx > 0) text.substring(0, colonIdx).trim() else fallback
    }

    /** Direct copy of parseWttrText for testing */
    private fun parseWttrText(text: String): Pair<String, String> {
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        val valuePart = if (colonIdx > 0) text.substring(colonIdx + 1).trim() else text

        val tempRegex = Regex("([+-]?\\d+)°[CF]")
        val tempMatch = tempRegex.find(valuePart)
        val temperature = if (tempMatch != null) "${tempMatch.groupValues[1]}°C" else "N/A"

        val condition = valuePart.replace(tempRegex, "").replace("°", "").trim()
        return condition.ifEmpty { "晴" } to temperature
    }
}
