package ai.openclaw.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for a single AI Agent
 */
@Serializable
data class AgentConfig(
    /** Unique identifier for this agent */
    val id: String,
    /** Display name */
    val name: String,
    /** Model identifier (e.g. "openai/qwen3.5-plus") */
    val model: String = "openai/qwen3.5-plus",
    /** Custom system prompt (prepended to BASE_SYSTEM_PROMPT) */
    val systemPrompt: String? = null,
    /** Tool filter — list of tool name prefixes or "all" for no filter */
    val tools: List<String> = listOf("all"),
    /** Keywords that trigger routing to this agent */
    val keywords: List<String> = emptyList(),
    /** Whether this is the default agent for unmatched messages */
    val isDefault: Boolean = false
) {
    /** Check if a message contains any of this agent's keywords */
    fun matches(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return keywords.any { keyword -> lowerMessage.contains(keyword.lowercase()) }
    }
}

/**
 * Registry containing all agent configurations
 */
@Serializable
data class AgentRegistry(
    val agents: List<AgentConfig>
) {
    fun getAgentById(id: String): AgentConfig? = agents.find { it.id == id }
    fun getDefaultAgent(): AgentConfig = agents.find { it.isDefault } ?: agents.first()
}

object AgentConfigSerializer {
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true 
    }
    
    fun serialize(registry: AgentRegistry): String = json.encodeToString(registry)
    fun deserialize(jsonString: String): AgentRegistry = json.decodeFromString(jsonString)
}
