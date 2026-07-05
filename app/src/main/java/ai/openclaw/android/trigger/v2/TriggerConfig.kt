package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.TriggerAction
import ai.openclaw.android.trigger.models.Filter
import ai.openclaw.android.ConfigManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.runBlocking
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

// ==================== v2 Config → v1 Rule 转换 ====================

/**
 * 统一入口：任意 v2 TriggerConfig → v1 TriggerRule。
 *
 * 与已有的按类型 dispatch 的 extension（TimeTrigger.toV1Rule() 等）保持一致；
 * 这个函数提供给调用方一个不需要 when 表达式的便捷入口。
 */
fun TriggerConfig.toV1Rule(): TriggerRule = when (this) {
    is TimeTrigger -> this.toV1Rule()
    is NotificationPatternTrigger -> this.toV1Rule()
    is DeviceStateTrigger -> this.toV1Rule()
    is LocationTrigger -> this.toV1Rule()
    is CustomExpressionTrigger -> this.toV1Rule()
    is TriggerConfigV1Compat -> this.toV1Rule()
}

/**
 * 将 v2 TimeTrigger 转 v1 TriggerRule。
 *
 * CRON source，filters 为空（cron 表达式自带 scheduleCron）。
 */
fun TimeTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = EventSource.CRON,
        scheduleCron = cronExpression,
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        ),
        filtersJson = "[]"
    )
}

/**
 * 将 v2 NotificationPatternTrigger 转 v1 TriggerRule。
 *
 * NOTIFICATION source，将 packageName/keywords/timeRange 拆分为 Filter 列表。
 */
