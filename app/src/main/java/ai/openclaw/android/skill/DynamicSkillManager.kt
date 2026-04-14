package ai.openclaw.android.skill

import ai.openclaw.android.data.local.DynamicSkillDao
import ai.openclaw.android.data.model.DynamicSkillEntity
import ai.openclaw.script.ScriptOrchestrator
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * 动态技能管理器
 *
 * 负责:
 * - 从 JSON 注册新技能（运行时 + 持久化）
 * - 启动时加载所有已保存的技能
 * - 生命周期管理：30 天未使用停用，90 天已停用删除
 * - 手动启用/停用/删除
 */
class DynamicSkillManager(
    private val context: Context,
    private val dynamicSkillDao: DynamicSkillDao,
    private val skillManager: SkillManager,
    private val orchestrator: ScriptOrchestrator,
    private val preferenceManager: UserPreferenceManager,
    private val onUserConfirmation: suspend (toolId: String, description: String) -> ApprovalDecision?
) {
    companion object {
        private const val TAG = "DynamicSkillManager"
        private const val DISABLE_AFTER_DAYS = 30
        private const val PURGE_AFTER_DAYS = 90
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 从 JSON 注册新技能
     *
     * @param json LLM 生成的技能定义 JSON
     * @return 成功返回技能 ID，失败返回异常
     */
    suspend fun registerFromJson(json: String): Result<String> = try {
        val skill = DynamicSkill.fromJson(json, orchestrator, preferenceManager, onUserConfirmation)

        // 持久化
        val entity = DynamicSkillEntity(
            id = skill.id,
            name = skill.name,
            description = skill.description,
            version = skill.version,
            category = "custom",
            instructions = skill.instructions,
            script = skill.script,
            toolsJson = json,
            permissions = "",
            lastUsedAt = 0,
            enabled = true
        )
        dynamicSkillDao.insert(entity)

        // 运行时注册
        skillManager.registerSkill(skill)

        Log.i(TAG, "Registered dynamic skill: ${skill.id}")
        Result.success(skill.id)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to register skill: ${e.message}")
        Result.failure(e)
    }

    /**
     * 启动时加载所有已保存的动态技能
     *
     * @return 成功加载的技能数量
     */
    suspend fun loadAllSaved(): Int {
        val entities = dynamicSkillDao.getAllEnabledList()
        var count = 0
        for (entity in entities) {
            try {
                val skill = DynamicSkill.fromJson(entity.toolsJson, orchestrator, preferenceManager, onUserConfirmation)
                skillManager.registerSkill(skill)
                count++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dynamic skill ${entity.id}: ${e.message}")
            }
        }
        Log.i(TAG, "Loaded $count dynamic skills from database")
        return count
    }

    /**
     * 30 天未使用 → 停用
     */
    suspend fun disableUnusedSkills() {
        val threshold = System.currentTimeMillis() - (DISABLE_AFTER_DAYS * 24 * 60 * 60 * 1000L)
        val unusedSkills = dynamicSkillDao.getEnabledSkillsLastUsedBefore(threshold)
        for (skill in unusedSkills) {
            dynamicSkillDao.disable(skill.id)
            skillManager.unregisterSkill(skill.id)
            Log.i(TAG, "Disabled unused skill: ${skill.id}")
        }
    }

    /**
     * 90 天已停用 → 彻底删除
     */
    suspend fun purgeDisabledSkills() {
        val threshold = System.currentTimeMillis() - (PURGE_AFTER_DAYS * 24 * 60 * 60 * 1000L)
        val disabledSkills = dynamicSkillDao.getDisabledSkillsDisabledBefore(threshold)
        for (skill in disabledSkills) {
            dynamicSkillDao.deleteById(skill.id)
            Log.i(TAG, "Purged disabled skill: ${skill.id}")
        }
    }

    /**
     * 手动删除技能（从数据库 + 运行时 + 偏好）
     */
    suspend fun removeSkill(id: String) {
        dynamicSkillDao.deleteById(id)
        skillManager.unregisterSkill(id)
        preferenceManager.clearPreference(id)
        Log.i(TAG, "Removed skill: $id")
    }

    /**
     * 手动停用技能
     */
    suspend fun disableSkill(id: String) {
        dynamicSkillDao.disable(id)
        skillManager.unregisterSkill(id)
        Log.i(TAG, "Disabled skill: $id")
    }

    /**
     * 手动启用技能
     */
    suspend fun enableSkill(id: String) {
        val entity = dynamicSkillDao.getById(id) ?: return
        dynamicSkillDao.enable(id)
        val skill = DynamicSkill.fromJson(entity.toolsJson, orchestrator, preferenceManager, onUserConfirmation)
        skillManager.registerSkill(skill)
        Log.i(TAG, "Enabled skill: $id")
    }

    /**
     * 记录技能使用（更新 lastUsedAt）
     */
    suspend fun recordUsage(id: String) {
        dynamicSkillDao.updateLastUsed(id, System.currentTimeMillis())
    }

    /**
     * 定时维护任务（可被 WorkManager 调用）
     */
    suspend fun runMaintenance() {
        disableUnusedSkills()
        purgeDisabledSkills()
    }

    /**
     * 清理协程作用域
     */
    fun cleanup() {
        scope.cancel()
    }
}
