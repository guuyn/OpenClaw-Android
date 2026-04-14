package ai.openclaw.android.ui

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A2UI 卡片 v2 — 统一数据模型
 *
 * 格式: {"type":"...","data":{...},"actions":[...]}
 * 向后兼容: 旧版 Map<String,String> 格式自动转为 LegacyCard
 */

// ==================== 自定义序列化器 ====================

/** Converts Map<String, Any?> to/from JSON. Needed because kotlinx.serialization
 *  doesn't support Map<String, Any?> natively. */
@OptIn(ExperimentalSerializationApi::class)
object AnyMapSerializer : KSerializer<Map<String, Any?>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AnyMap", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
        encoder.encodeSerializableValue(JsonObject.serializer(), value.toJsonObject())
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> {
        val jsonObject = decoder.decodeSerializableValue(JsonObject.serializer())
        return jsonObject.toAnyMap()
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(
        mapValues { (_, v) -> anyToJsonElement(v) }
    )

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.mapKeys { it.key.toString() }.mapValues { anyToJsonElement(it.value) })
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun JsonObject.toAnyMap(): Map<String, Any?> =
        mapValues { (_, v) -> jsonElementToAny(v) }

    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull!!
            element.doubleOrNull != null -> {
                val d = element.doubleOrNull!!
                if (d == d.toLong().toDouble()) d.toLong() else d
            }
            else -> element.content
        }
        is JsonObject -> element.toAnyMap()
        is JsonArray -> element.map { jsonElementToAny(it) }
    }
}

// ==================== 顶层结构 ====================

@Serializable
data class A2UICard(
    @SerialName("type") val type: String,
    @SerialName("layout") val layout: String? = null,
    @SerialName("data")
    @Serializable(with = AnyMapSerializer::class)
    val rawData: Map<String, Any?> = emptyMap(),
    @SerialName("actions") val actions: List<CardAction> = emptyList()
) {
    /** 类型安全访问器 */
    fun asWeatherCard(): WeatherCardData? =
        if (type == "weather") WeatherCardData.fromCard(this) else null

    fun asSearchResultCard(): SearchResultCardData? =
        if (type == "search_result") SearchResultCardData.fromCard(this) else null

    fun asTranslationCard(): TranslationCardData? =
        if (type == "translation") TranslationCardData.fromCard(this) else null

    fun asReminderCard(): ReminderCardData? =
        if (type == "reminder") ReminderCardData.fromCard(this) else null

    fun asCalendarCard(): CalendarCardData? =
        if (type == "calendar") CalendarCardData.fromCard(this) else null

    fun asLocationCard(): LocationCardData? =
        if (type == "location") LocationCardData.fromCard(this) else null

    fun asActionConfirmCard(): ActionConfirmCardData? =
        if (type == "action_confirm") ActionConfirmCardData.fromCard(this) else null

    fun asContactCard(): ContactCardData? =
        if (type == "contact") ContactCardData.fromCard(this) else null

    fun asSMSCard(): SMSCardData? =
        if (type == "sms") SMSCardData.fromCard(this) else null

    fun asAppCard(): AppCardData? =
        if (type == "app") AppCardData.fromCard(this) else null

    fun asSettingsCard(): SettingsCardData? =
        if (type == "settings") SettingsCardData.fromCard(this) else null

    fun asErrorCard(): ErrorCardData? =
        if (type == "error") ErrorCardData.fromCard(this) else null

    fun asInfoCard(): InfoCardData? =
        if (type == "info") InfoCardData.fromCard(this) else null

    fun asSummaryCard(): SummaryCardData? =
        if (type == "summary") SummaryCardData.fromCard(this) else null
}

/** 操作按钮 */
@Serializable
data class CardAction(
    @SerialName("label") val label: String,
    @SerialName("action") val action: String,
    @SerialName("style") val style: ButtonStyle = ButtonStyle.Secondary
)

/** 按钮样式 */
@Serializable
enum class ButtonStyle {
    Primary,    // 主操作（确认、发送）— 实心大按钮
    Secondary   // 次操作（取消、修改）— 文字按钮
}

/** 风险等级 */
enum class RiskLevel {
    Low, Medium, High
}

// ==================== 卡片数据类 ====================

