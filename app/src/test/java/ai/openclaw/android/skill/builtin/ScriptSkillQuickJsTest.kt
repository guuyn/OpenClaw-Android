package ai.openclaw.android.skill.builtin

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * 验证 ScriptSkill 与 QuickJS 引擎的集成
 *
 * 注：ScriptSkillMemoryTest 已覆盖 MemoryManager 相关测试，
 * 本测试聚焦于基础结构和 QuickJS 集成验证。
 */
class ScriptSkillQuickJsTest {

    @Test
    fun `ScriptSkill can be created with null MemoryManager`() {
        val skill = ScriptSkill(memoryManager = null)
        assertNotNull(skill)
        assertEquals("script", skill.id)
        assertEquals("Script Engine", skill.name)
    }

    @Test
    fun `ScriptSkill has execute_script tool`() {
        val skill = ScriptSkill(memoryManager = null)
        val tool = skill.tools.find { it.name == "execute_script" }
        assertNotNull("execute_script tool should exist", tool)
    }

    @Test
    fun `ScriptSkill tool has correct parameters`() {
        val skill = ScriptSkill(memoryManager = null)
        val tool = skill.tools.find { it.name == "execute_script" }!!
        assertEquals("execute_script", tool.name)
        assertTrue("Should have 'script' parameter", tool.parameters.containsKey("script"))
        assertTrue("Should have 'capabilities' parameter", tool.parameters.containsKey("capabilities"))
        assertTrue("script param should be required", tool.parameters["script"]?.required == true)
        assertEquals("string", tool.parameters["script"]?.type)
    }

    @Test
    fun `ScriptSkill without orchestrator returns not initialized error`() = runTest {
        val skill = ScriptSkill(memoryManager = null)
        val tool = skill.tools.find { it.name == "execute_script" }!!
        val result = tool.execute(mapOf(
            "script" to "1 + 1"
        ))
        assertFalse(result.success)
        assertTrue(result.error?.contains("not initialized") == true)
    }

    @Test
    fun `ScriptSkill missing script param returns error`() = runTest {
        val skill = ScriptSkill(memoryManager = null)
        val tool = skill.tools.find { it.name == "execute_script" }!!
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue("Should have error message", result.error?.isNotBlank() == true)
    }

    @Test
    fun `ScriptSkill version is set`() {
        val skill = ScriptSkill(memoryManager = null)
        assertEquals("0.1.0", skill.version)
    }
}
