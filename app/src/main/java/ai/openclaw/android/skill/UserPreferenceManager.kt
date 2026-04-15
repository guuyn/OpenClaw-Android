package ai.openclaw.android.skill

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 用户审批偏好管理器
 * 使用 JSON 文件存储（轻量，不需要额外 Room 表）
 *
 * 存储位置: dataDir/skill_approval_prefs.json
 *
 * @param dataDir 数据存储目录（生产环境使用 context.filesDir）
 */
class UserPreferenceManager(private val dataDir: File) {

    /**
     * Android 便捷构造函数
     */
    constructor(context: Context) : this(context.filesDir)

    private val prefsFile = File(dataDir, PREFS_FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Volatile
    private var preferences: Map<String, UserApprovalPreference> = loadPreferences()

    /**
     * 获取指定工具的审批偏好
     */
    fun getPreference(toolId: String): UserApprovalPreference? = preferences[toolId]

    /**
     * 设置工具的审批决策
     */
    fun setPreference(toolId: String, decision: ApprovalDecision) {
        val pref = UserApprovalPreference(toolId, decision)
        preferences = preferences + (toolId to pref)
        savePreferences()
    }

    /**
     * 清除指定工具的偏好
     */
    fun clearPreference(toolId: String) {
        preferences = preferences - toolId
        savePreferences()
    }

    /**
     * 获取所有偏好
     */
    fun getAllPreferences(): Map<String, UserApprovalPreference> = preferences.toMap()

    /**
     * 清除所有偏好
     */
    fun clearAll() {
        preferences = emptyMap()
        savePreferences()
    }

    private fun loadPreferences(): Map<String, UserApprovalPreference> {
        return try {
            if (prefsFile.exists()) {
                val content = prefsFile.readText()
                val prefsList = json.decodeFromString<List<UserApprovalPreference>>(content)
                prefsList.associateBy { it.toolId }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            println("[$LOG_TAG] Failed to load preferences: ${e.message}")
            e.printStackTrace()
            emptyMap()
        }
    }

    @Synchronized
    private fun savePreferences() {
        try {
            val content = json.encodeToString(preferences.values.toList())
            prefsFile.writeText(content)
        } catch (e: Exception) {
            // In JVM unit tests, android.util.Log may not be available
            println("[$LOG_TAG] Failed to save preferences: ${e.message}")
            e.printStackTrace()
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "skill_approval_prefs.json"
        private const val LOG_TAG = "UserPreferenceManager"
    }
}