data class WeatherCardData(
    val title: String,
    val city: String,
    val condition: String,
    val temperature: String,
    val feelsLike: String?,
    val humidity: String?,
    val wind: String?,
    val forecast: List<WeatherForecast>,
    val alert: String?
) {
    companion object {
        fun fromCard(card: A2UICard): WeatherCardData {
            val d = card.rawData
            val forecastRaw = d["forecast"] as? List<Map<String, Any?>> ?: emptyList()
            return WeatherCardData(
                title = d["title"] as? String ?: "天气",
                city = d["city"] as? String ?: "",
                condition = d["condition"] as? String ?: "",
                temperature = d["temperature"] as? String ?: "--°",
                feelsLike = d["feelsLike"] as? String,
                humidity = d["humidity"] as? String,
                wind = d["wind"] as? String,
                forecast = forecastRaw.map {
                    WeatherForecast(
                        day = (it["day"] as? String) ?: "",
                        icon = (it["icon"] as? String) ?: "",
                        condition = (it["condition"] as? String) ?: "",
                        high = (it["high"] as? String) ?: "",
                        low = (it["low"] as? String) ?: ""
                    )
                },
                alert = d["alert"] as? String
            )
        }
    }
}

data class WeatherForecast(
    val day: String,
    val icon: String,
    val condition: String,
    val high: String,
    val low: String
)

data class SearchResultCardData(
    val title: String,
    val query: String,
    val items: List<SearchResultItem>,
    val total: Int?,
    val time: String?
) {
    companion object {
        fun fromCard(card: A2UICard): SearchResultCardData {
            val d = card.rawData
            val itemsRaw = d["items"] as? List<Map<String, Any?>> ?: emptyList()
            return SearchResultCardData(
                title = d["title"] as? String ?: "搜索结果",
                query = d["query"] as? String ?: "",
                items = itemsRaw.map {
                    SearchResultItem(
                        title = (it["title"] as? String) ?: "",
                        url = (it["url"] as? String) ?: "",
                        snippet = (it["snippet"] as? String) ?: "",
                        source = (it["source"] as? String) ?: ""
                    )
                },
                total = (d["total"] as? Number)?.toInt(),
                time = d["time"] as? String
            )
        }
    }
}

data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

data class TranslationCardData(
    val sourceText: String,
    val sourceLang: String,
    val targetText: String,
    val targetLang: String,
    val pronunciation: String?
) {
    companion object {
        fun fromCard(card: A2UICard): TranslationCardData {
            val d = card.rawData
            return TranslationCardData(
                sourceText = d["sourceText"] as? String ?: "",
                sourceLang = d["sourceLang"] as? String ?: "",
                targetText = d["targetText"] as? String ?: "",
                targetLang = d["targetLang"] as? String ?: "",
                pronunciation = d["pronunciation"] as? String
            )
        }
    }
}

enum class ReminderMode { List, Confirm }

data class ReminderItem(
    val id: String,
    val text: String,
    val time: String,
    val status: String
)

data class ReminderCardData(
    val title: String,
    val count: Int?,
    val items: List<ReminderItem>,
    val mode: ReminderMode
) {
    companion object {
        fun fromCard(card: A2UICard): ReminderCardData {
            val d = card.rawData
            val layout = card.layout ?: "reminder_confirm"
            val mode = if (layout == "reminder_list") ReminderMode.List else ReminderMode.Confirm
            val itemsRaw = d["items"] as? List<Map<String, Any?>> ?: emptyList()
            return ReminderCardData(
                title = d["title"] as? String ?: "提醒",
                count = (d["count"] as? Number)?.toInt(),
                items = itemsRaw.map {
                    ReminderItem(
                        id = (it["id"] as? String) ?: "",
                        text = (it["text"] as? String) ?: "",
                        time = (it["time"] as? String) ?: "",
                        status = (it["status"] as? String) ?: "pending"
                    )
                },
                mode = mode
            )
        }
    }
}

data class CalendarItem(
    val title: String,
    val time: String,
    val location: String,
    val color: String
)

