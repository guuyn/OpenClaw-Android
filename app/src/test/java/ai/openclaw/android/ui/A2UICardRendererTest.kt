package ai.openclaw.android.ui

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for A2UI v2 card rendering data models.
 * Tests data parsing and transformation logic (not Compose UI rendering).
 */
class A2UICardRendererTest {

    @Test
    fun `weather card data parsing`() {
        val json = """{
            "type":"weather",
            "data":{
                "title":"天气",
                "city":"西安",
                "condition":"多云",
                "temperature":"14°",
                "feelsLike":"12°",
                "humidity":"45%",
                "wind":"东南风 3级",
                "alert":"明天有雨，记得带伞",
                "forecast":[
                    {"day":"周二","icon":"rainy","condition":"小雨","high":"16","low":"11"},
                    {"day":"周三","icon":"cloudy","condition":"阴","high":"18","low":"12"},
                    {"day":"周四","icon":"sunny","condition":"晴","high":"22","low":"14"}
                ]
            },
            "actions":[
                {"label":"⏰ 提醒","action":"set_reminder","style":"Secondary"},
                {"label":"7天","action":"view_7day","style":"Secondary"},
                {"label":"分享","action":"share","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard
        val weather = card.card.asWeatherCard()

        assertNotNull(weather)
        assertEquals("西安", weather!!.city)
        assertEquals("多云", weather.condition)
        assertEquals("14°", weather.temperature)
        assertEquals("12°", weather.feelsLike)
        assertEquals("45%", weather.humidity)
        assertEquals("东南风 3级", weather.wind)
        assertEquals("明天有雨，记得带伞", weather.alert)
        assertEquals(3, weather.forecast.size)
        assertEquals("周二", weather.forecast[0].day)
        assertEquals("16", weather.forecast[0].high)
        assertEquals("11", weather.forecast[0].low)
        assertEquals(3, card.card.actions.size)
    }

    @Test
    fun `search result card data parsing`() {
        val json = """{
            "type":"search_result",
            "data":{
                "title":"搜索结果",
                "query":"OpenClaw Android",
                "total":128,
                "time":"0.42秒",
                "items":[
                    {"title":"OpenClaw - AI Agent Framework","url":"https://openclaw.ai","snippet":"OpenClaw is an AI agent framework","source":"openclaw.ai"},
                    {"title":"GitHub - openclaw/android","url":"https://github.com/openclaw/android","snippet":"Android app for OpenClaw","source":"github.com"}
                ]
            },
            "actions":[
                {"label":"🌐 打开网页","action":"open_browser","style":"Secondary"},
                {"label":"📋 复制","action":"copy","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard
        val search = card.card.asSearchResultCard()

        assertNotNull(search)
        assertEquals("OpenClaw Android", search!!.query)
        assertEquals(128, search.total)
        assertEquals("0.42秒", search.time)
        assertEquals(2, search.items.size)
        assertEquals("OpenClaw - AI Agent Framework", search.items[0].title)
        assertEquals("https://openclaw.ai", search.items[0].url)
        assertEquals("openclaw.ai", search.items[0].source)
        assertEquals(2, card.card.actions.size)
    }

    @Test
    fun `translation card data parsing`() {
        val json = """{
            "type":"translation",
            "data":{
                "sourceText":"Hello, how are you?",
                "sourceLang":"en",
                "targetText":"你好，你好吗？",
                "targetLang":"zh-CN",
                "pronunciation":"nǐ hǎo, nǐ hǎo ma?"
            },
            "actions":[
                {"label":"🔊 朗读","action":"speak","style":"Secondary"},
                {"label":"📋 复制","action":"copy","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard
        val trans = card.card.asTranslationCard()

        assertNotNull(trans)
        assertEquals("Hello, how are you?", trans!!.sourceText)
        assertEquals("en", trans.sourceLang)
        assertEquals("你好，你好吗？", trans.targetText)
        assertEquals("zh-CN", trans.targetLang)
        assertEquals("nǐ hǎo, nǐ hǎo ma?", trans.pronunciation)
    }

    @Test
    fun `action confirm card risk levels`() {
        // Low risk
        val lowJson = """{"type":"action_confirm","data":{"title":"确认操作","icon":"check","riskLevel":"low","description":"打开天气应用","details":{"应用":"天气"}}}"""
        val lowCard = (A2UICardParser.parse("[A2UI]$lowJson[/A2UI]")[0] as MessageSegment.A2UICard).card
        val lowConfirm = lowCard.asActionConfirmCard()
        assertNotNull(lowConfirm)
        assertEquals(RiskLevel.Low, lowConfirm!!.riskLevel)

        // Medium risk
        val medJson = """{"type":"action_confirm","data":{"title":"确认操作","icon":"edit","riskLevel":"medium","description":"修改系统设置","details":{"设置":"亮度"}}}"""
        val medCard = (A2UICardParser.parse("[A2UI]$medJson[/A2UI]")[0] as MessageSegment.A2UICard).card
        val medConfirm = medCard.asActionConfirmCard()
        assertNotNull(medConfirm)
        assertEquals(RiskLevel.Medium, medConfirm!!.riskLevel)

        // High risk
        val highJson = """{"type":"action_confirm","data":{"title":"确认删除","icon":"warning","riskLevel":"high","description":"删除所有聊天记录","details":{"影响范围":"全部"},"warning":"此操作不可撤销！"}}"""
        val highCard = (A2UICardParser.parse("[A2UI]$highJson[/A2UI]")[0] as MessageSegment.A2UICard).card
        val highConfirm = highCard.asActionConfirmCard()
        assertNotNull(highConfirm)
        assertEquals(RiskLevel.High, highConfirm!!.riskLevel)
        assertEquals("此操作不可撤销！", highConfirm.warning)
        assertEquals("全部", highConfirm.details["影响范围"])
    }

    @Test
    fun `calendar card data parsing`() {
        val json = """{
            "type":"calendar",
            "data":{
                "title":"今日日程",
                "date":"2026-04-14",
                "items":[
                    {"title":"团队会议","time":"09:00 - 10:00","location":"会议室 A","color":"#4A90D9"},
                    {"title":"午餐","time":"12:00 - 13:00","location":"食堂","color":"#50C878"},
                    {"title":"代码审查","time":"15:00 - 16:00","location":"","color":"#FF6B6B"}
                ]
            },
            "actions":[
                {"label":"➕ 新建日程","action":"new_event","style":"Primary"},
                {"label":"📅 查看明天","action":"view_tomorrow","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard
        val cal = card.card.asCalendarCard()

        assertNotNull(cal)
        assertEquals("2026-04-14", cal!!.date)
        assertEquals(3, cal.items.size)
        assertEquals("团队会议", cal.items[0].title)
        assertEquals("09:00 - 10:00", cal.items[0].time)
        assertEquals("会议室 A", cal.items[0].location)
        assertEquals("#4A90D9", cal.items[0].color)
        assertEquals("", cal.items[2].location)
        assertEquals(2, card.card.actions.size)
    }

    @Test
    fun `reminder card list vs confirm mode`() {
        // List mode
        val listJson = """{
            "type":"reminder",
            "layout":"reminder_list",
            "data":{
                "title":"提醒列表",
                "count":3,
                "items":[
                    {"id":"r1","text":"买牛奶","time":"2026-04-14 10:00","status":"pending"},
                    {"id":"r2","text":"回复邮件","time":"2026-04-14 14:00","status":"completed"}
                ]
            },
            "actions":[
                {"label":"➕ 新建","action":"new_reminder","style":"Primary"}
            ]
        }"""
        val listCard = (A2UICardParser.parse("[A2UI]$listJson[/A2UI]")[0] as MessageSegment.A2UICard).card
        val listReminder = listCard.asReminderCard()
        assertNotNull(listReminder)
        assertEquals(ReminderMode.List, listReminder!!.mode)
        assertEquals(3, listReminder.count)
        assertEquals(2, listReminder.items.size)
        assertEquals("pending", listReminder.items[0].status)
        assertEquals("completed", listReminder.items[1].status)

        // Confirm mode
        val confirmJson = """{
            "type":"reminder",
            "data":{
                "title":"确认提醒",
                "items":[{"id":"r3","text":"下午3点开会","time":"2026-04-14 15:00","status":"pending"}]
            }
        }"""
        val confirmCard = (A2UICardParser.parse("[A2UI]$confirmJson[/A2UI]")[0] as MessageSegment.A2UICard).card
        val confirmReminder = confirmCard.asReminderCard()
        assertNotNull(confirmReminder)
        assertEquals(ReminderMode.Confirm, confirmReminder!!.mode)
        assertEquals("下午3点开会", confirmReminder.items[0].text)
        assertEquals("2026-04-14 15:00", confirmReminder.items[0].time)
    }

    @Test
    fun `location card data parsing`() {
        val json = """{
            "type":"location",
            "data":{
                "title":"当前位置",
                "address":"陕西省西安市雁塔区科技路",
                "latitude":"34.2264",
                "longitude":"108.9398",
                "nearby":[
                    {"name":"大雁塔","distance":"2.3km"},
                    {"name":"钟楼","distance":"5.1km"}
                ]
            },
            "actions":[
                {"label":"🧭 导航","action":"navigate","style":"Primary"},
                {"label":"📤 分享位置","action":"share","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard
        val loc = card.card.asLocationCard()

        assertNotNull(loc)
        assertEquals("陕西省西安市雁塔区科技路", loc!!.address)
        assertEquals("34.2264", loc.latitude)
        assertEquals("108.9398", loc.longitude)
        assertEquals(2, loc.nearby.size)
        assertEquals("大雁塔", loc.nearby[0].name)
        assertEquals("2.3km", loc.nearby[0].distance)
        assertEquals("🧭 导航", card.card.actions[0].label)
    }

    @Test
    fun `card action buttons parsing`() {
        val json = """{
            "type":"action_confirm",
            "data":{"title":"测试","icon":"test","riskLevel":"low","description":"测试描述","details":{}},
            "actions":[
                {"label":"✅ 确认","action":"confirm","style":"Primary"},
                {"label":"✏️ 修改","action":"edit","style":"Secondary"},
                {"label":"❌ 取消","action":"cancel","style":"Secondary"}
            ]
        }"""
        val card = A2UICardParser.parse("[A2UI]$json[/A2UI]")[0] as MessageSegment.A2UICard

        assertEquals(3, card.card.actions.size)

        val primary = card.card.actions.find { it.action == "confirm" }
        assertNotNull(primary)
        assertEquals(ButtonStyle.Primary, primary!!.style)

        val secondary = card.card.actions.filter { it.style == ButtonStyle.Secondary }
        assertEquals(2, secondary.size)

        val cancelAction = card.card.actions.find { it.action == "cancel" }
        assertNotNull(cancelAction)
        assertEquals("❌ 取消", cancelAction!!.label)
    }
}
