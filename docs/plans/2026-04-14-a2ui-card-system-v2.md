# A2UI 卡片系统 v2 实施计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 将 A2UI 卡片系统从 v1（扁平 Map 键值对）升级为 v2（结构化数据类 + 14 种卡片 + 操作按钮）

**Architecture:** 三层架构 — 数据模型层（A2UICard sealed class）→ 解析层（JSON 解析 + 向后兼容）→ 渲染层（14 种 Compose 卡片 + 操作按钮）

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, OkHttp (for skill HTTP)

---

## Task 1: 定义 v2 数据模型 + 解析器

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/ui/A2UICardModels.kt`
- Test: `app/src/test/java/ai/openclaw/android/ui/A2UICardModelsTest.kt`

### Step 1: 定义 `A2UICard` sealed class

包含 14 种子类型，每种对应一种卡片：

```kotlin
package ai.openclaw.android.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A2UI 卡片 v2 — 统一数据模型
 *
 * 格式: {"type":"...","data":{...},"actions":[...]}
 * 向后兼容: 旧版 Map<String,String> 格式自动转为 LegacyCard
 */

// ==================== 顶层结构 ====================

@Serializable
data class A2UICard(
    @SerialName("type") val type: String,
    @SerialName("layout") val layout: String? = null,
    @SerialName("data") val rawData: Map<String, Any?> = emptyMap(),
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

enum class ButtonStyle {
    Primary,    // 主操作（确认、发送）— 实心大按钮
    Secondary   // 次操作（取消、修改）— 文字按钮
}

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

enum class ReminderMode { List, Confirm }

data class ReminderItem(
    val id: String,
    val text: String,
    val time: String,
    val status: String
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

data class CalendarItem(
    val title: String,
    val time: String,
    val location: String,
    val color: String
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

data class LocationNearby(
    val name: String,
    val distance: String
)

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

            // 尝试 v2 JSON 解析
            val card = tryParseV2(jsonStr)
            if (card != null) {
                segments.add(MessageSegment.A2UICard(card))
            } else {
                // 回退 v1 格式
                segments.add(MessageSegment.A2UICard(parseV1(jsonStr)))
            }

            cursor = endIdx + endTag.length
        }

        if (cursor < content.length) {
            val remaining = content.substring(cursor).trim()
            if (remaining.isNotEmpty()) {
                segments.add(MessageSegment.Text(remaining))
            }
        }

        if (segments.isEmpty()) {
            segments.add(MessageSegment.Text(content))
        }

        return segments
    }

    /** 尝试 v2 JSON 解析 */
    private fun tryParseV2(jsonStr: String): A2UICard? = runCatching {
        json.decodeFromString<A2UICard>(jsonStr)
    }.getOrNull()

    /** v1 旧格式解析（type\ndata\n...） */
    private fun parseV1(text: String): A2UICard {
        // 尝试 JSON（可能是简单的 type+data 格式）
        return runCatching {
            val element = json.parseToJsonElement(text).jsonObject
            val type = element["type"]?.toString()?.trim('"') ?: "generic"
            val dataObj = element["data"]?.jsonObject
            val data = dataObj?.mapValues { it.value.toString().trim('"') } ?: emptyMap()
            val actionsJson = element["actions"]
            val actions = if (actionsJson != null) {
                runCatching { json.decodeFromString<List<CardAction>>(actionsJson.toString()) }.getOrNull() ?: emptyList()
            } else emptyList()
            A2UICard(type = type, rawData = data, actions = actions)
        }.getOrElse {
            // 纯文本键值对（最老格式）
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

/** MessageSegment 升级 */
sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class A2UICard(val card: A2UICard) : MessageSegment()
}
```

### Step 2: 编写测试

```kotlin
package ai.openclaw.android.ui

import org.junit.Test
import org.junit.Assert.*

class A2UICardModelsTest {

    @Test
    fun `parse v2 weather card JSON`() {
        val json = """
            {"type":"weather","data":{"title":"西安天气","city":"西安","condition":"多云","temperature":"14","feelsLike":"12","humidity":"45","wind":"东南风 3级","forecast":[{"day":"周二","icon":"rainy","condition":"小雨","high":"16","low":"11"}]}}
        """.trimIndent()

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
        val json = """
            {"type":"weather","data":{"city":"西安","temperature":"14"},"actions":[{"label":"提醒","action":"set_reminder","style":"Primary"}]}
        """.trimIndent()

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
        val json = """
            {"type":"action_confirm","data":{"title":"确认发送","icon":"send","riskLevel":"high","description":"已准备好发送消息","details":{"联系人":"张三","消息":"晚上6点吃饭"},"warning":"此操作不可撤销"}}
        """.trimIndent()

        val card = (A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard).card
        val confirmData = card.asActionConfirmCard()
        assertNotNull(confirmData)
        assertEquals(RiskLevel.High, confirmData!!.riskLevel)
        assertEquals("张三", confirmData.details["联系人"])
        assertEquals("此操作不可撤销", confirmData.warning)
    }

    @Test
    fun `parse translation card`() {
        val json = """
            {"type":"translation","data":{"sourceText":"Hello","sourceLang":"en","targetText":"你好","targetLang":"zh-CN","pronunciation":"ni hao"}}
        """.trimIndent()

        val card = (A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard).card
        val transData = card.asTranslationCard()
        assertNotNull(transData)
        assertEquals("Hello", transData!!.sourceText)
        assertEquals("你好", transData.targetText)
        assertEquals("ni hao", transData.pronunciation)
    }

    @Test
    fun `parse error card`() {
        val json = """
            {"type":"error","data":{"icon":"warning","title":"无法获取天气","message":"网络错误","suggestion":"请检查网络"}}
        """.trimIndent()

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
        // Should not crash, fallback to some card or text
        assertTrue(segments.isNotEmpty())
    }
}
```

### Step 3: Run tests

```bash
cd /home/guuya/OpenClaw-Android-build
./gradlew :app:testDebugUnitTest --tests "ai.openclaw.android.ui.A2UICardModelsTest"
```

Expected: 8 tests PASS

### Step 4: Commit

```bash
git add app/src/main/java/ai/openclaw/android/ui/A2UICardModels.kt app/src/test/java/ai/openclaw/android/ui/A2UICardModelsTest.kt
git commit -m "feat: A2UI card v2 data models and parser

- A2UICard sealed class with 14 card subtypes
- CardAction, RiskLevel, ButtonStyle enums
- A2UICardParser with v2 JSON + v1 fallback
- 8 unit tests for parser"
```

---

## Task 2: 升级 ChatScreen 解析器

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`
- Test: `app/src/test/java/ai/openclaw/android/ChatScreenParserTest.kt`

### Step 1: 替换旧解析逻辑

在 `ChatScreen.kt` 中：
- 删除旧的 `MessageSegment` sealed class 定义（在 ChatScreen 内的）
- 删除旧的 `parseMessageContent()` 函数
- 改用 `A2UICardParser.parse()` 和新的 `MessageSegment`
- `A2UICardView` 改为接收 `A2UICard` 类型，调度到 `A2UICardRouter`

### Step 2: 测试

```kotlin
class ChatScreenParserTest {
    @Test
    fun `v1 backward compatible parsing`() { ... }
    @Test
    fun `v2 JSON parsing`() { ... }
}
```

### Step 3: Commit

```bash
git commit -m "refactor: upgrade ChatScreen to use A2UICardParser v2"
```

---

## Task 3: 重写 A2UICards.kt — 核心 P0 卡片

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/A2UICards.kt`

重写 7 种卡片：
1. `WeatherCard` — 带 actions 按钮
2. `SearchResultCard` — 列表 + 操作栏
3. `TranslationCard` — 原文/译文 + 朗读/复制按钮
4. `ReminderCard` — 列表模式 + 确认模式
5. `CalendarCard` — 日程列表 + 颜色标记
6. `LocationCard` — 位置 + 导航按钮
7. `ActionConfirmCard` — ⭐ 风险等级 + 主/次按钮

### Step 1-4: 逐个卡片 TDD

每个卡片：写 Compose 测试 → 实现 → 验证

### Step 5: Commit

---

## Task 4: 重写 A2UICards.kt — 全局卡片

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/A2UICards.kt`

新增 7 种：
8. `ErrorCard` — 错误/警告
9. `InfoCard` — 通用信息
10. `SummaryCard` — 长内容折叠/展开
11. `ContactCard` — 联系人
12. `SMSCard` — 短信确认
13. `AppCard` — 应用启动
14. `SettingsCard` — 设置变更

统一 `A2UICardRouter` + 操作按钮渲染

### Step 5: Commit

---

## Task 5: 操作按钮回调机制

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ui/A2UICards.kt`
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`

内容：
- 按钮点击回调 `onCardAction(action: CardAction)`
- 回调触发 `AgentSession.sendMessage()`
- 风险等级样式

### Commit

---

## Task 6: 天气技能适配 v2

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/skill/builtin/WeatherSkill.kt`

### Commit

---

## Task 7: 翻译 + 提醒技能适配 v2

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/skill/builtin/TranslateSkill.kt`
- Modify: `app/src/main/java/ai/openclaw/android/skill/builtin/ReminderSkill.kt`

### Commit

---

## Task 8: 兜底机制

**Files:**
- Create: `app/src/main/java/ai/openclaw/android/util/CardGenerator.kt`
- Modify: `app/src/main/java/ai/openclaw/android/agent/AgentSession.kt`（Prompt 更新）

### Commit

---

## Task 9: 最终验证 + 提交

1. `./gradlew test` — 全量测试
2. 代码审查
3. 更新 `docs/TODO-LIST.md`
4. `git commit`
5. 更新 `docs/CURRENT-STATUS-2026-04-14.md`
