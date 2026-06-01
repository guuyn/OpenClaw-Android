package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.TriggerAction
import ai.openclaw.android.trigger.models.Filter
import ai.openclaw.android.ConfigManager
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * TriggerConfig — v2 触发器配置数据模型
 *
 * sealed class 定义不同类型的触发器配置
 */
@Serializable
sealed class TriggerConfig {
    abstract val id: String
    abstract val name: String
    abstract val description: String
    abstract val enabled: Boolean
    abstract val condition: TriggerCondition
    abstract val action: TriggerActionDef

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 从 v1 TriggerRule 转换为 v2 TriggerConfig
         */
        fun fromRule(rule: TriggerRule): TriggerConfig {
            return TriggerConfigV1Compat(
                id = rule.id,
                name = rule.name,
                description = "v1 规则: ${rule.source.name}",
                enabled = rule.enabled,
                condition = TriggerCondition.fromRule(rule),
                action = TriggerActionDef.fromRuleAction(rule),
                originalRuleId = rule.id
            )
        }
    }
}

/**
 * v1 兼容包装
 */
@Serializable
data class TriggerConfigV1Compat(
    override val id: String,
    override val name: String,
    override val description: String,
    override val enabled: Boolean,
    override val condition: TriggerCondition,
    override val action: TriggerActionDef,
    val originalRuleId: String
) : TriggerConfig()

/**
 * 时间触发器
 */
@Serializable
data class TimeTrigger(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val enabled: Boolean = true,
    val cronExpression: String,
    val timezone: String = "Asia/Shanghai",
    override val action: TriggerActionDef
) : TriggerConfig() {
    override val condition: TriggerCondition = TimeCondition(cronExpression, timezone)
}

/**
 * 通知模式触发器
 */
@Serializable
data class NotificationPatternTrigger(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val enabled: Boolean = true,
    val packageName: String,
    val keywords: List<String>,
    val matchMode: String = "OR",
    val timeRange: Pair<Int, Int>? = null,
    override val action: TriggerActionDef
) : TriggerConfig() {
    override val condition: TriggerCondition = NotificationPatternCondition(
        packageName, keywords, matchMode, timeRange
    )
}

/**
 * 设备状态触发器
 */
@Serializable
data class DeviceStateTrigger(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val enabled: Boolean = true,
    val stateType: DeviceStateType,
    val threshold: Float = 0.5f,
    override val action: TriggerActionDef
) : TriggerConfig() {
    override val condition: TriggerCondition = DeviceStateCondition(stateType, threshold)
}

/**
 * 位置触发器
 */
@Serializable
data class LocationTrigger(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val enabled: Boolean = true,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f,
    override val action: TriggerActionDef
) : TriggerConfig() {
    override val condition: TriggerCondition = LocationCondition(latitude, longitude, radiusMeters)
}

/**
 * 自定义表达式触发器
 */
@Serializable
data class CustomExpressionTrigger(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val enabled: Boolean = true,
    val expression: String,
    override val action: TriggerActionDef
) : TriggerConfig() {
    override val condition: TriggerCondition = CustomExpressionCondition(expression)
}

// ==================== 条件类型 ====================

@Serializable
sealed class TriggerCondition {
    companion object {
        fun fromRule(rule: TriggerRule): TriggerCondition {
            return when (rule.source) {
                EventSource.CRON -> TimeCondition(rule.scheduleCron ?: "0 * * * *")
                EventSource.NOTIFICATION -> {
                    val filters = rule.getFilters()
                    val pkgFilter = filters.filterIsInstance<Filter.PackageFilter>().firstOrNull()
                    val keywordFilter = filters.filterIsInstance<Filter.KeywordFilter>().firstOrNull()
                    val timeFilter = filters.filterIsInstance<Filter.TimeFilter>().firstOrNull()
                    NotificationPatternCondition(
                        packageName = pkgFilter?.packages?.joinToString(",") ?: "",
                        keywords = keywordFilter?.keywords ?: emptyList(),
                        matchMode = keywordFilter?.mode?.name ?: "OR",
                        timeRange = timeFilter?.let { Pair(it.startHour, it.endHour) }
                    )
                }
                EventSource.SYSTEM_BROADCAST -> DeviceStateCondition(DeviceStateType.SCREEN_ON)
                EventSource.ACCESSIBILITY -> CustomExpressionCondition("accessibility_event")
                EventSource.USER_ACTION -> CustomExpressionCondition("manual_trigger")
            }
        }
    }
}

