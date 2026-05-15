package org.a2ui.compose.rendering

import org.a2ui.compose.data.*
import org.a2ui.compose.data.ChildList.ArrayChildList
import org.a2ui.compose.data.ChildList.ObjectChildList
import org.junit.Test
import org.junit.Assert.*

class ParentChildResolverTest {

    // ── Helper ─────────────────────────────────────────────────────────

    private fun component(
        id: String,
        type: String = "Text",
        child: String? = null,
        children: ChildList? = null,
        trigger: String? = null,
        content: String? = null,
        tabs: List<TabItem>? = null,
    ): Component {
        return Component(
            id = id,
            component = type,
            child = child,
            children = children,
            trigger = trigger,
            content = content,
            tabs = tabs,
        )
    }

    private fun ids(components: List<Component>): List<String> = components.map { it.id }

    // ── Test 1: Normal parent-child ordering ───────────────────────────

    @Test
    fun `normal parent-child ordering - children array`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("row1", "Row", children = ArrayChildList(listOf("text1", "text2"))),
            component("text1", "Text"),
            component("text2", "Text"),
        ))

        val sorted = resolver.sortedComponents
        assertEquals(3, sorted.size)

        // Parent must come before children
        val rowIndex = sorted.indexOfFirst { it.id == "row1" }
        val text1Index = sorted.indexOfFirst { it.id == "text1" }
        val text2Index = sorted.indexOfFirst { it.id == "text2" }

        assertTrue("row1 must come before text1", rowIndex < text1Index)
        assertTrue("row1 must come before text2", rowIndex < text2Index)
    }

    @Test
    fun `normal parent-child ordering - single child reference`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("btn1", "Button", child = "label1"),
            component("label1", "Text"),
        ))

        val sorted = resolver.sortedComponents
        val btnIdx = sorted.indexOfFirst { it.id == "btn1" }
        val labelIdx = sorted.indexOfFirst { it.id == "label1" }

        assertTrue("Button must come before its child", btnIdx < labelIdx)
    }

    @Test
    fun `normal parent-child ordering - tabs child references`() {
        val resolver = ParentChildResolver()
        val tabItems = listOf(
            TabItem(title = DynamicValue.LiteralValue("Tab A"), child = "panelA"),
            TabItem(title = DynamicValue.LiteralValue("Tab B"), child = "panelB"),
        )
        resolver.putAll(listOf(
            component("tabs1", "Tabs", tabs = tabItems),
            component("panelA", "Column"),
            component("panelB", "Row"),
        ))

        val sorted = resolver.sortedComponents
        val tabsIdx = sorted.indexOfFirst { it.id == "tabs1" }
        val panelAIdx = sorted.indexOfFirst { it.id == "panelA" }
        val panelBIdx = sorted.indexOfFirst { it.id == "panelB" }

        assertTrue("Tabs must come before panelA", tabsIdx < panelAIdx)
        assertTrue("Tabs must come before panelB", tabsIdx < panelBIdx)
    }

    @Test
    fun `normal parent-child ordering - Modal trigger and content`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("modal1", "Modal", trigger = "triggerBtn", content = "modalBody"),
            component("triggerBtn", "Button"),
            component("modalBody", "Column"),
        ))

        val sorted = resolver.sortedComponents
        val modalIdx = sorted.indexOfFirst { it.id == "modal1" }
        val triggerIdx = sorted.indexOfFirst { it.id == "triggerBtn" }
        val bodyIdx = sorted.indexOfFirst { it.id == "modalBody" }

        assertTrue("Modal must come before trigger", modalIdx < triggerIdx)
        assertTrue("Modal must come before content", modalIdx < bodyIdx)
    }

    // ── Test 2: Child arrives before parent (streaming order) ──────────

    @Test
    fun `streaming order - children arrive before parent`() {
        val resolver = ParentChildResolver()

        // Simulate streaming: children arrive first
        resolver.put(component("text1", "Text"))
        resolver.put(component("text2", "Text"))

        // Verify partial result works
        val partial = resolver.sortedComponents
        assertEquals(2, partial.size)

        // Parent arrives later
        resolver.put(component("row1", "Row", children = ArrayChildList(listOf("text1", "text2"))))

        val sorted = resolver.sortedComponents
        assertEquals(3, sorted.size)

        val rowIndex = sorted.indexOfFirst { it.id == "row1" }
        val text1Index = sorted.indexOfFirst { it.id == "text1" }
        val text2Index = sorted.indexOfFirst { it.id == "text2" }

        assertTrue("row1 must come before text1 even when text1 arrived first", rowIndex < text1Index)
        assertTrue("row1 must come before text2 even when text2 arrived first", rowIndex < text2Index)
    }

    @Test
    fun `streaming order - deep nesting with late parent`() {
        val resolver = ParentChildResolver()

        // Deeply nested structure, children arrive first
        resolver.put(component("leaf", "Text"))
        resolver.put(component("branch", "Row", children = ArrayChildList(listOf("leaf"))))
        resolver.put(component("root", "Column", children = ArrayChildList(listOf("branch"))))

        val sorted = resolver.sortedComponents
        assertEquals(3, sorted.size)

        val rootIdx = sorted.indexOfFirst { it.id == "root" }
        val branchIdx = sorted.indexOfFirst { it.id == "branch" }
        val leafIdx = sorted.indexOfFirst { it.id == "leaf" }

        assertTrue("root < branch", rootIdx < branchIdx)
        assertTrue("branch < leaf", branchIdx < leafIdx)
    }

    // ── Test 3: Mixed reference types ──────────────────────────────────

    @Test
    fun `mixed reference types - children + child + tabs + trigger+content`() {
        val resolver = ParentChildResolver()
        val tabItems = listOf(
            TabItem(title = DynamicValue.LiteralValue("Settings"), child = "settingsPanel"),
        )
        resolver.putAll(listOf(
            component("root", "Column",
                children = ArrayChildList(listOf("header", "body")),
                child = "footer",
            ),
            component("header", "Row", children = ArrayChildList(listOf("title", "icon"))),
            component("body", "Tabs", tabs = tabItems),
            component("footer", "Button", child = "footerLabel"),
            component("title", "Text"),
            component("icon", "Icon"),
            component("settingsPanel", "Column"),
            component("footerLabel", "Text"),
        ))

        val sorted = resolver.sortedComponents
        assertEquals(8, sorted.size)

        // Verify all parent-before-child constraints
        fun index(id: String) = sorted.indexOfFirst { it.id == id }

        assertTrue("root < header", index("root") < index("header"))
        assertTrue("root < body", index("root") < index("body"))
        assertTrue("root < footer", index("root") < index("footer"))
        assertTrue("header < title", index("header") < index("title"))
        assertTrue("header < icon", index("header") < index("icon"))
        assertTrue("body < settingsPanel", index("body") < index("settingsPanel"))
        assertTrue("footer < footerLabel", index("footer") < index("footerLabel"))
    }

    @Test
    fun `mixed reference types - ObjectChildList (template)`() {
        val resolver = ParentChildResolver()
        val template = ObjectChildList(ChildTemplate(path = "/items", componentId = "itemTemplate"))
        resolver.putAll(listOf(
            component("list1", "List", children = template),
            component("itemTemplate", "Row", children = ArrayChildList(listOf("itemName"))),
            component("itemName", "Text"),
        ))

        val sorted = resolver.sortedComponents
        assertEquals(3, sorted.size)

        fun index(id: String) = sorted.indexOfFirst { it.id == id }
        assertTrue("list1 < itemTemplate", index("list1") < index("itemTemplate"))
        assertTrue("itemTemplate < itemName", index("itemTemplate") < index("itemName"))
    }

    // ── Test 4: Cycle detection ────────────────────────────────────────

    @Test
    fun `cycle detection - simple two-node cycle`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            Component(id = "A", component = "Row", child = "B"),
            Component(id = "B", component = "Row", child = "A"),
        ))

        val cycles = resolver.detectCycles()
        assertTrue("Should detect at least one cycle", cycles.isNotEmpty())

        // Still returns all components (appended after sorted ones)
        val sorted = resolver.sortedComponents
        assertEquals(2, sorted.size)
    }

    @Test
    fun `cycle detection - three-node cycle`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            Component(id = "A", component = "Row", child = "B"),
            Component(id = "B", component = "Row", child = "C"),
            Component(id = "C", component = "Row", child = "A"),
        ))

        val cycles = resolver.detectCycles()
        assertTrue("Should detect the A→B→C→A cycle", cycles.isNotEmpty())

        // Verify cycle path contains all three nodes
        val cycle = cycles.first()
        assertTrue(cycle.contains("A"))
        assertTrue(cycle.contains("B"))
        assertTrue(cycle.contains("C"))
    }

    @Test
    fun `cycle detection - no cycles returns empty`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("root", "Column", children = ArrayChildList(listOf("child1", "child2"))),
            component("child1", "Text"),
            component("child2", "Text"),
        ))

        val cycles = resolver.detectCycles()
        assertTrue("No cycles should return empty list", cycles.isEmpty())
    }

    // ── Test 5: Empty input ────────────────────────────────────────────

    @Test
    fun `empty input - no components`() {
        val resolver = ParentChildResolver()

        assertTrue(resolver.sortedComponents.isEmpty())
        assertTrue(resolver.isTreeComplete)
        assertTrue(resolver.detectCycles().isEmpty())
        assertEquals(0, resolver.componentIds.size)
    }

    @Test
    fun `single component - no relationships`() {
        val resolver = ParentChildResolver()
        resolver.put(component("solo", "Text"))

        assertEquals(1, resolver.sortedComponents.size)
        assertEquals("solo", resolver.sortedComponents[0].id)
        assertTrue(resolver.isTreeComplete)
    }

    // ── Test 6: Missing references ─────────────────────────────────────

    @Test
    fun `missing references - parent references unknown child`() {
        val resolver = ParentChildResolver()
        resolver.put(component("row1", "Row", children = ArrayChildList(listOf("unknown"))))

        val missing = resolver.findMissingReferences()
        assertEquals(1, missing.size)
        assertEquals("unknown", missing.first().childId)
        assertEquals("row1", missing.first().parentId)
        assertFalse(resolver.isTreeComplete)
    }

    @Test
    fun `missing references - resolved when child arrives`() {
        val resolver = ParentChildResolver()
        resolver.put(component("row1", "Row", children = ArrayChildList(listOf("text1"))))
        assertFalse(resolver.isTreeComplete)

        resolver.put(component("text1", "Text"))
        assertTrue(resolver.isTreeComplete)
        assertTrue(resolver.findMissingReferences().isEmpty())
    }

    // ── Test 7: Incremental operations ─────────────────────────────────

    @Test
    fun `put returns true for new component, false for replacement`() {
        val resolver = ParentChildResolver()
        assertTrue(resolver.put(component("c1", "Text")))
        assertFalse(resolver.put(component("c1", "Text"))) // replacement
        assertTrue(resolver.put(component("c2", "Text")))
    }

    @Test
    fun `remove component`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("r1", "Row", children = ArrayChildList(listOf("t1"))),
            component("t1", "Text"),
        ))

        val removed = resolver.remove("t1")
        assertNotNull(removed)
        assertEquals("t1", removed?.id)
        assertEquals(1, resolver.componentIds.size)
    }

    @Test
    fun `clear removes all components`() {
        val resolver = ParentChildResolver()
        resolver.putAll(listOf(
            component("r1", "Row"),
            component("t1", "Text"),
        ))
        resolver.clear()

        assertTrue(resolver.sortedComponents.isEmpty())
        assertTrue(resolver.componentIds.isEmpty())
    }

    @Test
    fun `merge combines two resolvers`() {
        val r1 = ParentChildResolver()
        r1.putAll(listOf(
            component("root", "Column", children = ArrayChildList(listOf("left", "right"))),
        ))

        val r2 = ParentChildResolver()
        r2.putAll(listOf(
            component("left", "Text"),
            component("right", "Text"),
        ))

        r1.merge(r2)

        val sorted = r1.sortedComponents
        assertEquals(3, sorted.size)
        assertTrue(r1.isTreeComplete)

        val rootIdx = sorted.indexOfFirst { it.id == "root" }
        assertTrue("root < left", rootIdx < sorted.indexOfFirst { it.id == "left" })
        assertTrue("root < right", rootIdx < sorted.indexOfFirst { it.id == "right" })
    }

    // ── Test 8: Edge extraction coverage ───────────────────────────────

    @Test
    fun `extractEdges covers all four reference types`() {
        val resolver = ParentChildResolver()
        val tabItems = listOf(
            TabItem(title = DynamicValue.LiteralValue("T"), child = "tabChild"),
        )
        val comp = Component(
            id = "mixed",
            component = "Column",
            children = ArrayChildList(listOf("c1", "c2")),
            child = "singleChild",
            tabs = tabItems,
            trigger = "triggerBtn",
            content = "contentBody",
        )

        val edges = resolver.extractEdges(comp)

        // children array
        assertTrue("Should contain c1", "c1" in edges)
        assertTrue("Should contain c2", "c2" in edges)
        // child single reference
        assertTrue("Should contain singleChild", "singleChild" in edges)
        // tabs child
        assertTrue("Should contain tabChild", "tabChild" in edges)
        // trigger / content
        assertTrue("Should contain triggerBtn", "triggerBtn" in edges)
        assertTrue("Should contain contentBody", "contentBody" in edges)

        // Total: 2 (children) + 1 (child) + 1 (tabs) + 1 (trigger) + 1 (content) = 6
        assertEquals(6, edges.size)
    }

    @Test
    fun `extractEdges handles ObjectChildList`() {
        val resolver = ParentChildResolver()
        val comp = Component(
            id = "list1",
            component = "List",
            children = ObjectChildList(ChildTemplate(path = "/items", componentId = "tmpl")),
        )

        val edges = resolver.extractEdges(comp)
        assertEquals(1, edges.size)
        assertTrue("tmpl" in edges)
    }

    @Test
    fun `extractEdges handles blank strings as no-ops`() {
        val resolver = ParentChildResolver()
        val comp = Component(
            id = "comp1",
            component = "Button",
            child = "",
            trigger = "   ",
            content = "",
        )

        val edges = resolver.extractEdges(comp)
        assertTrue("Blank references should produce no edges", edges.isEmpty())
    }

    @Test
    fun `extractEdges handles null references`() {
        val resolver = ParentChildResolver()
        val comp = Component(
            id = "comp1",
            component = "Text",
        )

        val edges = resolver.extractEdges(comp)
        assertTrue("Null references should produce no edges", edges.isEmpty())
    }

    // ── Test 9: Complex real-world-like scenario ───────────────────────

    @Test
    fun `complex nested structure with mixed types`() {
        val resolver = ParentChildResolver()

        // Simulate a realistic streaming order (not topological)
        val streamingOrder = listOf(
            // Leaf nodes first (arrive early in stream)
            component("weatherIcon", "Icon"),
            component("tempText", "Text"),
            component("detailLabel", "Text"),
            component("detailValue", "Text"),
            component("settingsIcon", "Icon"),
            component("settingsText", "Text"),
            // Mid-level containers
            component("weatherRow", "Row", children = ArrayChildList(listOf("weatherIcon", "tempText"))),
            component("detailRow", "Row", children = ArrayChildList(listOf("detailLabel", "detailValue"))),
            component("settingsBtn", "Button", child = "settingsRow"),
            component("settingsRow", "Row", children = ArrayChildList(listOf("settingsIcon", "settingsText"))),
            // Root
            component("weatherCard", "Card",
                children = ArrayChildList(listOf("weatherRow", "detailRow")),
                child = "settingsBtn",
            ),
        )

        resolver.putAll(streamingOrder)

        val sorted = resolver.sortedComponents
        assertEquals(11, sorted.size)

        fun index(id: String) = sorted.indexOfFirst { it.id == id }

        // Verify all parent-before-child constraints
        assertTrue("weatherCard < weatherRow", index("weatherCard") < index("weatherRow"))
        assertTrue("weatherCard < detailRow", index("weatherCard") < index("detailRow"))
        assertTrue("weatherCard < settingsBtn", index("weatherCard") < index("settingsBtn"))
        assertTrue("weatherRow < weatherIcon", index("weatherRow") < index("weatherIcon"))
        assertTrue("weatherRow < tempText", index("weatherRow") < index("tempText"))
        assertTrue("detailRow < detailLabel", index("detailRow") < index("detailLabel"))
        assertTrue("detailRow < detailValue", index("detailRow") < index("detailValue"))
        assertTrue("settingsBtn < settingsRow", index("settingsBtn") < index("settingsRow"))
        assertTrue("settingsRow < settingsIcon", index("settingsRow") < index("settingsIcon"))
        assertTrue("settingsRow < settingsText", index("settingsRow") < index("settingsText"))

        // Tree is complete
        assertTrue(resolver.isTreeComplete)
    }
}
