package ai.openclaw.android.skill

import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * 用户确认回调类型
 * @return 用户的决策，null 表示取消
 */
typealias UserConfirmationCallback = suspend (toolId: String, description: String) -> ApprovalDecision?

/**
 * 动态技能 — 由 LLM 生成、JS 脚本实现的 Skill
 *
 * 通过 fromJson() 从 LLM 返回的 JSON 创建，脚本由 ScriptOrchestrator 执行。
 *
 * @param preferenceManager 用户偏好管理器（可选，null 时禁用安全检查）
 * @param onUserConfirmation 用户确认回调（可选，null 时 ASK_USER 策略视为 DENY）
 */
class DynamicSkill(
    override val id: String,
    override val name: String,
    override val description: String,
    override val version: String,
    override val instructions: String,
    val script: String,
    toolDefs: List<DynamicToolDef>,
    private val orchestrator: ScriptOrchestrator,
    private val preferenceManager: UserPreferenceManager? = null,
    private val onUserConfirmation: UserConfirmationCallback? = null
) : Skill {

    override val tools: List<SkillTool> = toolDefs.map { def ->
        DynamicTool(id, def, script, orchestrator, preferenceManager, onUserConfirmation)
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
         * @param preferenceManager 用户偏好管理器（可选）
         * @param onUserConfirmation 用户确认回调（可选）
         * @throws IllegalArgumentException 缺少必填字段时
         */
        fun fromJson(
            jsonStr: String,
            orchestrator: ScriptOrchestrator,
            preferenceManager: UserPreferenceManager? = null,
            onUserConfirmation: UserConfirmationCallback? = null
        ): DynamicSkill {
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
                val isIdempotent = toolObj["idempotent"]?.jsonPrimitive?.booleanOrNull ?: false

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
                    entryPoint = entryPoint,
                    isIdempotent = isIdempotent
                )
            }

            return DynamicSkill(
                id, name, description, version, instructions, script, toolDefs, orchestrator,
                preferenceManager, onUserConfirmation
            )
        }
    }
}

/**
 * 工具定义（从 JSON 解析）
 *
 * @param isIdempotent 是否幂等操作（幂等操作无需用户审批）
 */
data class DynamicToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParam>,
    val entryPoint: String,
    val isIdempotent: Boolean = false
)

/**
 * 动态工具实现 — 将 SkillTool 调用路由到 ScriptOrchestrator
 *
 * 执行时拼接：脚本源码 + 入口函数调用（参数以 JSON 传入）
 * 内置安全审查：幂等操作自动执行，非幂等操作按用户偏好处理
 *
 * @param skillId 所属技能 ID，用于构建完整工具 ID
 * @param preferenceManager 用户偏好管理器（可选）
 * @param onUserConfirmation 用户确认回调（可选）
 */
class DynamicTool(
    private val skillId: String,
    private val def: DynamicToolDef,
    private val script: String,
    private val orchestrator: ScriptOrchestrator,
    private val preferenceManager: UserPreferenceManager? = null,
    private val onUserConfirmation: UserConfirmationCallback? = null
) : SkillTool {

    /**
     * 兼容旧版构造函数（无安全检查）
     */
    constructor(
        def: DynamicToolDef,
        script: String,
        orchestrator: ScriptOrchestrator
    ) : this("", def, script, orchestrator, null, null)

    override val name: String = def.name
    override val description: String = def.description
    override val parameters: Map<String, SkillParam> = def.parameters

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val toolId = if (skillId.isNotBlank()) "${skillId}_${def.name}" else def.name

        // 安全审查
        val preference = preferenceManager?.getPreference(toolId)
        val policy = SecurityReview.reviewTool(def.name, def.isIdempotent, preference)

        return when (policy) {
            ToolSecurityPolicy.AUTO_EXECUTE -> {
                executeScript(params)
            }

            ToolSecurityPolicy.ASK_USER -> {
                val decision = onUserConfirmation?.invoke(toolId, def.description)
                when (decision) {
                    ApprovalDecision.ALWAYS_APPROVE -> {
                        preferenceManager?.setPreference(toolId, decision)
                        executeScript(params)
                    }

                    ApprovalDecision.ALWAYS_DENY -> {
                        preferenceManager?.setPreference(toolId, decision)
                        SkillResult(false, "", "用户已拒绝此操作")
                    }

                    ApprovalDecision.ASK_EVERY_TIME -> {
                        // 仅此次执行，不保存偏好
                        executeScript(params)
                    }

                    null -> SkillResult(false, "", "用户取消了操作")
                }
            }

            ToolSecurityPolicy.DENY -> {
                SkillResult(false, "", "此操作已被用户拒绝")
            }
        }
    }

    private suspend fun executeScript(params: Map<String, Any>): SkillResult {
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
