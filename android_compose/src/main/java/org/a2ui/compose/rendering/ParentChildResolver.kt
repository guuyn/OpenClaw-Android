package org.a2ui.compose.rendering

import org.a2ui.compose.data.ChildList
import org.a2ui.compose.data.ChildList.ArrayChildList
import org.a2ui.compose.data.ChildList.ObjectChildList
import org.a2ui.compose.data.Component
import java.util.Collections

/**
 * Resolves parent-child relationships among A2UI components and produces a
 * topologically-sorted order so that parents are always rendered before their
 * children — even when components arrive in arbitrary (streaming) order.
 *
 * Covers all four reference types in the A2UI protocol:
 * 1. `children` — ArrayChildList or ObjectChildList
 * 2. `child` — single-component reference (Button, Card, Surface, Modal, etc.)
 * 3. `tabs[].child` / `tabItems[].child` — tab child references
 * 4. `trigger` / `content` — Modal entry-point and content references
 *
 * Algorithm: Kahn's algorithm (BFS-based topological sort) with cycle detection.
 */
class ParentChildResolver {

    // ── State ──────────────────────────────────────────────────────────

    private val _components = linkedMapOf<String, Component>()
    private val _sortedIds = mutableListOf<String>()
    private var _dirty = true

    /** Snapshot of known component IDs (read-only). */
    val componentIds: Set<String> get() = _components.keys.toSet()

    /** Topologically sorted component list (computed on demand). */
    val sortedComponents: List<Component>
        get() {
            if (_dirty) {
                recompute()
            }
            return _sortedIds.mapNotNull { _components[it] }
        }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Add or replace a component and mark the graph dirty.
     * @return true if the component was newly added, false if it replaced an existing one.
     */
    fun put(component: Component): Boolean {
        val isNew = !_components.containsKey(component.id)
        _components[component.id] = component
        _dirty = true
        return isNew
    }

    /**
     * Bulk-add components.
     * @return number of newly added components.
     */
    fun putAll(components: List<Component>): Int {
        var newCount = 0
        components.forEach { if (put(it)) newCount++ }
        return newCount
    }

    /** Remove a component by ID. */
    fun remove(id: String): Component? {
        val removed = _components.remove(id)
        if (removed != null) _dirty = true
        return removed
    }

    /** Clear all components. */
    fun clear() {
        _components.clear()
        _sortedIds.clear()
        _dirty = true
    }

    /** Get a component by ID. */
    operator fun get(id: String): Component? = _components[id]

    // ── Tree completeness ──────────────────────────────────────────────

    /**
     * Validate tree completeness — find references to component IDs that
     * do not yet exist in the resolver. Useful for streaming scenarios to
     * detect which components are still missing.
     */
    fun findMissingReferences(): Set<MissingReference> {
        val missing = mutableSetOf<MissingReference>()
        for ((parentId, component) in _components) {
            val edges = extractEdges(component)
            for (childId in edges) {
                if (childId !in _components && childId.isNotBlank()) {
                    missing.add(MissingReference(childId, parentId))
                }
            }
        }
        return missing
    }

    /** Whether the current component set forms a complete tree (no missing refs). */
    val isTreeComplete: Boolean
        get() = findMissingReferences().isEmpty()

    // ── Cycle detection ────────────────────────────────────────────────

    /**
     * Detect cycles in the component graph.
     * @return list of cycle paths, empty if no cycles found.
     */
    fun detectCycles(): List<List<String>> {
        val adjacency = buildAdjacencyMap()
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun dfs(node: String, path: MutableList<String>) {
            visited.add(node)
            recursionStack.add(node)
            path.add(node)

            for (neighbor in adjacency[node].orEmpty()) {
                if (neighbor !in visited) {
                    dfs(neighbor, path)
                } else if (neighbor in recursionStack) {
                    // Found a cycle — extract it
                    val cycleStart = path.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        cycles.add(path.subList(cycleStart, path.size).toList() + neighbor)
                    }
                }
            }

            path.removeAt(path.lastIndex)
            recursionStack.remove(node)
        }

