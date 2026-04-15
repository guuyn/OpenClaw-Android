package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.util.Log

/**
 * 生成并注册动态技能的工具
 * LLM 通过调用此工具来创建新技能
 */
class GenerateSkillTool(
    private val dynamicSkillManager: ai.openclaw.android.skill.DynamicSkillManager
) : SkillTool {

    companion object {
        private const val TAG = "GenerateSkillTool"
    }

    override val name = "generate_skill"
    override val description = """
        Generate and register a new dynamic skill from a JSON definition.
        The skill definition must include:
        - id: unique identifier (lowercase, underscores)
        - name: display name
        - description: what this skill does
        - version: semantic version (e.g. "1.0.0")
        - instructions: when and how to use this skill
        - script: JavaScript module implementing the skill's functions
        - tools: array of tool definitions, each with name, description, parameters, entryPoint, idempotent

        Each tool in the tools array must have:
        - name: tool name (lowercase, underscores)
        - description: what this tool does
        - parameters: object mapping parameter names to {type, description, required}
        - entryPoint: JavaScript function name to call
        - idempotent: boolean, true if the tool is a read-only operation

        Example:
        {
          "id": "weather",
          "name": "天气查询",
          "description": "查询城市天气",
          "version": "1.0.0",
          "instructions": "当用户询问天气时使用",
          "script": "function get_weather(params) { return http.get('https://wttr.in/' + params.city); }",
          "tools": [{
            "name": "get_weather",
            "description": "获取指定城市天气",
            "parameters": {"city": {"type": "string", "description": "城市名", "required": true}},
            "entryPoint": "get_weather",
            "idempotent": true
          }]
        }
    """.trimIndent()

    override val parameters = mapOf(
        "skillJson" to SkillParam(
            type = "string",
            description = "完整的技能定义 JSON",
            required = true
        )
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val skillJson = params["skillJson"] as? String
            ?: return SkillResult(false, "", "Missing 'skillJson' parameter. Pass a valid skill definition JSON.")

        if (skillJson.isBlank()) {
            return SkillResult(false, "", "skillJson cannot be empty.")
        }

        return try {
            val result = dynamicSkillManager.registerFromJson(skillJson)
            result.fold(
                onSuccess = { skillId ->
                    SkillResult(true, "Dynamic skill registered successfully: $skillId. The skill's tools are now available for use.", "")
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to register skill: ${e.message}")
                    SkillResult(false, "", "Failed to register skill: ${e.message ?: "Unknown error"}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            SkillResult(false, "", "Unexpected error: ${e.message ?: "Unknown error"}")
        }
    }
}