data class CalendarCardData(
    val title: String,
    val date: String,
    val items: List<CalendarItem>
) {
    companion object {
        fun fromCard(card: A2UICard): CalendarCardData {
            val d = card.rawData
            val itemsRaw = d["items"] as? List<Map<String, Any?>> ?: emptyList()
            return CalendarCardData(
                title = d["title"] as? String ?: "日程",
                date = d["date"] as? String ?: "",
                items = itemsRaw.map {
                    CalendarItem(
                        title = (it["title"] as? String) ?: "",
                        time = (it["time"] as? String) ?: "",
                        location = (it["location"] as? String) ?: "",
                        color = (it["color"] as? String) ?: "#4A90D9"
                    )
                }
            )
        }
    }
}

data class LocationNearby(
    val name: String,
    val distance: String
)

data class LocationCardData(
    val title: String,
    val address: String,
    val latitude: String?,
    val longitude: String?,
    val nearby: List<LocationNearby>
) {
    companion object {
        fun fromCard(card: A2UICard): LocationCardData {
            val d = card.rawData
            val nearbyRaw = d["nearby"] as? List<Map<String, Any?>> ?: emptyList()
            return LocationCardData(
                title = d["title"] as? String ?: "位置",
                address = d["address"] as? String ?: "",
                latitude = d["latitude"] as? String,
                longitude = d["longitude"] as? String,
                nearby = nearbyRaw.map {
                    LocationNearby(
                        name = (it["name"] as? String) ?: "",
                        distance = (it["distance"] as? String) ?: ""
                    )
                }
            )
        }
    }
}

data class ActionConfirmCardData(
    val title: String,
    val icon: String,
    val riskLevel: RiskLevel,
    val description: String,
    val details: Map<String, String>,
    val warning: String?
) {
    companion object {
        fun fromCard(card: A2UICard): ActionConfirmCardData {
            val d = card.rawData
            val riskStr = d["riskLevel"] as? String ?: "low"
            val risk = when (riskStr) {
                "high" -> RiskLevel.High
                "medium" -> RiskLevel.Medium
                else -> RiskLevel.Low
            }
            val detailsRaw = d["details"] as? Map<String, Any?> ?: emptyMap()
            return ActionConfirmCardData(
                title = d["title"] as? String ?: "确认操作",
                icon = d["icon"] as? String ?: "info",
                riskLevel = risk,
                description = d["description"] as? String ?: "",
                details = detailsRaw.mapValues { it.value?.toString() ?: "" },
                warning = d["warning"] as? String
            )
        }
    }
}

data class ContactCardData(
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String?
) {
    companion object {
        fun fromCard(card: A2UICard): ContactCardData {
            val d = card.rawData
            return ContactCardData(
                name = d["name"] as? String ?: "",
                phone = d["phone"] as? String,
                email = d["email"] as? String,
                address = d["address"] as? String
            )
        }
    }
}

data class SMSCardData(
    val recipient: String,
    val content: String,
    val status: String
) {
    companion object {
        fun fromCard(card: A2UICard): SMSCardData {
            val d = card.rawData
            return SMSCardData(
                recipient = d["recipient"] as? String ?: "",
                content = d["content"] as? String ?: "",
                status = d["status"] as? String ?: "pending"
            )
        }
    }
}

data class AppCardData(
    val appName: String,
    val packageName: String?,
    val action: String
) {
    companion object {
        fun fromCard(card: A2UICard): AppCardData {
            val d = card.rawData
            return AppCardData(
                appName = d["appName"] as? String ?: "",
                packageName = d["packageName"] as? String,
                action = d["action"] as? String ?: "launch"
            )
        }
    }
}

data class SettingsCardData(
    val settingName: String,
    val oldValue: String?,
    val newValue: String
) {
    companion object {
        fun fromCard(card: A2UICard): SettingsCardData {
            val d = card.rawData
            return SettingsCardData(
                settingName = d["settingName"] as? String ?: "",
                oldValue = d["oldValue"] as? String,
                newValue = d["newValue"] as? String ?: ""
            )
        }
    }
}

data class ErrorCardData(
    val icon: String,
    val title: String,
    val message: String,
    val suggestion: String?
) {
    companion object {
        fun fromCard(card: A2UICard): ErrorCardData {
            val d = card.rawData
            return ErrorCardData(
                icon = d["icon"] as? String ?: "warning",
                title = d["title"] as? String ?: "错误",
                message = d["message"] as? String ?: "",
                suggestion = d["suggestion"] as? String
            )
        }
    }
}

