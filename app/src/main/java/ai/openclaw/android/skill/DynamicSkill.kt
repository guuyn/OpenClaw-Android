package ai.openclaw.android.skill

import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.json.*

/**
 * 动态技能 — 由 LLM 生成、JS 脚本实现的 Skill
 *
 * 通过 fromJson() 从 LLM 返回的 JSON 创建，脚本由 ScriptOrchestrator 执行。
 */
class DynamicSkill(
    override val id: String,
    override val name: String,
    override val description: String,
    override val version: String,
    override val instructions: String,
    private val script: String,
    toolDefs: List<DynamicToolDef>,
    private val orchestrator: ScriptOrchestrator
) : Skill {

    override val tools: List<SkillTool> = toolDefs.map { def ->
        DynamicTool(def, script, orchestrator)
    }

    override fun initialize(context: SkillContext) {
        // No-op — JS engine is managed by ScriptOrchestrator
    }

    override fun cleanup() {
        // No-op — ScriptOrchestrator lifecycle is external
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 从 JSON 字符串创建 DynamicSkill
         *
         * @param jsonStr LLM 生成的技能定义 JSON
         * @param orchestrator 脚本执行器（由外部注入）
         * @throws IllegalArgumentException 缺少必填字段时
         */
        fun fromJson(jsonStr: String, orchestrator: ScriptOrchestrator): DynamicSkill {
            val element = json.parseToJsonElement(jsonStr).jsonObject
            val id = element["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'id'")
            val name = element["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'name'")
            val description = element["description"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'description'")
            val version = element["version"]?.jsonPrimitive?.content ?: "1.0.0"
            val instructions = element["instructions"]?.jsonPrimitive?.content ?: ""
            val script = element["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'script'")
            val toolsArray = element["tools"]?.jsonArray
                ?: throw IllegalArgumentException("Missing 'tools'")

            val toolDefs = toolsArray.map { toolElement ->
                val toolObj = toolElement.jsonObject
                val toolName = toolObj["name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Tool missing 'name'")
                val toolDesc = toolObj["description"]?.jsonPrimitive?.content ?: ""
                val entryPoint = toolObj["entryPoint"]?.jsonPrimitive?.content ?: toolName

                val paramsObj = toolObj["parameters"]?.jsonObject ?: JsonObject(emptyMap())
                val parameters = paramsObj.mapValues { (_, v) ->
                    val pObj = v.jsonObject
                    SkillParam(
                        type = pObj["type"]?.jsonPrimitive?.content ?: "string",
                        description = pObj["description"]?.jsonPrimitive?.content ?: "",
                        required = pObj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
                        default = pObj["default"]?.jsonPrimitive?.content
                    )
                }

                DynamicToolDef(
                    name = toolName,
                    description = toolDesc,
                    parameters = parameters,
                    entryPoint = entryPoint
                )
            }

            return DynamicSkill(id, name, description, version, instructions, script, toolDefs, orchestrator)
        }
    }
}

/**
 * 工具定义（从 JSON 解析）
 */
data class DynamicToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParam>,
    val entryPoint: String
)

/**
 * 动态工具实现 — 将 SkillTool 调用路由到 ScriptOrchestrator
 *
 * 执行时拼接：脚本源码 + 入口函数调用（参数以 JSON 传入）
 */
class DynamicTool(
    private val def: DynamicToolDef,
    private val script: String,
    private val orchestrator: ScriptOrchestrator
) : SkillTool {

    override val name: String = def.name
    override val description: String = def.description
    override val parameters: Map<String, SkillParam> = def.parameters

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        // 构建调用脚本：原始脚本 + 入口函数调用
        val paramsJson = buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()

        val callScript = buildString {
            appendLine(script)
            appendLine()
            appendLine("${def.entryPoint}($paramsJson)")
        }

        val result = orchestrator.execute(callScript, listOf("fs", "http"))
        return if (result.success) {
            SkillResult(success = true, output = result.output)
        } else {
            SkillResult(success = false, output = "", error = result.error ?: "Script execution failed")
        }
    }
}
