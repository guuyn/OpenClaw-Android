package ai.openclaw.android.domain.agent

import ai.openclaw.android.data.model.AgentConfig
import ai.openclaw.android.data.model.AgentConfigSerializer
import ai.openclaw.android.data.model.AgentRegistry
import android.content.Context
import android.util.Log

/**
 * Loads and manages agent configurations from assets.
 */
class AgentConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentConfigManager"
        private const val AGENTS_FILE = "agents.json"
    }

    private var registry: AgentRegistry? = null
    private val keywordIndex: MutableMap<String, String> = mutableMapOf() // keyword → agentId

    /**
     * Load agents.json from assets. Call once during initialization.
     */
    fun loadFromAssets(): List<AgentConfig> {
        return try {
            val jsonString = context.assets.open(AGENTS_FILE).bufferedReader().use { it.readText() }
            registry = AgentConfigSerializer.deserialize(jsonString)
            buildKeywordIndex()
            Log.i(TAG, "Loaded ${registry!!.agents.size} agents from $AGENTS_FILE")
            registry!!.agents
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $AGENTS_FILE: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get an agent config by ID.
     */
    fun getAgentById(id: String): AgentConfig? {
        return registry?.getAgentById(id)
    }

    /**
     * Get the default agent config.
     */
    fun getDefaultAgent(): AgentConfig {
        return registry?.getDefaultAgent() ?: throw IllegalStateException("No agents configured")
    }

    /**
     * Get all configured agents.
     */
    fun getAllAgents(): List<AgentConfig> {
        return registry?.agents ?: emptyList()
    }

    /**
     * Get keyword index for routing (keyword → agentId mapping).
     */
    fun getKeywordIndex(): Map<String, String> {
        return keywordIndex.toMap()
    }

    /**
     * Check if an agent with the given ID exists.
     */
    fun hasAgent(id: String): Boolean {
        return getAgentById(id) != null
    }

    private fun buildKeywordIndex() {
        keywordIndex.clear()
        registry?.agents?.forEach { agent ->
            agent.keywords.forEach { keyword ->
                val lowerKeyword = keyword.lowercase()
                if (!keywordIndex.containsKey(lowerKeyword)) {
                    keywordIndex[lowerKeyword] = agent.id
                } else {
                    Log.w(TAG, "Duplicate keyword '$keyword' — already mapped to ${keywordIndex[lowerKeyword]}")
                }
            }
        }
        Log.d(TAG, "Built keyword index with ${keywordIndex.size} entries")
    }
}
