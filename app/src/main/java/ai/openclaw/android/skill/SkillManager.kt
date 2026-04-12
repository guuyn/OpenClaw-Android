package ai.openclaw.android.skill

// import ai.openclaw.android.script.ScriptSkill  // TODO: fix Rhino dependency
import ai.openclaw.android.skill.builtin.*
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient

class SkillManager(private val context: Context) {
    private val TAG = "SkillManager"
    private val loadedSkills: MutableMap<String, Skill> = mutableMapOf()
    private val httpClient = OkHttpClient()
    
    fun loadBuiltinSkills(context: Context) {
        Log.i(TAG, "Loading built-in skills...")
        
        // Register all built-in skills
        registerSkill(WeatherSkill())
        registerSkill(MultiSearchSkill())
        registerSkill(TranslateSkill())
        registerSkill(ReminderSkill(context))
        registerSkill(CalendarSkill(context))
        registerSkill(LocationSkill(context))
        registerSkill(ContactSkill(context))
        registerSkill(SMSSkill(context))
        registerSkill(AppLauncherSkill())
        registerSkill(SettingsSkill())
        // registerSkill(ScriptSkill())  // disabled - script module not compiled

        Log.i(TAG, "SkillManager initialized with ${loadedSkills.size} skills")
    }
    
    fun loadBuiltinSkills() {
        // Backward compatible overload - requires context for some skills
        Log.w(TAG, "loadBuiltinSkills() called without context - skills requiring context will not be loaded")
    }
    
    fun registerSkill(skill: Skill) {
        try {
            skill.initialize(createSkillContext())
            loadedSkills[skill.id] = skill
            Log.i(TAG, "Loaded skill: ${skill.name} v${skill.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load skill ${skill.id}: ${e.message}")
        }
    }
    
    fun getAllTools(): List<ToolDefinition> {
        return loadedSkills.flatMap { (skillId, skill) ->
            skill.tools.map { tool ->
                ToolDefinition(
                    name = "${skillId}_${tool.name}",
                    description = tool.description,
                    parameters = tool.parameters
                )
            }
        }
    }
    
    suspend fun executeTool(fullName: String, params: Map<String, Any>): SkillResult {
        val (skillId, toolName) = parseToolName(fullName)
        val skill = loadedSkills[skillId]
        
        if (skill == null) {
            return SkillResult(false, "", "Skill not found: $skillId")
        }
        
        val tool = skill.tools.find { it.name == toolName }
        if (tool == null) {
            return SkillResult(false, "", "Tool not found: $toolName in skill $skillId")
        }
        
        // 检查技能所需的权限
        val permissionResult = checkSkillPermissions(skillId)
        if (!permissionResult.first) {
            // 权限不足，返回权限请求信息
            return SkillResult(false, "", "需要权限: ${permissionResult.second}")
        }
        
        return tool.execute(params)
    }
    
    /**
     * 检查技能所需权限
     * @return Pair<Boolean, String> - 第一个元素表示是否有权限，第二个元素是缺少的权限信息
     */
    fun checkSkillPermissions(skillId: String): Pair<Boolean, String> {
        val permissions = getRequiredPermissionsForSkill(skillId)
        if (permissions == null) {
            // 该技能不需要特殊权限
            return Pair(true, "")
        }
        
        val hasPermissions = hasPermissions(context, permissions)
        if (hasPermissions) {
            return Pair(true, "")
        }
        
        // 返回缺少的权限列表
        val missingPermissions = permissions.filter { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        
        return Pair(false, missingPermissions.joinToString(", "))
    }
    
    /**
     * 获取技能所需的权限列表
     */
    fun getSkillRequiredPermissions(skillId: String): Array<String>? {
        return getRequiredPermissionsForSkill(skillId)
    }

    /**
     * 根据技能ID获取所需权限（public for PermissionManager）
     */
    fun getRequiredPermissionsForSkill(skillId: String): Array<String>? {
        return when (skillId) {
            "calendar" -> arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            )
            "location" -> arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            "contact" -> arrayOf(
                android.Manifest.permission.READ_CONTACTS
            )
            "sms" -> arrayOf(
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_SMS
            )
            else -> null
        }
    }
    
    /**
     * 检查是否拥有所有必需的权限
     */
    private fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun parseToolName(fullName: String): Pair<String, String> {
        val parts = fullName.split("_", limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair("", fullName)
        }
    }
    
    private fun createSkillContext(): SkillContext {
        return object : SkillContext {
            override val applicationContext: Context = context
            override val httpClient: OkHttpClient = this@SkillManager.httpClient
            override fun log(message: String) {
                Log.d(TAG, message)
            }
        }
    }
    
    fun getLoadedSkills(): Map<String, Skill> = loadedSkills.toMap()
    
    fun getSkillCount(): Int = loadedSkills.size
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParam>
)