@Serializable
data class TimeCondition(val cronExpression: String, val timezone: String = "Asia/Shanghai") : TriggerCondition()

@Serializable
data class NotificationPatternCondition(
    val packageName: String,
    val keywords: List<String>,
    val matchMode: String,
    val timeRange: Pair<Int, Int>? = null
) : TriggerCondition()

@Serializable
data class DeviceStateCondition(val stateType: DeviceStateType, val threshold: Float = 0.5f) : TriggerCondition()

@Serializable
data class LocationCondition(val latitude: Double, val longitude: Double, val radiusMeters: Float) : TriggerCondition()

@Serializable
data class CustomExpressionCondition(val expression: String) : TriggerCondition()

/**
 * 设备状态类型
 */
@Serializable
enum class DeviceStateType {
    BATTERY_LOW,
    BATTERY_FULL,
    CHARGING,
    SCREEN_ON,
    SCREEN_OFF,
    POWER_SAVE_MODE,
    DO_NOT_DISTURB,
    WIFI_CONNECTED,
    BLUETOOTH_CONNECTED
}

// ==================== 动作定义 ====================

@Serializable
sealed class TriggerActionDef {
    companion object {
        fun fromRuleAction(rule: TriggerRule): TriggerActionDef {
            return when (val action = rule.getAction()) {
                is TriggerAction.SkillCall -> SkillCallActionDef(action.skillId, action.toolName, action.paramsJson)
                is TriggerAction.AgentQuery -> AgentQueryActionDef(action.prompt, action.model)
                is TriggerAction.NotificationReply -> NotificationReplyActionDef(action.template, action.autoReply)
                is TriggerAction.CustomScript -> CustomScriptActionDef(action.script)
                null -> NotificationActionDef("触发器: ${rule.name}")
            }
        }
    }
}

@Serializable
data class SkillCallActionDef(
    val skillId: String,
    val toolName: String,
    val paramsJson: String = "{}"
) : TriggerActionDef()

@Serializable
data class AgentQueryActionDef(
    val prompt: String,
    val model: String? = null
) : TriggerActionDef()

@Serializable
data class NotificationReplyActionDef(
    val template: String,
    val autoReply: Boolean = false
) : TriggerActionDef()

@Serializable
data class CustomScriptActionDef(val script: String) : TriggerActionDef()

@Serializable
data class NotificationActionDef(val message: String) : TriggerActionDef()

// ==================== 预设模板 ====================

object TriggerTemplates {

    /**
     * "重要通知提醒" 模板
     */
    fun importantNotificationReminder(): NotificationPatternTrigger {
        return NotificationPatternTrigger(
            name = "重要通知提醒",
            description = "当收到包含紧急关键词的通知时，自动向 AI 请求处理建议",
            packageName = "",
            keywords = listOf("紧急", "important", "urgent", "马上", "立即", "asap"),
            matchMode = "OR",
            action = AgentQueryActionDef(
                prompt = "收到一条重要通知: {notification.title} - {notification.text}。请简要分析并提供处理建议。"
            )
        )
    }

    /**
     * "通勤天气" 模板
     */
    fun commuteWeather(): TimeTrigger {
        return TimeTrigger(
            name = "通勤天气",
            description = "工作日早 8 点自动获取天气并提醒带伞/添衣",
            cronExpression = "0 8 * * 1-5",
            action = AgentQueryActionDef(
                prompt = "现在是早 8 点，请查询今天的天气并提醒我是否需要带伞或添衣。"
            )
        )
    }

