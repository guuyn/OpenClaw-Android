package ai.openclaw.android.skill.builtin

import ai.openclaw.android.data.local.DynamicSkillDao
import ai.openclaw.android.data.model.DynamicSkillEntity
import ai.openclaw.android.skill.DynamicSkillManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.UserPreferenceManager
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.script.ScriptOrchestrator
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * GenerateSkillTool 单元测试
 *
 * 验证:
 * - 有效 JSON 注册成功
 * - 缺少 skillJson 参数返回错误
 * - 空 skillJson 返回错误
 * - 无效 JSON（manager 返回失败）返回错误
 *
 * 注意: mockk 对 Kotlin Result 内联类有已知问题。
 * 使用真实 DynamicSkillManager + mock 依赖来避免 ClassCastException。
 */
class GenerateSkillToolTest {

    private val mockContext = mockk<android.content.Context>(relaxed = true)
    private val mockDao = mockk<DynamicSkillDao>(relaxed = true)
    private val mockSkillManager = mockk<SkillManager>(relaxed = true)
    private val mockOrchestrator = mockk<ScriptOrchestrator>(relaxed = true)
    private val mockPrefs = mockk<UserPreferenceManager>(relaxed = true)

    private lateinit var manager: DynamicSkillManager
    private lateinit var tool: GenerateSkillTool

    @Before
    fun setUp() {
        manager = DynamicSkillManager(
            context = mockContext,
            dynamicSkillDao = mockDao,
            skillManager = mockSkillManager,
            orchestrator = mockOrchestrator,
            preferenceManager = mockPrefs,
            onUserConfirmation = { _, _ -> null }
        )
        tool = GenerateSkillTool(manager)
    }

    @Test
    fun `execute with valid JSON returns success`() = runBlocking {
        val validJson = """
            {
                "id": "test_skill",
                "name": "测试",
                "description": "测试技能",
                "version": "1.0.0",
                "instructions": "测试",
                "script": "function test() {}",
                "tools": [{"name": "test", "description": "test", "parameters": {}, "entryPoint": "test", "idempotent": true}]
            }
        """.trimIndent()

        val result = tool.execute(mapOf("skillJson" to validJson))

        assertTrue("Expected success but got: ${result.error}", result.success)
        assertTrue(result.output.contains("test_skill"))
        coVerify { mockDao.insert(any()) }
        verify { mockSkillManager.registerSkill(any()) }
    }

    @Test
    fun `execute with missing skillJson returns error`() = runBlocking {
        val result = tool.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error?.contains("skillJson") == true)
    }

    @Test
    fun `execute with empty skillJson returns error`() = runBlocking {
        val result = tool.execute(mapOf("skillJson" to ""))

        assertFalse(result.success)
        assertTrue(result.error?.contains("empty") == true)
    }

    @Test
    fun `execute with invalid JSON returns error`() = runBlocking {
        // Missing required fields — DynamicSkillManager will fail to parse
        val result = tool.execute(mapOf("skillJson" to "not valid json"))

        assertFalse(result.success)
    }

    @Test
    fun `execute with blank skillJson returns error`() = runBlocking {
        val result = tool.execute(mapOf("skillJson" to "   "))

        assertFalse(result.success)
        assertTrue(result.error?.contains("empty") == true)
    }

    @Test
    fun `tool has correct metadata`() {
        assertEquals("generate_skill", tool.name)
        assertTrue(tool.description.contains("Generate and register"))
        assertTrue(tool.parameters.containsKey("skillJson"))
        assertTrue(tool.parameters["skillJson"]?.required == true)
        assertEquals("string", tool.parameters["skillJson"]?.type)
    }

    @Test
    fun `execute with missing required fields returns error`() = runBlocking {
        // Valid JSON but missing required fields
        val incompleteJson = """{"name": "no id", "description": "x"}"""

        val result = tool.execute(mapOf("skillJson" to incompleteJson))

        assertFalse(result.success)
    }
}
