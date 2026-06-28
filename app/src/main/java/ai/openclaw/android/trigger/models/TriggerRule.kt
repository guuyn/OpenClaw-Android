package ai.openclaw.android.trigger.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

enum class EventSource {
    CRON, NOTIFICATION, ACCESSIBILITY, SYSTEM_BROADCAST, USER_ACTION
}

enum class MatchMode { CONTAINS, OR, AND, EXACT }

// ==================== Filters ====================

@Serializable
sealed class Filter {
    @Serializable
    @SerialName("PackageFilter")
    data class PackageFilter(val packages: List<String>) : Filter()

    @Serializable
    @SerialName("KeywordFilter")
    data class KeywordFilter(val keywords: List<String>, val mode: MatchMode = MatchMode.OR) : Filter()

    @Serializable
    @SerialName("TimeFilter")
    data class TimeFilter(val startHour: Int, val endHour: Int) : Filter()

    @Serializable
    @SerialName("CategoryFilter")
    data class CategoryFilter(val category: String) : Filter()
}

// ==================== Actions ====================

@Serializable
sealed class TriggerAction {
    @Serializable
    @SerialName("SkillCall")
    data class SkillCall(
        val skillId: String,
        val toolName: String,
        val paramsJson: String = "{}"  // JSON string to avoid Any? serialization issue
    ) : TriggerAction()

    @Serializable
    @SerialName("AgentQuery")
    data class AgentQuery(
        val prompt: String,
        val model: String? = null
    ) : TriggerAction()

    @Serializable
    @SerialName("NotificationReply")
    data class NotificationReply(
        val template: String,
        val autoReply: Boolean = false
    ) : TriggerAction()

    @Serializable
    @SerialName("CustomScript")
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
        // NOTE: The sealed `Filter` and `TriggerAction` hierarchies require
        // polymorphic serializers to be registered, otherwise decodeFromString
        // throws SerializationException (we catch it and return empty/null).
        // The discriminator field `type` is added automatically by the default
        // Json configuration because the subclasses are annotated @Serializable.
        private val json = Json {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                polymorphic(Filter::class) {
                    subclass(Filter.PackageFilter::class)
                    subclass(Filter.KeywordFilter::class)
                    subclass(Filter.TimeFilter::class)
                    subclass(Filter.CategoryFilter::class)
                }
                polymorphic(TriggerAction::class) {
                    subclass(TriggerAction.SkillCall::class)
                    subclass(TriggerAction.AgentQuery::class)
                    subclass(TriggerAction.NotificationReply::class)
                    subclass(TriggerAction.CustomScript::class)
                }
            }
        }

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
