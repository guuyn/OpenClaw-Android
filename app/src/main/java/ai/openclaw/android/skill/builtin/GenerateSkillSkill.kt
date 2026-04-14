package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.android.skill.DynamicSkillManager

/**
 * 动态技能生成 Skill
 * 提供 generate_skill 工具供 LLM 调用
 */
class GenerateSkillSkill(
    private val generateSkillTool: GenerateSkillTool
) : Skill {
    override val id = "dynamic_skill_generator"
    override val name = "动态技能生成"
    override val description = "生成和注册新的动态技能"
    override val version = "1.0.0"
    override val instructions = """
# 动态技能生成

当用户要求创建新技能时，使用 generate_skill 工具。
技能定义必须是有效的 JSON，包含 id, name, description, version, instructions, script, tools 字段。
"""
    override val tools: List<SkillTool> = listOf(generateSkillTool)
    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}
