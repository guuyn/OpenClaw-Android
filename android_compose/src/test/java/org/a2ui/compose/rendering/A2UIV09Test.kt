package org.a2ui.compose.rendering

import org.junit.Test
import org.junit.Assert.*
import org.a2ui.compose.data.*

/**
 * v0.9 协议兼容性测试
 * 使用模型实际输出的格式验证渲染器是否正确解析
 */
class A2UIV09Test {

    private val renderer = A2UIRenderer()

    // ============ 测试模型实际输出的 v0.9 天气卡片 ============

    @Test
    fun v09WeatherCardModelOutput() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"weather_xian","catalogId":"app_catalog"}}""")

        val r2 = renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"weather_xian","components":[{"id":"root","component":"Card","child":"content"},{"id":"content","component":"Column","children":["title","temp","condition","details"]},{"id":"title","component":"Text","text":"西安","variant":"h3"},{"id":"temp","component":"Text","text":"21°C","variant":"h1"},{"id":"condition","component":"Text","text":"多云"},{"id":"details","component":"Text","text":"湿度: 45%","variant":"caption"}]}}""")
        assertTrue("updateComponents 应成功: ${r2.exceptionOrNull()?.message}", r2.isSuccess)

        val root = renderer.getComponent("weather_xian", "root")
        assertNotNull("root 组件应存在", root)
        assertEquals("Card", root?.component)
        assertEquals("content", root?.child)

        val content = renderer.getComponent("weather_xian", "content")
        assertNotNull("content 组件应存在", content)
        assertEquals("Column", content?.component)

        val children = (content?.children as? ChildList.ArrayChildList)?.array
        assertEquals(listOf("title", "temp", "condition", "details"), children)

        val title = renderer.getComponent("weather_xian", "title")
        assertEquals("西安", (title?.text as? DynamicValue.LiteralValue)?.literal)
        assertEquals("h3", title?.variant)

        val temp = renderer.getComponent("weather_xian", "temp")
        assertEquals("21°C", (temp?.text as? DynamicValue.LiteralValue)?.literal)
    }

    // ============ 测试 v0.9 纯数组 children 格式 ============

    @Test
    fun v09PureArrayChildren() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test1","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test1","components":[{"id":"root","component":"Column","children":["t1","t2","t3"]},{"id":"t1","component":"Text","text":"第一行"},{"id":"t2","component":"Text","text":"第二行"},{"id":"t3","component":"Text","text":"第三行"}]}}""")

        val root = renderer.getComponent("test1", "root")
        assertNotNull(root)
        val children = root?.children as? ChildList.ArrayChildList
        assertNotNull(children)
        assertEquals(listOf("t1", "t2", "t3"), children?.array)
        assertNotNull(renderer.getComponent("test1", "t1"))
        assertNotNull(renderer.getComponent("test1", "t2"))
        assertNotNull(renderer.getComponent("test1", "t3"))
    }

    // ============ 测试 v0.9 纯字符串 text 格式 ============

    @Test
    fun v09PureStringText() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test2","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test2","components":[{"id":"t1","component":"Text","text":"纯字符串测试"}]}}""")

        val t1 = renderer.getComponent("test2", "t1")
        assertNotNull(t1)
        val textVal = t1?.text as? DynamicValue.LiteralValue
        assertNotNull(textVal)
        assertEquals("纯字符串测试", textVal?.literal)
    }

    // ============ 测试 v0.9 backward compat: 旧 {"array":[...]} 格式仍可用 ============

    @Test
    fun v09CompatOldArrayFormat() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test3","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test3","components":[{"id":"root","component":"Column","children":{"array":["a","b"]}},{"id":"a","component":"Text","text":"A"},{"id":"b","component":"Text","text":"B"}]}}""")

        val root = renderer.getComponent("test3", "root")
        val children = root?.children as? ChildList.ArrayChildList
        assertNotNull(children)
        assertEquals(listOf("a", "b"), children?.array)
    }

    // ============ 测试 v0.9 ChoicePicker selections 字段 ============

    @Test
    fun v09ChoicePickerSelections() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test4","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test4","components":[{"id":"cp","component":"ChoicePicker","options":[{"label":"选项A","value":"a"},{"label":"选项B","value":"b"}],"selections":{"path":"/selected"},"variant":"multipleSelection","maxAllowedSelections":2,"label":"请选择"}]}}""")

        val cp = renderer.getComponent("test4", "cp")
        assertNotNull(cp)
        assertEquals("ChoicePicker", cp?.component)
        assertNotNull(cp?.selections)
        assertEquals(2, cp?.maxAllowedSelections)
        assertEquals("multipleSelection", cp?.variant)
        assertEquals(2, cp?.options?.size)
    }

    // ============ 测试 v0.9 Slider minValue/maxValue 字段 ============

    @Test
    fun v09SliderMinMaxValue() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test5","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test5","components":[{"id":"slider","component":"Slider","value":{"path":"/volume"},"minValue":0,"maxValue":100,"step":5,"label":"音量"}]}}""")

        val slider = renderer.getComponent("test5", "slider")
        assertNotNull(slider)
        assertEquals("Slider", slider?.component)
        assertEquals(0.0, slider?.minValue)
        assertEquals(100.0, slider?.maxValue)
        assertEquals(5.0, slider?.step)
    }

    // ============ 测试 v0.9 Button 使用 child + action event 格式 ============

    @Test
    fun v09ButtonChildActionEvent() {
        renderer.processMessage("""{"version":"v0.9","createSurface":{"surfaceId":"test6","catalogId":"app"}}""")

        renderer.processMessage("""{"version":"v0.9","updateComponents":{"surfaceId":"test6","components":[{"id":"btn","component":"Button","child":"btn_label","action":{"event":{"name":"click_action"}},"variant":"primary"},{"id":"btn_label","component":"Text","text":"点击我"}]}}""")

        val btn = renderer.getComponent("test6", "btn")
        assertNotNull(btn)
        assertEquals("Button", btn?.component)
        assertEquals("btn_label", btn?.child)
        assertEquals("primary", btn?.variant)
        assertNotNull(btn?.action?.event)
        assertEquals("click_action", btn?.action?.event?.name)
    }

    // ============ 测试 JSONL 多操作格式（每行一个完整 JSON） ============

    @Test
    fun jsonlMultiOperations() {
        // JSONL 格式：每行一个完整 JSON 对象
        val line1 = """{"version":"v0.9","createSurface":{"surfaceId":"jsonl_test","catalogId":"app"}}"""
        val line2 = """{"version":"v0.9","updateComponents":{"surfaceId":"jsonl_test","components":[{"id":"root","component":"Text","text":"Hello"}]}}"""

        val r1 = renderer.processMessage(line1)
        assertTrue("createSurface 应成功", r1.isSuccess)

        val r2 = renderer.processMessage(line2)
        assertTrue("updateComponents 应成功", r2.isSuccess)

        val root = renderer.getComponent("jsonl_test", "root")
        assertNotNull(root)
        assertEquals("Text", root?.component)
    }

    // ============ 测试完整 weather JSONL 流程（模拟 A2UIComposeRenderer 的处理） ============

    @Test
    fun completeWeatherJsonlFlow() {
        // JSONL 格式：每行一个完整 JSON
        val line1 = """{"version":"v0.9","createSurface":{"surfaceId":"weather_xian2","catalogId":"app_catalog"}}"""
        val line2 = """{"version":"v0.9","updateComponents":{"surfaceId":"weather_xian2","components":[{"id":"root","component":"Card","child":"content"},{"id":"content","component":"Column","children":["title","icon","temp","condition","details"]},{"id":"title","component":"Text","text":"西安","variant":"h3"},{"id":"icon","component":"Text","text":"⛅","variant":"h1"},{"id":"temp","component":"Text","text":"21°C","variant":"h1"},{"id":"condition","component":"Text","text":"多云","variant":"subtitle"},{"id":"details","component":"Text","text":"当前天气数据","variant":"caption"}]}}"""

        val r1 = renderer.processMessage(line1)
        assertTrue("createSurface 应成功: ${r1.exceptionOrNull()?.message}", r1.isSuccess)

        val r2 = renderer.processMessage(line2)
        assertTrue("updateComponents 应成功: ${r2.exceptionOrNull()?.message}", r2.isSuccess)

        val root = renderer.getComponent("weather_xian2", "root")
        assertNotNull(root)
        assertEquals("Card", root?.component)

        val content = renderer.getComponent("weather_xian2", "content")
        assertNotNull(content)
        assertEquals("Column", content?.component)

        val children = (content?.children as? ChildList.ArrayChildList)?.array
        assertEquals(listOf("title", "icon", "temp", "condition", "details"), children)

        val title = renderer.getComponent("weather_xian2", "title")
        assertEquals("西安", (title?.text as? DynamicValue.LiteralValue)?.literal)
        assertEquals("h3", title?.variant)

        val temp = renderer.getComponent("weather_xian2", "temp")
        assertEquals("21°C", (temp?.text as? DynamicValue.LiteralValue)?.literal)
        assertEquals("h1", temp?.variant)

        val condition = renderer.getComponent("weather_xian2", "condition")
        assertEquals("多云", (condition?.text as? DynamicValue.LiteralValue)?.literal)
    }
}