        for (node in _components.keys) {
            if (node !in visited) {
                dfs(node, mutableListOf())
            }
        }

        return cycles
    }

    // ── Incremental merge ──────────────────────────────────────────────

    /**
     * Merge components from another resolver into this one.
     * Useful when a new batch of streaming components arrives.
     */
    fun merge(other: ParentChildResolver) {
        other._components.values.forEach { put(it) }
    }

    // ── Internal: Edge extraction ──────────────────────────────────────

    /**
     * Extract all child component IDs referenced by a single component.
     * Covers all four A2UI reference types.
     */
    internal fun extractEdges(component: Component): Set<String> {
        val edges = mutableSetOf<String>()

        // 1. children array
        when (val children = component.children) {
            is ArrayChildList -> edges.addAll(children.array)
            is ObjectChildList -> edges.add(children.objectChild.componentId)
            else -> {}
        }

        // 2. child — single reference
        component.child?.takeIf { it.isNotBlank() }?.let { edges.add(it) }

        // 3. tabs / tabItems child references
        component.tabs?.forEach { tab ->
            tab.child?.takeIf { it.isNotBlank() }?.let { edges.add(it) }
        }
        component.tabItems?.forEach { tab ->
            tab.child?.takeIf { it.isNotBlank() }?.let { edges.add(it) }
        }

        // 4. trigger / content (Modal component)
        component.trigger?.takeIf { it.isNotBlank() }?.let { edges.add(it) }
        component.content?.takeIf { it.isNotBlank() }?.let { edges.add(it) }

        return edges
    }

    // ── Internal: Kahn's algorithm ─────────────────────────────────────

    private fun recompute() {
        val adjacency = buildAdjacencyMap()
        val inDegree = mutableMapOf<String, Int>()

        // Initialize in-degree for all known nodes
        for (id in _components.keys) {
            inDegree.getOrPut(id) { 0 }
        }

        // Build in-degree: edge parent → child means parent must come first,
        // so child has an incoming edge from parent.
        // Kahn's algorithm: in-degree = number of parents a node depends on.
        // But we want parents BEFORE children, so edges go parent → child.
        // In Kahn's, in-degree counts incoming edges. A child has in-degree from its parent.
        for ((parentId, childIds) in adjacency) {
            for (childId in childIds) {
                if (childId in _components) {
                    inDegree[childId] = inDegree.getOrPut(childId) { 0 } + 1
                }
            }
        }

        // Start with nodes that have no parents (in-degree == 0)
        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        // Sort the initial queue for deterministic output
        val initialNodes = queue.toList().sorted()
        queue.clear()
        queue.addAll(initialNodes)

        val result = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            // Deterministic: pick the lexicographically smallest among ready nodes
            val current = queue.removeFirst()
            result.add(current)

            for (childId in adjacency[current].orEmpty().sorted()) {
                if (childId in _components) {
                    inDegree[childId] = inDegree.getValue(childId) - 1
                    if (inDegree[childId] == 0) {
                        queue.add(childId)
                    }
                }
            }
        }

        // If result doesn't contain all components, there's a cycle.
        // Still include all components but mark unresolved ones at the end.
        if (result.size < _components.size) {
            for (id in _components.keys) {
                if (id !in result) {
                    result.add(id)
                }
            }
        }

        _sortedIds.clear()
        _sortedIds.addAll(result)
        _dirty = false
    }

    private fun buildAdjacencyMap(): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        for ((id, component) in _components) {
            val edges = extractEdges(component)
            if (edges.isNotEmpty()) {
                map[id] = edges.filter { it in _components }.toList().toMutableList()
            }
        }
        return map
    }

    // ── Data classes ───────────────────────────────────────────────────

    /** Represents a reference from [parentId] to a component [childId] that does not exist. */
    data class MissingReference(
        val childId: String,
        val parentId: String
    ) {
        override fun toString(): String = "$parentId → $childId (missing)"
    }
}
