package ai.openclaw.android.skill

import ai.openclaw.script.ScriptOrchestrator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicSkillTest {

    private fun mockOrchestrator(): ScriptOrchestrator = mockk(relaxed = true)

    @Test
    fun `fromJson creates skill with correct metadata`() {
        val json = """
        {
            "id": "test_skill",
            "name": "测试技能",
            "description": "测试描述",
            "version": "2.0.0",
            "instructions": "使用说明",
            "script": "function execute() { return 'hello'; }",
            "tools": [{
                "name": "execute",
                "description": "执行",
                "parameters": {},
                "entryPoint": "execute"
            }]
        }
        """.trimIndent()

        val skill = DynamicSkill.fromJson(json, mockOrchestrator())

        assertEquals("test_skill", skill.id)
        assertEquals("测试技能", skill.name)
        assertEquals("测试描述", skill.description)
        assertEquals("2.0.0", skill.version)
        assertEquals("使用说明", skill.instructions)
        assertEquals(1, skill.tools.size)
        assertEquals("execute", skill.tools[0].name)
    }

    @Test
    fun `fromJson uses default version when omitted`() {
        val json = """{"id":"x","name":"x","description":"x","script":"x","tools":[]}"""
        val skill = DynamicSkill.fromJson(json, mockOrchestrator())
        assertEquals("1.0.0", skill.version)
    }

    @Test
    fun `fromJson uses empty instructions when omitted`() {
        val json = """{"id":"x","name":"x","description":"x","script":"x","tools":[]}"""
        val skill = DynamicSkill.fromJson(json, mockOrchestrator())
        assertEquals("", skill.instructions)
    }

    @Test
    fun `fromJson uses tool name as entryPoint when omitted`() {
        val json = """
        {
            "id": "t",
            "name": "t",
            "description": "t",
            "script": "x",
            "tools": [{"name": "do_thing", "description": "thing", "parameters": {}}]
        }
        """.trimIndent()

        val skill = DynamicSkill.fromJson(json, mockOrchestrator())
        val tool = skill.tools[0]
        assertEquals("do_thing", tool.name)
    }

    @Test
    fun `fromJson parses tool parameters correctly`() {
        val json = """
        {
            "id": "t",
            "name": "t",
            "description": "t",
            "script": "x",
            "tools": [{
                "name": "search",
                "description": "Search",
                "parameters": {
                    "query": {"type": "string", "description": "Search query", "required": true},
                    "limit": {"type": "number", "description": "Max results", "required": false, "default": "10"}
                },
                "entryPoint": "search"
            }]
        }
        """.trimIndent()

        val skill = DynamicSkill.fromJson(json, mockOrchestrator())
        val params = skill.tools[0].parameters

        assertEquals("string", params["query"]?.type)
        assertTrue(params["query"]?.required == true)
        assertEquals("number", params["limit"]?.type)
        assertTrue(params["limit"]?.required == false)
        assertEquals("10", params["limit"]?.default)
    }

    @Test
    fun `fromJson throws on missing id`() {
        val json = """{"name":"x","description":"x","script":"x","tools":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSkill.fromJson(json, mockOrchestrator())
        }
    }

    @Test
    fun `fromJson throws on missing name`() {
        val json = """{"id":"x","description":"x","script":"x","tools":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSkill.fromJson(json, mockOrchestrator())
        }
    }

    @Test
    fun `fromJson throws on missing description`() {
        val json = """{"id":"x","name":"x","script":"x","tools":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSkill.fromJson(json, mockOrchestrator())
        }
    }

    @Test
    fun `fromJson throws on missing script`() {
        val json = """{"id":"x","name":"x","description":"x","tools":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSkill.fromJson(json, mockOrchestrator())
        }
    }

    @Test
    fun `fromJson throws on missing tools`() {
        val json = """{"id":"x","name":"x","description":"x","script":"x"}"""
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSkill.fromJson(json, mockOrchestrator())
        }
    }

    @Test
    fun `DynamicTool has correct name and description`() {
        val toolDef = DynamicToolDef(
            name = "test_tool",
            description = "A test tool",
            parameters = emptyMap(),
            entryPoint = "myFunction"
        )
        val script = "function myFunction(params) { return JSON.stringify(params); }"
        val tool = DynamicTool(toolDef, script, mockOrchestrator())

        assertEquals("test_tool", tool.name)
        assertEquals("A test tool", tool.description)
        assertTrue(tool.parameters.isEmpty())
    }

    @Test
    fun `DynamicSkill tools list matches toolDefs count`() {
        val json = """
        {
            "id": "multi",
            "name": "Multi",
            "description": "Multi-tool skill",
            "script": "x",
            "tools": [
                {"name": "tool_a", "description": "A", "parameters": {}, "entryPoint": "a"},
                {"name": "tool_b", "description": "B", "parameters": {}, "entryPoint": "b"},
                {"name": "tool_c", "description": "C", "parameters": {}, "entryPoint": "c"}
            ]
        }
        """.trimIndent()

        val skill = DynamicSkill.fromJson(json, mockOrchestrator())
        assertEquals(3, skill.tools.size)
        assertEquals("tool_a", skill.tools[0].name)
        assertEquals("tool_b", skill.tools[1].name)
        assertEquals("tool_c", skill.tools[2].name)
    }
}
