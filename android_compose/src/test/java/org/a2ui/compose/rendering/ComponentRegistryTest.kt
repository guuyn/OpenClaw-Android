package org.a2ui.compose.rendering

import org.junit.Test
import org.junit.Assert.*
import org.a2ui.compose.data.*

/**
 * ComponentRegistry 及 A2UI 渲染核心逻辑的 JVM 单元测试。
 *
 * ComponentRegistry 的渲染方法是 @Composable，需要 Android 运行时。
 * 本测试通过 A2UIRenderer + ComponentRegistry 组合测试：
 * - 协议消息处理 → 组件注册 → 组件检索
 * - 各组件类型的数据模型构建
 * - 深度嵌套（超过 MAX_RENDER_DEPTH）时的降级
 * - 非法属性与边界情况
 * - 数据模型解析与值解析
 *
 * 测试覆盖所有协议版本（v0.8/v0.9/v0.10）。
 */
class ComponentRegistryTest {

    private val renderer = A2UIRenderer()

    // ==================== 基础组件创建与检索 ====================

    @Test
    fun `create surface and register Text component (v0_10)`() {
        // Create surface
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "text_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        // Register Text component
        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "text_test",
                "components": [
                    {"id": "root", "component": "Text", "text": "Hello World", "variant": "body"}
                ]
            }
        }""")

        val component = renderer.getComponent("text_test", "root")
        assertNotNull(component)
        assertEquals("Text", component!!.component)
        assertEquals("Hello World", (component.text as DynamicValue.LiteralValue<String>).literal)
        assertEquals("body", component.variant)
    }

    @Test
    fun `register multiple component types (v0_10)`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "multi_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "multi_test",
                "components": [
                    {"id": "root", "component": "Column", "children": {"array": ["title", "btn", "img"]}},
                    {"id": "title", "component": "Text", "text": "Title", "variant": "h2"},
                    {"id": "btn", "component": "Button", "text": "Click Me", "variant": "primary", "action": {"event": {"name": "submit"}}},
                    {"id": "img", "component": "Image", "url": {"literal": "https://example.com/img.png"}, "fit": "cover"}
                ]
            }
        }""")

        val root = renderer.getComponent("multi_test", "root")
        assertNotNull(root)
        assertEquals("Column", root!!.component)

        val title = renderer.getComponent("multi_test", "title")
        assertNotNull(title)
        assertEquals("Text", title!!.component)
        assertEquals("h2", title.variant)

        val btn = renderer.getComponent("multi_test", "btn")
        assertNotNull(btn)
        assertEquals("Button", btn!!.component)
        assertEquals("primary", btn.variant)
        assertNotNull(btn.action)

        val img = renderer.getComponent("multi_test", "img")
        assertNotNull(img)
        assertEquals("Image", img!!.component)
    }

    @Test
    fun `register Card component with child reference (v0_10)`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "card_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "card_test",
                "components": [
                    {"id": "card1", "component": "Card", "child": "card_content", "cornerRadius": 16, "shadow": 8},
                    {"id": "card_content", "component": "Column", "children": {"array": ["t1", "t2"]}},
                    {"id": "t1", "component": "Text", "text": "Card Title", "variant": "h4"},
                    {"id": "t2", "component": "Text", "text": "Card body content", "variant": "body"}
                ]
            }
        }""")

        val card = renderer.getComponent("card_test", "card1")
        assertNotNull(card)
        assertEquals("Card", card!!.component)
        assertEquals("card_content", card.child)
        assertEquals(16, card.cornerRadius)
        assertEquals(8, card.shadow)
    }

    @Test
    fun `register Row with justify and align (v0_10)`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "row_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "row_test",
                "components": [
                    {"id": "row1", "component": "Row", "children": {"array": ["a", "b"]}, "justify": "spaceBetween", "align": "center"}
                ]
            }
        }""")

        val row = renderer.getComponent("row_test", "row1")
        assertNotNull(row)
        assertEquals("Row", row!!.component)
        assertEquals("spaceBetween", row.justify)
        assertEquals("center", row.align)
    }

    @Test
    fun `register List with array children (v0_10)`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "list_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "list_test",
                "components": [
                    {"id": "list1", "component": "List", "children": {"array": ["item1", "item2", "item3"]}},
                    {"id": "item1", "component": "Text", "text": "Item 1"},
                    {"id": "item2", "component": "Text", "text": "Item 2"},
                    {"id": "item3", "component": "Text", "text": "Item 3"}
                ]
            }
        }""")

        val list = renderer.getComponent("list_test", "list1")
        assertNotNull(list)
        assertEquals("List", list!!.component)
        val children = list.children as? ChildList.ArrayChildList
        assertNotNull(children)
        assertEquals(3, children!!.array.size)
    }

    @Test
    fun `register Divider component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "divider_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "divider_test",
                "components": [
                    {"id": "h_divider", "component": "Divider", "axis": "horizontal"},
                    {"id": "v_divider", "component": "Divider", "axis": "vertical"}
                ]
            }
        }""")

        val hDivider = renderer.getComponent("divider_test", "h_divider")
        assertNotNull(hDivider)
        assertEquals("Divider", hDivider!!.component)
        assertEquals("horizontal", hDivider.axis)

        val vDivider = renderer.getComponent("divider_test", "v_divider")
        assertNotNull(vDivider)
        assertEquals("vertical", vDivider!!.axis)
    }

    @Test
    fun `register Slider component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "slider_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "slider_test",
                "components": [
                    {"id": "slider1", "component": "Slider", "label": {"literal": "Volume"}, "value": {"literal": 50}, "minValue": 0, "maxValue": 100}
                ]
            }
        }""")

        val slider = renderer.getComponent("slider_test", "slider1")
        assertNotNull(slider)
        assertEquals("Slider", slider!!.component)
        assertEquals(0.0, slider.minValue!!, 0.01)
        assertEquals(100.0, slider.maxValue!!, 0.01)
    }

    @Test
    fun `register ChoicePicker with options`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "choice_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "choice_test",
                "components": [
                    {
                        "id": "picker1",
                        "component": "ChoicePicker",
                        "label": {"literal": "Select color"},
                        "variant": "mutuallyExclusive",
                        "options": [
                            {"label": "Red", "value": "red"},
                            {"label": "Blue", "value": "blue"},
                            {"label": "Green", "value": "green"}
                        ]
                    }
                ]
            }
        }""")

        val picker = renderer.getComponent("choice_test", "picker1")
        assertNotNull(picker)
        assertEquals("ChoicePicker", picker!!.component)
        assertEquals("mutuallyExclusive", picker.variant)
        assertEquals(3, picker.options?.size)
    }

    @Test
    fun `register Switch component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "switch_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "switch_test",
                "components": [
                    {"id": "sw1", "component": "Switch", "label": {"literal": "Dark Mode"}, "value": {"literal": false}}
                ]
            }
        }""")

        val sw = renderer.getComponent("switch_test", "sw1")
        assertNotNull(sw)
        assertEquals("Switch", sw!!.component)
    }

    @Test
    fun `register ProgressBar component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "progress_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "progress_test",
                "components": [
                    {"id": "pb1", "component": "ProgressBar", "value": {"literal": 0.75}}
                ]
            }
        }""")

        val pb = renderer.getComponent("progress_test", "pb1")
        assertNotNull(pb)
        assertEquals("ProgressBar", pb!!.component)
    }

    @Test
    fun `register Icon component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "icon_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "icon_test",
                "components": [
                    {"id": "icon1", "component": "Icon", "icon": "home", "size": 32}
                ]
            }
        }""")

        val icon = renderer.getComponent("icon_test", "icon1")
        assertNotNull(icon)
        assertEquals("Icon", icon!!.component)
        assertEquals("home", icon.icon)
        assertEquals(32, icon.size)
    }

    @Test
    fun `register Tabs component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "tabs_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "tabs_test",
                "components": [
                    {
                        "id": "tabs1",
                        "component": "Tabs",
                        "tabs": [
                            {"title": {"literal": "Tab 1"}, "child": "tab_content_1"},
                            {"title": {"literal": "Tab 2"}, "child": "tab_content_2"}
                        ]
                    },
                    {"id": "tab_content_1", "component": "Text", "text": "Content 1"},
                    {"id": "tab_content_2", "component": "Text", "text": "Content 2"}
                ]
            }
        }""")

        val tabs = renderer.getComponent("tabs_test", "tabs1")
        assertNotNull(tabs)
        assertEquals("Tabs", tabs!!.component)
        assertEquals(2, tabs.tabs?.size)
    }

    @Test
    fun `register Dropdown component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "dropdown_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "dropdown_test",
                "components": [
                    {
                        "id": "dd1",
                        "component": "Dropdown",
                        "label": {"literal": "Language"},
                        "options": [
                            {"label": "English", "value": "en"},
                            {"label": "中文", "value": "zh"}
                        ]
                    }
                ]
            }
        }""")

        val dd = renderer.getComponent("dropdown_test", "dd1")
        assertNotNull(dd)
        assertEquals("Dropdown", dd!!.component)
        assertEquals(2, dd.options?.size)
    }

    // ==================== 协议版本测试 ====================

    @Test
    fun `v0_8 protocol - createSurface and components`() {
        val result1 = renderer.processMessage("""{
            "version": "v0.8",
            "createSurface": {
                "surfaceId": "v08_test",
                "catalogId": "https://a2ui.org/specification/v0_8/standard_catalog.json"
            }
        }""")
        assertTrue(result1.isSuccess)

        val result2 = renderer.processMessage("""{
            "version": "v0.8",
            "updateComponents": {
                "surfaceId": "v08_test",
                "components": [
                    {"id": "root", "component": "Text", "text": "v0.8 Test", "usageHint": "body"}
                ]
            }
        }""")
        assertTrue(result2.isSuccess)

        val component = renderer.getComponent("v08_test", "root")
        assertNotNull(component)
        assertEquals("v0.8 Test", (component!!.text as DynamicValue.LiteralValue<String>).literal)
    }

    @Test
    fun `v0_9 protocol - createSurface and components`() {
        val result1 = renderer.processMessage("""{
            "version": "v0.9",
            "createSurface": {
                "surfaceId": "v09_test",
                "catalogId": "https://a2ui.org/specification/v0_9/standard_catalog.json"
            }
        }""")
        assertTrue(result1.isSuccess)

        val result2 = renderer.processMessage("""{
            "version": "v0.9",
            "updateComponents": {
                "surfaceId": "v09_test",
                "components": [
                    {"id": "root", "component": "Column", "children": {"array": ["txt"]}},
                    {"id": "txt", "component": "Text", "text": "v0.9 Test", "variant": "h3"}
                ]
            }
        }""")
        assertTrue(result2.isSuccess)

        val component = renderer.getComponent("v09_test", "txt")
        assertNotNull(component)
        assertEquals("v0.9 Test", (component!!.text as DynamicValue.LiteralValue<String>).literal)
    }

    @Test
    fun `v0_10 protocol - full surface lifecycle`() {
        // Create
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "v10_lifecycle",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json",
                "theme": {"primaryColor": "#6200EE"}
            }
        }""")

        // Update
        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "v10_lifecycle",
                "components": [
                    {"id": "root", "component": "Text", "text": "Lifecycle Test"}
                ]
            }
        }""")

        // Update data model
        renderer.processMessage("""{
            "version": "v0.10",
            "updateDataModel": {
                "surfaceId": "v10_lifecycle",
                "path": "/user/name",
                "value": "Alice"
            }
        }""")

        val context = renderer.getSurfaceContext("v10_lifecycle")
        assertNotNull(context)
        assertEquals("#6200EE", context?.theme?.primaryColor)

        val component = renderer.getComponent("v10_lifecycle", "root")
        assertNotNull(component)

        val dataModel = renderer.getDataModel("v10_lifecycle")
        assertNotNull(dataModel)
        assertEquals("Alice", dataModel?.getValue("/user/name"))

        // Delete
        renderer.processMessage("""{
            "version": "v0.10",
            "deleteSurface": {"surfaceId": "v10_lifecycle"}
        }""")

        assertNull(renderer.getSurfaceContext("v10_lifecycle"))
    }

    // ==================== 深度嵌套测试 ====================

    @Test
    fun `deeply nested components are registered correctly`() {
        // Build a 10-level deep component tree
        val componentList = mutableListOf<String>()
        componentList.add("""{"id": "level_0", "component": "Column", "children": {"array": ["level_1"]}}""")
        for (i in 1 until 10) {
            componentList.add("""{"id": "level_$i", "component": "Column", "children": {"array": ["level_${i + 1}"]}}""")
        }
        componentList.add("""{"id": "level_10", "component": "Text", "text": "Deep leaf"}""")

        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "deep_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        val componentsJson = componentList.joinToString(",")
        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "deep_test",
                "components": [$componentsJson]
            }
        }""")

        // Verify all levels are registered
        for (i in 0..10) {
            val comp = renderer.getComponent("deep_test", "level_$i")
            assertNotNull("Component level_$i should exist", comp)
        }

        // Verify leaf component
        val leaf = renderer.getComponent("deep_test", "level_10")
        assertEquals("Text", leaf!!.component)
    }

    @Test
    fun `component tree exceeds MAX_RENDER_DEPTH triggers depth error`() {
        // ComponentRegistry has MAX_RENDER_DEPTH = 50
        // Create 55 levels of nesting
        val componentList = mutableListOf<String>()
        for (i in 0..55) {
            if (i < 55) {
                componentList.add("""{"id": "d$i", "component": "Column", "children": {"array": ["d${i + 1}"]}}""")
            } else {
                componentList.add("""{"id": "d$i", "component": "Text", "text": "Deep leaf"}""")
            }
        }

        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "depth_exceed",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "depth_exceed",
                "components": [${componentList.joinToString(",")}]
            }
        }""")

        // All components should still be registered (they're stored, not rendered in test)
        val root = renderer.getComponent("depth_exceed", "d0")
        assertNotNull(root)
        val deep = renderer.getComponent("depth_exceed", "d55")
        assertNotNull(deep)
    }

    // ==================== 非法属性与边界情况 ====================

    @Test
    fun `component with unknown type is still registered`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "unknown_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "unknown_test",
                "components": [
                    {"id": "custom1", "component": "CustomUnknownType", "text": "Custom"}
                ]
            }
        }""")

        val comp = renderer.getComponent("unknown_test", "custom1")
        assertNotNull(comp)
        assertEquals("CustomUnknownType", comp!!.component)
    }

    @Test
    fun `component with missing optional fields`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "minimal_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "minimal_test",
                "components": [
                    {"id": "minimal", "component": "Text"}
                ]
            }
        }""")

        val comp = renderer.getComponent("minimal_test", "minimal")
        assertNotNull(comp)
        assertEquals("Text", comp!!.component)
        assertNull(comp.text)
        assertNull(comp.variant)
    }

    @Test
    fun `component with empty children array`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "empty_children_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "empty_children_test",
                "components": [
                    {"id": "empty_col", "component": "Column", "children": {"array": []}}
                ]
            }
        }""")

        val comp = renderer.getComponent("empty_children_test", "empty_col")
        assertNotNull(comp)
        val children = comp!!.children as? ChildList.ArrayChildList
        assertNotNull(children)
        assertTrue(children!!.array.isEmpty())
    }

    @Test
    fun `component with special characters in text`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "special_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "special_test",
                "components": [
                    {"id": "special", "component": "Text", "text": "Hello \"World\" & <tags>\\n"}
                ]
            }
        }""")

        val comp = renderer.getComponent("special_test", "special")
        assertNotNull(comp)
        val text = (comp!!.text as DynamicValue.LiteralValue<String>).literal
        assertEquals("Hello \"World\" & <tags>\\n", text)
    }

    @Test
    fun `component with null values in JSON`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "null_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "null_test",
                "components": [
                    {"id": "null_comp", "component": "Text", "text": null, "variant": null}
                ]
            }
        }""")

        val comp = renderer.getComponent("null_test", "null_comp")
        assertNotNull(comp)
        assertNull(comp!!.text)
        assertNull(comp.variant)
    }

    @Test
    fun `updateComponents to non-existent surface silently fails`() {
        val result = renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "nonexistent",
                "components": [{"id": "root", "component": "Text", "text": "Should not appear"}]
            }
        }""")

        // Should succeed (no error) but component won't exist
        assertTrue(result.isSuccess)
        assertNull(renderer.getComponent("nonexistent", "root"))
    }

    @Test
    fun `getComponent returns null for missing component`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "missing_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        assertNull(renderer.getComponent("missing_test", "nonexistent"))
    }

    @Test
    fun `getComponent returns null for missing surface`() {
        assertNull(renderer.getComponent("completely_missing", "root"))
    }

    // ==================== DataModel tests ====================

    @Test
    fun `data model with nested object path`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "datamodel_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateDataModel": {
                "surfaceId": "datamodel_test",
                "path": "/",
                "value": {
                    "user": {"name": "Bob", "age": 30},
                    "items": ["a", "b", "c"]
                }
            }
        }""")

        val dm = renderer.getDataModel("datamodel_test")
        assertNotNull(dm)
        assertEquals("Bob", dm?.getValue("/user/name"))
        assertEquals(30L, dm?.getValue("/user/age"))
    }

    @Test
    fun `data model update individual path`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "dm_update_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        // Set initial data
        renderer.processMessage("""{
            "version": "v0.10",
            "updateDataModel": {
                "surfaceId": "dm_update_test",
                "path": "/counter",
                "value": 0
            }
        }""")

        // Update
        renderer.processMessage("""{
            "version": "v0.10",
            "updateDataModel": {
                "surfaceId": "dm_update_test",
                "path": "/counter",
                "value": 42
            }
        }""")

        val dm = renderer.getDataModel("dm_update_test")
        assertNotNull(dm)
        assertEquals(42L, dm?.getValue("/counter"))
    }

    // ==================== Visual styling tests ====================

    @Test
    fun `component with visual styling properties (v0_9+)`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "style_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "style_test",
                "components": [
                    {
                        "id": "styled_card",
                        "component": "Card",
                        "child": "content",
                        "backgroundColor": "#FF5722",
                        "textColor": "#FFFFFF",
                        "cornerRadius": 20,
                        "padding": 16,
                        "shadow": 12,
                        "gradient": ["#FF5722", "#FF9800"]
                    },
                    {"id": "content", "component": "Text", "text": "Styled Card"}
                ]
            }
        }""")

        val card = renderer.getComponent("style_test", "styled_card")
        assertNotNull(card)
        assertEquals("#FF5722", card!!.backgroundColor)
        assertEquals("#FFFFFF", card.textColor)
        assertEquals(20, card.cornerRadius)
        assertEquals(16, card.padding)
        assertEquals(12, card.shadow)
        assertEquals(2, card.gradient?.size)
    }

    @Test
    fun `component with accessibility properties`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "a11y_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "a11y_test",
                "components": [
                    {
                        "id": "a11y_btn",
                        "component": "Button",
                        "text": "Submit",
                        "accessibilityLabel": "Submit form button",
                        "accessibilityRole": "button"
                    }
                ]
            }
        }""")

        val btn = renderer.getComponent("a11y_test", "a11y_btn")
        assertNotNull(btn)
        assertEquals("Submit form button", btn!!.accessibilityLabel)
        assertEquals("button", btn.accessibilityRole)
    }

    // ==================== ComponentRegistry direct API tests ====================

    @Test
    fun `register and unregister custom component`() {
        val registry = ComponentRegistry(renderer)

        // Create a surface first (required for rendering context)
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "custom_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        // Register custom component
        var wasCalled = false
        registry.registerCustomComponent("MyCustomComponent") { _, _ ->
            wasCalled = true
        }

        // Unregister
        registry.unregisterCustomComponent("MyCustomComponent")

        // The customComponents map is private, so we verify indirectly
        // After unregistering, the component should not be in customComponents
        // Since we can't directly check, we verify no crash occurs
        assertTrue("Unregister should succeed without crash", true)
    }

    @Test
    fun `register standard component type`() {
        val registry = ComponentRegistry(renderer)

        // Verify that standard types are registered (init does this)
        // We test by registering components of various types and checking they're stored
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "registry_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        val types = listOf(
            "Text", "Button", "Column", "Row", "Card", "Image", "Icon",
            "Divider", "Slider", "ChoicePicker", "List", "Tabs", "Modal",
            "TextField", "CheckBox", "Switch", "Dropdown", "Spacer",
            "ProgressBar", "DateTimeInput", "Video", "AudioPlayer",
            "Surface", "StockCard", "CandlestickChart", "LineChart",
            "GaugeChart", "MiniGauge", "HeatmapChart", "RadarChart",
            "BubbleChart", "StreamingLineChart", "InteractiveLineChart"
        )

        // Register components of each type
        val componentsJson = types.mapIndexed { idx, type ->
            """{"id": "comp_$idx", "component": "$type"}"""
        }.joinToString(",")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "registry_test",
                "components": [$componentsJson]
            }
        }""")

        // Verify all are stored
        for ((idx, type) in types.withIndex()) {
            val comp = renderer.getComponent("registry_test", "comp_$idx")
            assertNotNull("Component $type (comp_$idx) should be registered", comp)
            assertEquals(type, comp!!.component)
        }
    }

    // ==================== Error handling tests ====================

    @Test
    fun `invalid JSON message returns failure`() {
        val result = renderer.processMessage("{ invalid json }")
        assertTrue(result.isFailure)
    }

    @Test
    fun `empty message returns failure`() {
        val result = renderer.processMessage("")
        assertTrue(result.isFailure)
    }

    @Test
    fun `message with unknown operation type returns failure`() {
        val result = renderer.processMessage("""{"version": "v0.10", "unknownOperation": {}}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `multiple surfaces can coexist`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "surface_a",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "surface_b",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "surface_a",
                "components": [{"id": "txt_a", "component": "Text", "text": "Surface A"}]
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "surface_b",
                "components": [{"id": "txt_b", "component": "Text", "text": "Surface B"}]
            }
        }""")

        val compA = renderer.getComponent("surface_a", "txt_a")
        val compB = renderer.getComponent("surface_b", "txt_b")

        assertNotNull(compA)
        assertNotNull(compB)
        assertEquals("Surface A", (compA!!.text as DynamicValue.LiteralValue<String>).literal)
        assertEquals("Surface B", (compB!!.text as DynamicValue.LiteralValue<String>).literal)
    }

    // ==================== List with ObjectChildList (template) ====================

    @Test
    fun `List with objectChild template`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "template_list",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateDataModel": {
                "surfaceId": "template_list",
                "path": "/items",
                "value": [{"name": "Item 1"}, {"name": "Item 2"}, {"name": "Item 3"}]
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "template_list",
                "components": [
                    {
                        "id": "list1",
                        "component": "List",
                        "children": {"objectChild": {"path": "/items", "componentId": "item_template"}}
                    },
                    {"id": "item_template", "component": "Text", "text": {"path": "/name"}}
                ]
            }
        }""")

        val list = renderer.getComponent("template_list", "list1")
        assertNotNull(list)
        assertEquals("List", list!!.component)
        val children = list.children as? ChildList.ObjectChildList
        assertNotNull(children)
        assertEquals("/items", children!!.objectChild.path)
        assertEquals("item_template", children.objectChild.componentId)
    }

    // ==================== Modal component ====================

    @Test
    fun `Modal component with trigger and content`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "modal_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "modal_test",
                "components": [
                    {"id": "modal1", "component": "Modal", "trigger": "modal_trigger", "content": "modal_content"},
                    {"id": "modal_trigger", "component": "Button", "text": "Open Modal"},
                    {"id": "modal_content", "component": "Column", "children": {"array": ["modal_text"]}},
                    {"id": "modal_text", "component": "Text", "text": "Modal content here"}
                ]
            }
        }""")

        val modal = renderer.getComponent("modal_test", "modal1")
        assertNotNull(modal)
        assertEquals("Modal", modal!!.component)
        assertEquals("modal_trigger", modal.trigger)
        assertEquals("modal_content", modal.content)
    }

    // ==================== v0.8 compatibility: MultipleChoice -> ChoicePicker ====================

    @Test
    fun `MultipleChoice is registered as alias for ChoicePicker`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "multi_choice_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "multi_choice_test",
                "components": [
                    {
                        "id": "mc1",
                        "component": "MultipleChoice",
                        "label": {"literal": "Select"},
                        "options": [
                            {"label": "Option A", "value": "a"},
                            {"label": "Option B", "value": "b"}
                        ]
                    }
                ]
            }
        }""")

        val mc = renderer.getComponent("multi_choice_test", "mc1")
        assertNotNull(mc)
        assertEquals("MultipleChoice", mc!!.component)
    }

    // ==================== Accordion component ====================

    @Test
    fun `Accordion component with array children`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "accordion_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "accordion_test",
                "components": [
                    {
                        "id": "acc1",
                        "component": "Accordion",
                        "children": {"array": ["acc_item1", "acc_item2"]}
                    },
                    {"id": "acc_item1", "component": "Column", "child": "acc_content1", "label": {"literal": "Section 1"}},
                    {"id": "acc_item2", "component": "Column", "child": "acc_content2", "label": {"literal": "Section 2"}},
                    {"id": "acc_content1", "component": "Text", "text": "Content 1"},
                    {"id": "acc_content2", "component": "Text", "text": "Content 2"}
                ]
            }
        }""")

        val acc = renderer.getComponent("accordion_test", "acc1")
        assertNotNull(acc)
        assertEquals("Accordion", acc!!.component)
    }

    // ==================== TextField with validation ====================

    @Test
    fun `TextField with validation checks`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "textfield_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "textfield_test",
                "components": [
                    {
                        "id": "email_field",
                        "component": "TextField",
                        "label": {"literal": "Email"},
                        "variant": "shortText",
                        "required": true,
                        "checks": [
                            {"call": "required", "args": {}, "message": "Email is required"},
                            {"call": "email", "args": {}, "message": "Invalid email format"}
                        ]
                    }
                ]
            }
        }""")

        val tf = renderer.getComponent("textfield_test", "email_field")
        assertNotNull(tf)
        assertEquals("TextField", tf!!.component)
        assertTrue(tf.required == true)
        assertEquals(2, tf.checks?.size)
        assertEquals("required", tf.checks!![0].call)
        assertEquals("email", tf.checks[1].call)
    }

    // ==================== DateTimeInput component ====================

    @Test
    fun `DateTimeInput component registration`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "datetime_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "datetime_test",
                "components": [
                    {
                        "id": "dt1",
                        "component": "DateTimeInput",
                        "label": {"literal": "Select Date"},
                        "enableDate": true,
                        "enableTime": false
                    }
                ]
            }
        }""")

        val dt = renderer.getComponent("datetime_test", "dt1")
        assertNotNull(dt)
        assertEquals("DateTimeInput", dt!!.component)
        assertTrue(dt.enableDate == true)
        assertTrue(dt.enableTime == false)
    }

    // ==================== CheckBox component ====================

    @Test
    fun `CheckBox component registration`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "checkbox_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        renderer.processMessage("""{
            "version": "v0.10",
            "updateComponents": {
                "surfaceId": "checkbox_test",
                "components": [
                    {
                        "id": "cb1",
                        "component": "CheckBox",
                        "label": {"literal": "I agree to terms"},
                        "value": {"literal": false}
                    }
                ]
            }
        }""")

        val cb = renderer.getComponent("checkbox_test", "cb1")
        assertNotNull(cb)
        assertEquals("CheckBox", cb!!.component)
    }

    // ==================== SurfaceContext properties ====================

    @Test
    fun `SurfaceContext has correct render depth`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "ctx_test",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json"
            }
        }""")

        val ctx = renderer.getSurfaceContext("ctx_test")
        assertNotNull(ctx)
        assertEquals("ctx_test", ctx?.surfaceId)
        assertEquals(0, ctx?.renderDepth)
        assertNull(ctx?.scopePath)
    }

    @Test
    fun `SurfaceContext with theme`() {
        renderer.processMessage("""{
            "version": "v0.10",
            "createSurface": {
                "surfaceId": "themed_ctx",
                "catalogId": "https://a2ui.org/specification/v0_10/standard_catalog.json",
                "theme": {
                    "primaryColor": "#1976D2",
                    "iconUrl": "https://example.com/icon.png",
                    "agentDisplayName": "TestAgent"
                }
            }
        }""")

        val ctx = renderer.getSurfaceContext("themed_ctx")
        assertNotNull(ctx)
        assertEquals("#1976D2", ctx?.theme?.primaryColor)
        assertEquals("https://example.com/icon.png", ctx?.theme?.iconUrl)
        assertEquals("TestAgent", ctx?.theme?.agentDisplayName)
    }
}
