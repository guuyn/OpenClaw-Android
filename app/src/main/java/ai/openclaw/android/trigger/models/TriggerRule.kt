package ai.openclaw.android.trigger.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import kotlinx.serialization.Serializable

enum class EventSource {
    CRON, NOTIFICATION, ACCESSIBILITY, SYSTEM_BROADCAST, USER_ACTION
}

enum class MatchMode { CONTAINS, OR, AND, EXACT }

// ==================== Filters ====================

@Serializable
sealed class Filter {
    @Serializable
    data class PackageFilter(val packages: List<String>) : Filter()

    @Serializable
    data class KeywordFilter(val keywords: List<String>, val mode: MatchMode = MatchMode.OR) : Filter()

    @Serializable
    data class TimeFilter(val startHour: Int, val endHour: Int) : Filter()

    @Serializable
    data class CategoryFilter(val category: String) : Filter()
}

// ==================== Actions ====================

@Serializable
sealed class TriggerAction {
    @Serializable
    data class SkillCall(
        val skillId: String,
        val toolName: String,
        val paramsJson: String = "{}"  // JSON string to avoid Any? serialization issue
    ) : TriggerAction()

    @Serializable
    data class AgentQuery(
        val prompt: String,
        val model: String? = null
    ) : TriggerAction()

    @Serializable
    data class NotificationReply(
        val template: String,
        val autoReply: Boolean = false
    ) : TriggerAction()

    @Serializable
    data class CustomScript(val script: String) : TriggerAction()
}

// ==================== Trigger Rule Entity ====================

@Entity(
    tableName = "trigger_rules",
    indices = [Index("source"), Index("enabled")]
)
data class TriggerRule(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val source: EventSource,
    val filtersJson: String = "[]", // JSON array of Filter
    val actionJson: String, // JSON of TriggerAction
    val cooldownMs: Long = 300_000, // 5 min default
    val scheduleCron: String? = null, // Cron expression (CRON source only)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parseFilters(jsonStr: String): List<Filter> {
            return try {
                json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun parseAction(jsonStr: String): TriggerAction? {
            return try {
                json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                null
            }
        }

        fun serializeFilters(filters: List<Filter>): String {
            return try {
                json.encodeToString(filters)
            } catch (e: Exception) {
                "[]"
            }
        }

        fun serializeAction(action: TriggerAction): String {
            return try {
                json.encodeToString(action)
            } catch (e: Exception) {
                ""
            }
        }
    }

    fun getFilters(): List<Filter> = parseFilters(filtersJson)
    fun getAction(): TriggerAction? = parseAction(actionJson)
}
