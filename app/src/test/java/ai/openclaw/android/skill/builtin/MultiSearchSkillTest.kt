package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.ScriptResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
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
        coEvery {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"Kotlin","snippet":"A modern language","url":"https://kotlinlang.org"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "Kotlin")) }

        assertTrue(result.success)
        assertTrue(result.output.contains("Kotlin"))
        assertTrue(result.output.contains("A modern language"))
        assertTrue(result.output.contains("[A2UI]"))
        assertTrue(result.output.contains("\"type\":\"search\""))
        assertTrue(result.output.contains("\"result1\""))
    }

    @Test
    fun `search multiple results all included in output`() = runTest {
        coEvery {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"R1","snippet":"S1","url":"https://a.com"},{"title":"R2","snippet":"S2","url":"https://b.com"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "test")) }

        assertTrue(result.success)
        assertTrue(result.output.contains("R1"))
        assertTrue(result.output.contains("R2"))
        assertTrue(result.output.contains("S1"))
        assertTrue(result.output.contains("S2"))
    }

    @Test
    fun `search script returns failure`() = runTest {
        coEvery {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":false,"error":"所有搜索实例均不可用"}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "test")) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("所有搜索实例均不可用") == true)
    }

    @Test
    fun `search orchestrator execution fails`() = runTest {
        coEvery {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.failure("Timeout (10000ms)")

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "test")) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("Timeout") == true)
    }

    @Test
    fun `search missing query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 query 参数") == true)
    }

    @Test
    fun `search empty query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "")) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 query 参数") == true)
    }

    @Test
    fun `search blank query parameter returns error`() = runTest {
        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "   ")) }

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
        coEvery {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success(
            """{"success":true,"results":[{"title":"T1","snippet":"S1","url":"https://a.com"}]}"""
        )

        initWithMockOrchestrator()

        val searchTool = multiSearchSkill.tools[0]
        val result = kotlinx.coroutines.runBlocking { searchTool.execute(mapOf("query" to "test")) }

        assertTrue(result.success)
        // 验证 A2UI 使用 flat-key 格式，匹配现有 SearchCard renderer
        val a2uiSection = result.output.substringAfter("[A2UI]\n").substringBefore("\n[/A2UI]")
        assertTrue(a2uiSection.contains("\"result1\""))
        assertTrue(a2uiSection.contains("\"snippet1\""))
        assertTrue(a2uiSection.contains("\"url1\""))
        assertTrue(a2uiSection.contains("\"query\""))
    }
}