data class InfoCardData(
    val title: String?,
    val icon: String,
    val content: String,
    val summary: String?
) {
    companion object {
        fun fromCard(card: A2UICard): InfoCardData {
            val d = card.rawData
            return InfoCardData(
                title = d["title"] as? String,
                icon = d["icon"] as? String ?: "info",
                content = d["content"] as? String ?: "",
                summary = d["summary"] as? String
            )
        }
    }
}

data class SummaryCardData(
    val title: String,
    val icon: String,
    val summary: String,
    val fullContent: String
) {
    companion object {
        fun fromCard(card: A2UICard): SummaryCardData {
            val d = card.rawData
            return SummaryCardData(
                title = d["title"] as? String ?: "",
                icon = d["icon"] as? String ?: "article",
                summary = d["summary"] as? String ?: "",
                fullContent = d["fullContent"] as? String ?: ""
            )
        }
    }
}

/** 向后兼容: v1 旧格式 */
data class LegacyCard(
    val type: String,
    val data: Map<String, String>
)

// ==================== MessageSegment ====================

sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class A2UICard(val card: ai.openclaw.android.ui.A2UICard) : MessageSegment()
}

// ==================== 解析器 ====================

object A2UICardParser {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * 解析 [A2UI]...[/A2UI] 内容
     * 优先尝试 v2 JSON 格式，失败则回退 v1 旧格式
     */
    fun parse(content: String): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        val startTag = "[A2UI]"
        val endTag = "[/A2UI]"
        var cursor = 0

        while (cursor < content.length) {
            val startIdx = content.indexOf(startTag, cursor)
            if (startIdx == -1) break

            // Text before tag
            if (startIdx > cursor) {
                val textBefore = content.substring(cursor, startIdx).trim()
                if (textBefore.isNotEmpty()) {
                    segments.add(MessageSegment.Text(textBefore))
                }
            }

            val jsonStart = startIdx + startTag.length
            val endIdx = content.indexOf(endTag, jsonStart)
            if (endIdx == -1) break

            val jsonStr = content.substring(jsonStart, endIdx).trim()

            // Try v2 JSON parse
            val card = tryParseV2(jsonStr)
            if (card != null) {
                segments.add(MessageSegment.A2UICard(card))
            } else {
                // Fallback to v1 format
                segments.add(MessageSegment.A2UICard(parseV1(jsonStr)))
            }

            cursor = endIdx + endTag.length
        }

        // Remaining text after last tag
        if (cursor < content.length) {
            val remaining = content.substring(cursor).trim()
            if (remaining.isNotEmpty()) {
                segments.add(MessageSegment.Text(remaining))
            }
        }

        // No tags found at all — treat entire content as text
        if (segments.isEmpty()) {
            segments.add(MessageSegment.Text(content))
        }

        return segments
    }

    /** 尝试 v2 JSON 解析 */
    private fun tryParseV2(jsonStr: String): A2UICard? = runCatching {
        json.decodeFromString<A2UICard>(jsonStr)
    }.getOrNull()

    /** v1 旧格式解析（type\ndata\n... 或简单 JSON） */
    private fun parseV1(text: String): A2UICard {
        // Try JSON first (might be simple type+data format)
        return runCatching {
            val element = json.parseToJsonElement(text).jsonObject
            val type = element["type"]?.jsonPrimitive?.content ?: "generic"
            val dataObj = element["data"]?.jsonObject
            val data = dataObj?.mapValues { (_, v) ->
                when {
                    v is JsonPrimitive && v.isString -> v.jsonPrimitive.content
                    v is JsonPrimitive -> v.jsonPrimitive.content
                    else -> v.toString()
                }
            } ?: emptyMap()
            val actionsJson = element["actions"]
            val actions = if (actionsJson != null) {
                runCatching { json.decodeFromString<List<CardAction>>(actionsJson.toString()) }.getOrNull() ?: emptyList()
            } else emptyList()
            A2UICard(type = type, rawData = data, actions = actions)
        }.getOrElse {
            // Plain text key-value pairs (oldest format)
            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val type = lines.firstOrNull() ?: "generic"
            val data = lines.drop(1).mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                else null
            }.toMap()
            A2UICard(type = type, rawData = data)
        }
    }
}
