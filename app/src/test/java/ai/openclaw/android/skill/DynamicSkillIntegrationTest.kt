package ai.openclaw.android.skill

import ai.openclaw.script.ScriptOrchestrator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端测试: JSON 注册 → 工具发现 → 安全策略
 */
class DynamicSkillIntegrationTest {

    private fun mockOrchestrator(): ScriptOrchestrator = mockk(relaxed = true)

    @Test
    fun `register skill from JSON and discover tool`() {
        // 模拟完整流程
        val skillJson = """
        {
            "id": "test_math",
            "name": "数学计算",
            "description": "简单数学计算",
            "version": "1.0.0",
            "instructions": "测试",
            "script": "function add(params) { return JSON.stringify({result: params.a + params.b}); }",
            "tools": [{
                "name": "add",
                "description": "两数相加",
                "parameters": {
                    "a": {"type": "number", "description": "第一个数", "required": true},
                    "b": {"type": "number", "description": "第二个数", "required": true}
                },
                "entryPoint": "add",
                "idempotent": true
            }]
        }
        """.trimIndent()

        // 1. 解析 JSON 创建 DynamicSkill
        val skill = DynamicSkill.fromJson(skillJson, mockOrchestrator())
        assertEquals("test_math", skill.id)
        assertEquals(1, skill.tools.size)
        assertEquals("add", skill.tools[0].name)

        // 2. 验证工具定义正确
        val tool = skill.tools[0]
        assertEquals("两数相加", tool.description)
        assertTrue(tool.parameters.containsKey("a"))
        assertTrue(tool.parameters.containsKey("b"))
    }

    @Test
    fun `security policy applied to idempotent and non-idempotent tools`() {
        val idempotentPolicy = SecurityReview.reviewTool("get_weather", true, null)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, idempotentPolicy)

        val nonIdempotentPolicy = SecurityReview.reviewTool("set_reminder", false, null)
        assertEquals(ToolSecurityPolicy.ASK_USER, nonIdempotentPolicy)

        val approvedPolicy = SecurityReview.reviewTool("send_email", false,
            UserApprovalPreference("test_send_email", ApprovalDecision.ALWAYS_APPROVE))
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, approvedPolicy)
    }
}
