package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WeatherSkill : Skill {
    override val id = "weather"
    override val name = "天气查询"
    override val description = "查询当前天气和天气预报"
    override val version = "2.0.0"

    override val instructions = """
# Weather Skill

查询天气信息，支持 wttr.in。

## 用法
- 用户询问天气时，调用 get_weather 工具
- 无需 API Key，直接可用
"""

    private var orchestrator: ScriptOrchestrator? = null
    private var scriptContent: String? = null

    override val tools: List<SkillTool> = listOf(
        WeatherTool()
    )

    private inner class WeatherTool : SkillTool {
        override val name = "get_weather"
        override val description = "获取指定位置的天气信息"
        override val parameters = mapOf(
            "location" to SkillParam(
                type = "string",
                description = "位置名称，如 '北京' 或 'Beijing'",
                required = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val location = params["location"] as? String
            if (location.isNullOrBlank()) {
                return SkillResult(false, "", "缺少 location 参数")
            }

            val orch = orchestrator
                ?: return SkillResult(false, "", "ScriptOrchestrator not initialized")
            val script = scriptContent
                ?: return SkillResult(false, "", "weather.js not loaded")

            try {
                val fullScript = "var LOCATION = \"$location\";\n$script"
                val result = orch.execute(fullScript, listOf("http"))

                if (!result.success) {
                    return SkillResult(false, "", result.error ?: "Script execution failed")
                }

                // Parse JSON result from script
                val json = Json.parseToJsonElement(result.output).jsonObject
                val success = json["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
                if (success) {
                    val data = json["data"]?.jsonPrimitive?.content ?: ""
                    return SkillResult(true, data)
                } else {
                    val error = json["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    return SkillResult(false, "", error)
                }
            } catch (e: Exception) {
                return SkillResult(false, "", "Error: ${e.message}")
            }
        }
    }

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
        scriptContent = context.applicationContext.assets
            .open("scripts/weather.js")
            .bufferedReader()
            .use { it.readText() }
    }

    override fun cleanup() {
        orchestrator = null
        scriptContent = null
    }
}
