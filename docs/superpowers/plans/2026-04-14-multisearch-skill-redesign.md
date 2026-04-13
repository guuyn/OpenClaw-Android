# MultiSearchSkill Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite MultiSearchSkill to use JS script execution (via ScriptOrchestrator) instead of hand-written JSON parsing, with A2UI card rendering for search results.

**Architecture:** Follow WeatherSkill's script-based pattern — Kotlin skill is a thin shell that loads `search.js`, injects the query as a JS variable, executes via ScriptOrchestrator with `http` capability, then wraps the structured results in A2UI tags for the existing `SearchCard` renderer.

**Tech Stack:** Kotlin, JavaScript (Rhino), kotlinx-serialization-json, ScriptOrchestrator + HttpBridge, A2UI protocol

---

## File Structure

| Operation | File | Responsibility |
|-----------|------|----------------|
| Create | `app/src/main/assets/scripts/search.js` | SearXNG search logic with multi-instance failover |
| Rewrite | `app/src/main/java/ai/openclaw/android/skill/builtin/MultiSearchSkill.kt` | Thin Kotlin shell: load script, execute, wrap A2UI |
| Rewrite | `app/src/test/java/ai/openclaw/android/skill/builtin/MultiSearchSkillTest.kt` | Tests using mocked ScriptOrchestrator (WeatherSkillTest pattern) |

No changes needed to `A2UICards.kt` — the existing `SearchCard` already handles flat-key format (`result1`, `snippet1`, etc.).

---

### Task 1: Create search.js

**Files:**
- Create: `app/src/main/assets/scripts/search.js`

- [ ] **Step 1: Create the search script**

```javascript
// search.js — SearXNG 多引擎搜索脚本，通过 http bridge 发起请求
// 调用方注入全局变量 QUERY（搜索关键词）

var INSTANCES = [
    "https://searx.work",
    "https://searxng.no-logs.com",
    "https://search.bus-hit.me"
];

function search(query) {
    for (var i = 0; i < INSTANCES.length; i++) {
        var url = INSTANCES[i] + "/search?q=" +
                  encodeURIComponent(query) + "&format=json";
        try {
            var resp = http.get(url);
            if (resp.status === 200 && resp.body) {
                var data = JSON.parse(resp.body);
                var items = data.results || [];
                var results = [];
                for (var j = 0; j < Math.min(items.length, 5); j++) {
                    results.push({
                        title: items[j].title || "",
                        snippet: items[j].content || "",
                        url: items[j].url || ""
                    });
                }
                if (results.length > 0) {
                    return JSON.stringify({success: true, results: results});
                }
            }
        } catch (e) {
            // 当前实例失败，继续尝试下一个
        }
    }
    return JSON.stringify({success: false, error: "所有搜索实例均不可用"});
}

search(QUERY);
```

- [ ] **Step 2: Verify script passes ScriptValidator**