fun NotificationPatternTrigger.toV1Rule(): TriggerRule {
    val filters = mutableListOf<Filter>()
    if (packageName.isNotBlank()) {
        filters.add(Filter.PackageFilter(listOf(packageName)))
    }
    if (keywords.isNotEmpty()) {
        val mode = when (matchMode) {
            "AND" -> ai.openclaw.android.trigger.models.MatchMode.AND
            "EXACT" -> ai.openclaw.android.trigger.models.MatchMode.EXACT
            else -> ai.openclaw.android.trigger.models.MatchMode.OR
        }
        filters.add(Filter.KeywordFilter(keywords, mode))
    }
    timeRange?.let { (start, end) ->
        filters.add(Filter.TimeFilter(start, end))
    }

    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = EventSource.NOTIFICATION,
        filtersJson = TriggerRule.serializeFilters(filters),
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

/**
 * 将 v2 DeviceStateTrigger 转 v1 TriggerRule。
 *
 * SYSTEM_BROADCAST source，filters 为空（具体状态由 v2 condition 推断）。
 */
fun DeviceStateTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = EventSource.SYSTEM_BROADCAST,
        filtersJson = "[]",
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

/**
 * 将 v2 LocationTrigger 转 v1 TriggerRule。
 *
 * USER_ACTION source（v1 没有原生 location 事件类型，回退为手动触发）。
 */
fun LocationTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = EventSource.USER_ACTION,
        filtersJson = "[]",
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

/**
 * 将 v2 CustomExpressionTrigger 转 v1 TriggerRule。
 *
 * USER_ACTION source（v1 没有自定义表达式评估器）。
 */
fun CustomExpressionTrigger.toV1Rule(): TriggerRule {
    return TriggerRule(
        id = id,
        name = name,
        enabled = enabled,
        source = EventSource.USER_ACTION,
        filtersJson = "[]",
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

/**
 * 将 v2 TriggerConfigV1Compat 转 v1 TriggerRule（按 v1 原 id 写入，保留原行为）。
 */
fun TriggerConfigV1Compat.toV1Rule(): TriggerRule {
    // 由 v1 rule 包装而来 — 用 originalRuleId 走 dao.getById 查找并直接复用
    return TriggerRule(
        id = originalRuleId,
        name = name,
        enabled = enabled,
        source = when (condition) {
            is TimeCondition -> EventSource.CRON
            is NotificationPatternCondition -> EventSource.NOTIFICATION
            is DeviceStateCondition -> EventSource.SYSTEM_BROADCAST
            is LocationCondition -> EventSource.USER_ACTION
            is CustomExpressionCondition -> EventSource.USER_ACTION
        },
        scheduleCron = (condition as? TimeCondition)?.cronExpression,
        filtersJson = when (val c = condition) {
            is NotificationPatternCondition -> {
                val filters = mutableListOf<Filter>()
                if (c.packageName.isNotBlank()) {
                    filters.add(Filter.PackageFilter(listOf(c.packageName)))
                }
                if (c.keywords.isNotEmpty()) {
                    filters.add(Filter.KeywordFilter(c.keywords, ai.openclaw.android.trigger.models.MatchMode.OR))
                }
                c.timeRange?.let { (s, e) -> filters.add(Filter.TimeFilter(s, e)) }
                TriggerRule.serializeFilters(filters)
            }
            else -> "[]"
        },
        actionJson = TriggerRule.serializeAction(
            when (action) {
                is SkillCallActionDef -> TriggerAction.SkillCall(
                    action.skillId, action.toolName, action.paramsJson
                )
                is AgentQueryActionDef -> TriggerAction.AgentQuery(action.prompt, action.model)
                is NotificationReplyActionDef -> TriggerAction.NotificationReply(action.template, action.autoReply)
                is CustomScriptActionDef -> TriggerAction.CustomScript(action.script)
                is NotificationActionDef -> TriggerAction.AgentQuery(action.message)
            }
        )
    )
}

// ==================== 配置管理器 ====================

/**
 * TriggerConfigManager — 触发器配置管理（Room 持久化）
 *
 * 设计变更 (2026-07-05, fix for "数量一直是 0" bug)：
 * - 之前：使用 EncryptedSharedPreferences (`openclaw_trigger_configs`) 单独存储 v2 配置，
 *   与 v1 Room `trigger_rules` 表并存但互不相通。
 * - 现在：统一到 v1 的 Room `trigger_rules` 表（EncryptedSharedPreferences 已被 AppDatabase
 *   内的 SQLCipher 加密保护）。TriggerConfig ↔ TriggerRule 通过 `toV1Rule()` / `fromRule()`
 *   互转，单一数据源 = dao。
 *
 * 保留此类的目的：
 * 1. 作为 v2 TriggerConfig API 的稳定入口（兼容外部调用方，例如 TriggerViewModel）。
 * 2. 提供 initDefaults() 在应用启动时种入 5 个预设模板，并支持从旧的 EncryptedSharedPreferences
 *    迁移已存在的数据（一次性迁移，读完即删）。
 */
class TriggerConfigManager(
    private val dao: TriggerRuleDao,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "TriggerConfigManager"

        // 旧版 EncryptedSharedPreferences 标识（迁移用）
        private const val LEGACY_PREFS_NAME = "openclaw_trigger_configs"
        private const val LEGACY_KEY_PREFIX = "trigger_config_"
        private const val LEGACY_KEY_LIST = "trigger_config_list"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 保存配置：将 v2 TriggerConfig 转 v1 TriggerRule 后写入 dao。
     *
     * v1 dao 使用 OnConflictStrategy.REPLACE，所以同一个 id 的多次保存是幂等的。
     */
    fun save(config: TriggerConfig) {
        val rule = config.toV1Rule()
        runBlocking { dao.insert(rule) }
    }

    /**
     * 加载单个配置
     */
    fun load(configId: String): TriggerConfig? {
        val rule = runBlocking { dao.getById(configId) } ?: return null
        return TriggerConfig.fromRule(rule)
    }

    /**
     * 加载所有配置
     */
    fun loadAll(): List<TriggerConfig> {
        val rules = runBlocking { dao.getAll() }
        return rules.map { TriggerConfig.fromRule(it) }
    }

    /**
     * 删除配置
     */
    fun delete(configId: String) {
        runBlocking { dao.deleteById(configId) }
    }

    /**
     * 删除所有配置
     */
    fun deleteAll() {
        val rules = runBlocking { dao.getAll() }
        runBlocking {
            rules.forEach { dao.delete(it) }
        }
    }

    /**
     * 从预设模板初始化（如果为空）。
     *
     * 行为：
     * 1. 如果有 Context 且旧版 EncryptedSharedPreferences 有遗留数据 → 迁移到 dao，然后清空旧 prefs。
     * 2. 如果 dao 仍然为空 → 写入 5 个预设模板。
     * 3. 如果 dao 已有规则 → 跳过模板插入，保留用户现有数据。
     *
     * 注意：dao 的 insert 是 REPLACE 策略，所以重复调用是安全的（不会复制）。
     */
    fun initDefaults() {
        // Step 1: 旧版 EncryptedSharedPreferences 数据迁移
        if (context != null) {
            migrateFromLegacyIfNeeded()
        }

        // Step 2: 如果 dao 仍然为空，写入预设模板
        val current = runBlocking { dao.getAll() }
        if (current.isEmpty()) {
            Log.i(TAG, "DAO empty, seeding ${TriggerTemplates.getAllTemplates().size} default templates")
            val defaults = TriggerTemplates.getAllTemplates()
            defaults.forEach { save(it) }
        } else {
            Log.i(TAG, "DAO already has ${current.size} rules, skip default seeding")
        }
    }

    /**
     * 从旧版 EncryptedSharedPreferences 迁移数据。
     *
     * 一次性迁移：成功迁移后立即清空旧 prefs，避免下次启动重复处理。
     */
    private fun migrateFromLegacyIfNeeded() {
        val ctx = context ?: return
        var legacyPrefs: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            legacyPrefs = EncryptedSharedPreferences.create(
                ctx,
                LEGACY_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val listJson = legacyPrefs.getString(LEGACY_KEY_LIST, null)
            if (listJson.isNullOrBlank()) {
                // No legacy data; clean up empty entry and return
                legacyPrefs.edit().clear().apply()
                return
            }

            val ids = try {
                json.decodeFromString<List<String>>(listJson)
            } catch (e: Exception) {
                emptyList()
            }

            if (ids.isEmpty()) {
                legacyPrefs.edit().clear().apply()
                return
            }

            var migrated = 0
            var skipped = 0
            for (id in ids) {
                val raw = legacyPrefs.getString("$LEGACY_KEY_PREFIX$id", null)
                if (raw.isNullOrBlank()) {
                    skipped++
                    continue
                }
                val config: TriggerConfig? = try {
                    json.decodeFromString(TriggerConfig.serializer(), raw)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode legacy config id=$id: ${e.message}")
                    null
                }
                if (config == null) {
                    skipped++
                    continue
                }
                try {
                    save(config)
                    migrated++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate legacy config id=$id: ${e.message}")
                    skipped++
                }
            }

            // 迁移完成，清空旧 prefs（无论成功失败，重复运行都安全）
            legacyPrefs.edit().clear().apply()

            Log.i(TAG, "Legacy migration done: migrated=$migrated skipped=$skipped")
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration skipped due to error: ${e.message}")
            // 尝试清空 legacy prefs（防止后续再次阻塞）
            try {
                legacyPrefs?.edit()?.clear()?.apply()
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
