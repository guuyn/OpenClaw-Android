package ai.openclaw.android.skill

interface Skill {
    val id: String
    val name: String
    val description: String
    val version: String
    val instructions: String  // Markdown format, equivalent to SKILL.md
    val tools: List<SkillTool>
    
    fun initialize(context: SkillContext)
    fun cleanup()
}