    /**
     * "低电量提醒" 模板
     */
    fun lowBatteryReminder(): DeviceStateTrigger {
        return DeviceStateTrigger(
            name = "低电量提醒",
            description = "电量低于 15% 时提醒充电",
            stateType = DeviceStateType.BATTERY_LOW,
            threshold = 0.15f,
            action = NotificationActionDef("⚡ 电量低于 15%，请尽快充电！")
        )
    }

    /**
     * "充电完成提醒" 模板
     */
    fun chargingCompleteReminder(): DeviceStateTrigger {
        return DeviceStateTrigger(
            name = "充电完成提醒",
            description = "电量充满时提醒拔掉充电器",
            stateType = DeviceStateType.BATTERY_FULL,
            threshold = 1.0f,
            action = NotificationActionDef("🔋 电量已充满，可以拔掉充电器了！")
        )
    }

    /**
     * "工作时段免打扰" 模板
     */
    fun workHoursDnd(): TimeTrigger {
        return TimeTrigger(
            name = "工作时段免打扰",
            description = "工作日 9-18 点，非紧急通知静默处理",
            cronExpression = "0 9 * * 1-5",
            action = NotificationActionDef("工作时段：非紧急通知已静默")
        )
    }

    /**
     * 获取所有预设模板
     */
    fun getAllTemplates(): List<TriggerConfig> {
        return listOf(
            importantNotificationReminder(),
            commuteWeather(),
            lowBatteryReminder(),
            chargingCompleteReminder(),
            workHoursDnd()
        )
    }
}

// ==================== 配置管理器 ====================

/**
 * TriggerConfigManager — 触发器配置管理（加密存储）
 *
 * 使用 EncryptedSharedPreferences 存储 Trigger 配置的 JSON 序列化
 */
class TriggerConfigManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "openclaw_trigger_configs"
        private const val KEY_PREFIX = "trigger_config_"
        private const val KEY_LIST = "trigger_config_list"
    }

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 保存配置
     */
    fun save(config: TriggerConfig) {
        val jsonStr = json.encodeToString(TriggerConfig.serializer(), config)
        prefs.edit()
            .putString("${KEY_PREFIX}${config.id}", jsonStr)
            .apply()
        updateConfigList { ids -> ids + config.id }
    }

    /**
     * 加载单个配置
     */
    fun load(configId: String): TriggerConfig? {
        val jsonStr = prefs.getString("${KEY_PREFIX}$configId", null) ?: return null
        return try {
            json.decodeFromString(TriggerConfig.serializer(), jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 加载所有配置
     */
    fun loadAll(): List<TriggerConfig> {
        val ids = getConfigList()
        return ids.mapNotNull { id -> load(id) }
    }

    /**
     * 删除配置
     */
    fun delete(configId: String) {
        prefs.edit()
            .remove("${KEY_PREFIX}$configId")
            .apply()
        updateConfigList { ids -> ids - configId }
    }

    /**
     * 删除所有配置
     */
    fun deleteAll() {
        val ids = getConfigList()
        val editor = prefs.edit()
        ids.forEach { id ->
            editor.remove("${KEY_PREFIX}$id")
        }
        editor.putString(KEY_LIST, "[]")
        editor.apply()
    }

    /**
     * 从预设模板初始化（如果为空）
     */
    fun initDefaults() {
        if (loadAll().isEmpty()) {
            val defaults = TriggerTemplates.getAllTemplates()
            defaults.forEach { save(it) }
        }
    }

    private fun getConfigList(): List<String> {
        val jsonStr = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateConfigList(block: (List<String>) -> List<String>) {
        val current = getConfigList()
        val updated = block(current)
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(updated))
            .apply()
    }
}
