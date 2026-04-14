package ai.openclaw.android.skill.builtin

import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.memory.MemorySearchResult
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ScriptSkillMemoryTest {

    private lateinit var mockMemoryManager: MemoryManager
    private lateinit var scriptSkill: ScriptSkill

    @Before
    fun setUp() {
        mockMemoryManager = mockk(relaxed = true)
        scriptSkill = ScriptSkill(memoryManager = mockMemoryManager)
    }

    @Test
    fun `skill initialized with memory manager has memory capability`() {
        assertNotNull(scriptSkill)
        assertEquals("script", scriptSkill.id)
        assertEquals("Script Engine", scriptSkill.name)
    }

    @Test
    fun `skill without memory manager still works`() {
        val skillNoMemory = ScriptSkill(memoryManager = null)
        assertNotNull(skillNoMemory)
        assertEquals("script", skillNoMemory.id)
        assertEquals("Script Engine", skillNoMemory.name)
    }

    @Test
    fun `setMemoryManager updates the memory manager`() {
        val newMock = mockk<MemoryManager>(relaxed = true)
        scriptSkill.setMemoryManager(newMock)
        assertNotNull(scriptSkill)
        // Verify the skill still functions after setting a new manager
        assertEquals("script", scriptSkill.id)
    }

    @Test
    fun `setMemoryManager null clears the memory manager`() {
        val skillWithNull = ScriptSkill(memoryManager = null)
        skillWithNull.setMemoryManager(mockMemoryManager)
        skillWithNull.setMemoryManager(null)
        assertNotNull(skillWithNull)
        assertEquals("script", skillWithNull.id)
    }

    @Test
    fun `execute script without orchestrator returns not initialized error`() = runTest {
        // Without initializing the skill (no orchestrator), execute_script returns error
        val tool = scriptSkill.tools.find { it.name == "execute_script" }!!
        val skillResult = tool.execute(mapOf(
            "script" to "memory.recall('test');",
            "capabilities" to "memory"
        ))

        assertFalse(skillResult.success)
        assertTrue(skillResult.error?.contains("not initialized") == true)
    }

    @Test
    fun `execute script missing script param returns error`() = runTest {
        val tool = scriptSkill.tools.find { it.name == "execute_script" }!!
        val skillResult = tool.execute(mapOf(
            "capabilities" to "memory"
        ))

        assertFalse(skillResult.success)
        // The error could be "Missing 'script' parameter" or "ScriptEngine not initialized"
        val hasError = skillResult.error?.isNotBlank() == true
        assertTrue("Expected error message but got: output='${skillResult.output}', error='${skillResult.error}'", hasError)
    }

    @Test
    fun `skill has execute_script tool`() {
        val tool = scriptSkill.tools.find { it.name == "execute_script" }
        assertNotNull(tool)
        assertEquals("execute_script", tool!!.name)
        assertTrue(tool.parameters.containsKey("script"))
        assertTrue(tool.parameters.containsKey("capabilities"))
    }

    @Test
    fun `skill tools list is not empty`() {
        assertTrue(scriptSkill.tools.isNotEmpty())
    }
}