The script must not contain any blocked patterns (`import`, `require`, `eval`, `setTimeout`, `java.`, etc.). The script above passes all checks — it only uses `JSON.parse`, `encodeURIComponent`, `http.get`, and basic JS constructs.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/scripts/search.js
git commit -m "feat: add search.js script for SearXNG multi-instance search"
```

---

### Task 2: Rewrite MultiSearchSkill.kt

**Files:**
- Rewrite: `app/src/main/java/ai/openclaw/android/skill/builtin/MultiSearchSkill.kt`

- [ ] **Step 1: Write the new MultiSearchSkill**

The new implementation follows the WeatherSkill pattern exactly: `ScriptOrchestrator` for execution, `kotlinx-serialization-json` for parsing JS output, A2UI tags for rich rendering.

Key differences from WeatherSkill:
- Uses `Json.encodeToString(query)` for safe QUERY injection (handles quotes in query strings)
- Builds A2UI output using flat-key format (`result1`, `snippet1`, `url1`, ...) to match existing `SearchCard` renderer in `A2UICards.kt`
- Includes both text summary (for LLM) and A2UI card (for UI) in output

```kotlin
package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MultiSearchSkill : Skill {
    override val id = "search"
    override val name = "多引擎搜索"
    override val description = "使用 SearXNG 搜索互联网信息"
    override val version = "2.0.0"

    override val instructions = """
# Multi-Search Skill

使用 SearXNG 进行搜索，无需 API Key。

## 用法
- 用户要求搜索信息时，调用 search 工具
- 返回搜索结果摘要与卡片展示
"""

    private var orchestrator: ScriptOrchestrator? = null
    private var scriptContent: String? = null

    override val tools: List<SkillTool> = listOf(SearchTool())

    private inner class SearchTool : SkillTool {
        override val name = "search"
        override val description = "搜索互联网信息"
        override val parameters = mapOf(
            "query" to SkillParam("string", "搜索关键词", true)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val query = params["query"] as? String
            if (query.isNullOrBlank()) {
                return SkillResult(false, "", "缺少 query 参数")
            }

            val orch = orchestrator
                ?: return SkillResult(false, "", "ScriptOrchestrator 未初始化")
            val script = scriptContent
                ?: return SkillResult(false, "", "search.js 未加载")

            try {
                val fullScript = "var QUERY = ${Json.encodeToString(query)};\n$script"
                val result = orch.execute(fullScript, listOf("http"))

                if (!result.success) {
                    return SkillResult(false, "", result.error ?: "脚本执行失败")
                }

                // 用 kotlinx-serialization 解析 JS 返回的 JSON
                val json = Json.parseToJsonElement(result.output).jsonObject
                val success = json["success"]?.jsonPrimitive?.boolean ?: false

                if (!success) {
                    val error = json["error"]?.jsonPrimitive?.content ?: "搜索失败"
                    return SkillResult(false, "", error)
                }

                val resultsArray = json["results"]?.jsonArray ?: emptyList()
                if (resultsArray.isEmpty()) {
                    return SkillResult(true, "关于 \"$query\" 未找到相关结果")
                }

                // 构建纯文本摘要（供 LLM 理解）
                val textSummary = buildTextSummary(query, resultsArray)

                // 构建 A2UI 卡片（供 UI 渲染）
                val a2ui = buildA2UICard(query, resultsArray)

                return SkillResult(true, "$textSummary\n\n$a2ui")
            } catch (e: Exception) {
                return SkillResult(false, "", "搜索错误: ${e.message}")
            }
        }

        private fun buildTextSummary(query: String, results: List<JsonElement>): String {
            val sb = StringBuilder("搜索 \"$query\" 的结果:\n\n")
            results.forEachIndexed { i, elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val snippet = obj["snippet"]?.jsonPrimitive?.content ?: ""
                sb.append("${i + 1}. $title\n")
                if (snippet.isNotEmpty()) {
                    sb.append("   $snippet\n")
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        private fun buildA2UICard(query: String, results: List<JsonElement>): String {
            val dataMap = mutableMapOf<String, JsonPrimitive>(
                "query" to JsonPrimitive(query)
            )
            results.forEachIndexed { i, elem ->
                val obj = elem.jsonObject
                val index = i + 1
                dataMap["result$index"] = JsonPrimitive(obj["title"]?.jsonPrimitive?.content ?: "")
                dataMap["snippet$index"] = JsonPrimitive(obj["snippet"]?.jsonPrimitive?.content ?: "")
                dataMap["url$index"] = JsonPrimitive(obj["url"]?.jsonPrimitive?.content ?: "")
            }
            val data = JsonObject(dataMap)
            return "[A2UI]\n{\"type\":\"search\",\"data\":$data}\n[/A2UI]"
        }
    }

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
        scriptContent = context.applicationContext.assets
            .open("scripts/search.js")
            .bufferedReader()
            .use { it.readText() }
    }

    override fun cleanup() {
        orchestrator = null
        scriptContent = null
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ai/openclaw/android/skill/builtin/MultiSearchSkill.kt
git commit -m "feat: rewrite MultiSearchSkill with script-based search and A2UI rendering"
```

---

### Task 3: Rewrite MultiSearchSkillTest.kt

**Files:**
- Rewrite: `app/src/test/java/ai/openclaw/android/skill/builtin/MultiSearchSkillTest.kt`

- [ ] **Step 1: Write the new test file**

Follow the WeatherSkillTest pattern: mock `ScriptOrchestrator` and inject via reflection. The tests validate the Kotlin shell logic (parameter validation, JSON parsing, A2UI wrapping), not the actual HTTP search (that's in the JS script).

```kotlin
package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.ScriptResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class MultiSearchSkillTest {

    @MockK
    private lateinit var mockContext: SkillContext

    @MockK
    private lateinit var mockOrchestrator: ScriptOrchestrator

    private lateinit var multiSearchSkill: MultiSearchSkill

    private val searchJs = """
        var INSTANCES = ["https://searx.work"];
        function search(query) {
            var url = INSTANCES[0] + "/search?q=" + encodeURIComponent(query) + "&format=json";
            var resp = http.get(url);
            if (resp.status === 200) {
                var data = JSON.parse(resp.body);
                var results = [];
                var items = data.results || [];
                for (var j = 0; j < Math.min(items.length, 5); j++) {
                    results.push({title: items[j].title || "", snippet: items[j].content || "", url: items[j].url || ""});
                }
                if (results.length > 0) return JSON.stringify({success: true, results: results});
            }
            return JSON.stringify({success: false, error: "搜索失败"});
        }
        search(QUERY);
    """.trimIndent()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        multiSearchSkill = MultiSearchSkill()

        val mockAppContext = mockk<android.content.Context>(relaxed = true)
        val mockAssets = mockk<android.content.res.AssetManager>(relaxed = true)
        every { mockAppContext.assets } returns mockAssets
        every { mockAssets.open("scripts/search.js") } returns ByteArrayInputStream(searchJs.toByteArray())
        every { mockContext.applicationContext } returns mockAppContext
    }

    private fun initWithMockOrchestrator() {
        multiSearchSkill.initialize(mockContext)
        val orchField = MultiSearchSkill::class.java.getDeclaredField("orchestrator")
        orchField.isAccessible = true
        orchField.set(multiSearchSkill, mockOrchestrator)
    }

    @Test
    fun `search valid query returns success with results`() = runTest {
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"Kotlin","snippet":"A modern language","url":"https://kotlinlang.org"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "Kotlin"))

        assertTrue(result.success)
        assertTrue(result.output.contains("Kotlin"))
        assertTrue(result.output.contains("A modern language"))
        assertTrue(result.output.contains("[A2UI]"))
        assertTrue(result.output.contains("\"type\":\"search\""))
        assertTrue(result.output.contains("\"result1\""))
    }

    @Test
    fun `search multiple results all included in output`() = runTest {
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"R1","snippet":"S1","url":"https://a.com"},{"title":"R2","snippet":"S2","url":"https://b.com"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "test"))

        assertTrue(result.success)
        assertTrue(result.output.contains("R1"))
        assertTrue(result.output.contains("R2"))
        assertTrue(result.output.contains("S1"))
        assertTrue(result.output.contains("S2"))
    }

    @Test
    fun `search script returns failure`() = runTest {
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":false,"error":"所有搜索实例均不可用"}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "test"))

        assertFalse(result.success)
        assertTrue(result.error?.contains("所有搜索实例均不可用") == true)
    }

    @Test
    fun `search orchestrator execution fails`() = runTest {
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.failure("Timeout (10000ms)")

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "test"))

        assertFalse(result.success)
        assertTrue(result.error?.contains("Timeout") == true)
    }

    @Test
    fun `search missing query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 query 参数") == true)
    }

    @Test
    fun `search empty query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to ""))

        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 query 参数") == true)
    }

    @Test
    fun `search blank query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "   "))

        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 query 参数") == true)
    }

    @Test
    fun `initialize loads script without error`() {
        kotlin.runCatching {
            multiSearchSkill.initialize(mockContext)
        }.onFailure {
            fail("Initialization threw exception: ${it.message}")
        }
    }

    @Test
    fun `skill metadata is correct`() {
        assertEquals("search", multiSearchSkill.id)
        assertEquals("多引擎搜索", multiSearchSkill.name)
        assertEquals("2.0.0", multiSearchSkill.version)
        assertEquals(1, multiSearchSkill.tools.size)
        assertEquals("search", multiSearchSkill.tools[0].name)
    }

    @Test
    fun `search A2UI card uses flat key format`() = runTest {
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"T1","snippet":"S1","url":"https://a.com"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = searchTool.execute(mapOf("query" to "test"))

        assertTrue(result.success)
        // 验证 A2UI 使用 flat-key 格式，匹配现有 SearchCard renderer
        val a2uiSection = result.output.substringAfter("[A2UI]\n").substringBefore("\n[/A2UI]")
        assertTrue(a2uiSection.contains("\"result1\""))
        assertTrue(a2uiSection.contains("\"snippet1\""))
        assertTrue(a2uiSection.contains("\"url1\""))
        assertTrue(a2uiSection.contains("\"query\""))
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:test --tests "ai.openclaw.android.skill.builtin.MultiSearchSkillTest"`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/ai/openclaw/android/skill/builtin/MultiSearchSkillTest.kt
git commit -m "test: rewrite MultiSearchSkillTest for script-based search"
```

---

### Task 4: Run full test suite and verify build

- [ ] **Step 1: Run all unit tests**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:test`
Expected: All tests PASS (including SkillManagerTest which references MultiSearchSkill)

- [ ] **Step 2: Build debug APK**

Run: `JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Final commit (if any fixes were needed)**

Only if tests or build required fixes. Otherwise skip.
