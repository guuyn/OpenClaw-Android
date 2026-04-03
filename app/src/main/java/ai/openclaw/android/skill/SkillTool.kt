package ai.openclaw.android.skill

interface SkillTool {
    val name: String
    val description: String
    val parameters: Map<String, SkillParam>
    
    suspend fun execute(params: Map<String, Any>): SkillResult
}

data class SkillParam(
    val type: String,  // "string" | "number" | "boolean"
    val description: String,
    val required: Boolean,
    val default: Any? = null
)

data class SkillResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)