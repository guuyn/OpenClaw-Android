package ai.openclaw.android.domain.agent

import ai.openclaw.android.ConfigManager
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.data.model.AgentConfig as DataAgentConfig
import ai.openclaw.android.config.AgentConfig
import ai.openclaw.android.model.BailianClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import android.content.Context
import android.util.Log

/**
 * Manages multiple AgentSession instances with lazy creation, caching, and LRU eviction.
 *
 * Each agent ID maps to a lazily-created AgentSession configured with its own
 * model provider, system prompt, and tool filtering rules.
 *
 * @param context Android application context
 * @param configManager Source of agent configurations
 * @param skillManager Shared skill registry
 * @param accessibilityBridge Optional accessibility tools bridge
 * @param permissionManager Optional permission gate for skill execution
 * @param maxCachedSessions Maximum sessions to keep in cache before LRU eviction (default: 3)
 */
open class AgentSessionManager(
    private val context: Context,
    private val configManager: AgentConfigManager,
    private val skillManager: SkillManager,
    private val accessibilityBridge: AccessibilityBridge?,
    private val permissionManager: PermissionManager? = null,
    private val maxCachedSessions: Int = 3
) {

    private fun toConfigAgent(dataConfig: DataAgentConfig): AgentConfig {
        return AgentConfig(
            id = dataConfig.id,
            name = dataConfig.name,
            model = dataConfig.model,
            systemPrompt = dataConfig.systemPrompt ?: "",
            maxContextTokens = 4000,
            tools = dataConfig.tools
        )
    }

    companion object {
        private const val TAG = "AgentSessionManager"
    }

    private val sessionCache = mutableMapOf<String, AgentSession>()
    private val accessOrder = mutableListOf<String>() // LRU tracking

    /**
     * Get or create an AgentSession for the given agent ID.
     *
     * If a session already exists in cache it is returned and marked as recently
     * accessed.  Otherwise a new session is built from the agent's config and
     * cached (evicting the least-recently-used session if the cache is full).
     */
    fun getOrCreate(agentId: String): AgentSession {
        // Return cached if exists
        sessionCache[agentId]?.let { session ->
            touch(agentId)
            Log.d(TAG, "Reusing cached session for '$agentId'")
            return session
        }

        // Create new session
        val config = configManager.getAgentById(agentId) ?: configManager.getDefaultAgent()
        val modelClient = createModelClient(config)

        val session = AgentSession(
            modelClient = modelClient,
            skillManager = skillManager,
            agentConfig = toConfigAgent(config),
            permissionManager = permissionManager
        )

        // Initialize tools
        session.setToolsWithSkills(
            accessTools = accessibilityBridge?.getTools() ?: emptyList(),
            executor = { toolCall ->
                accessibilityBridge?.execute(toolCall) ?: "Accessibility not available"
            }
        )

        // Cache with eviction (skip if maxCachedSessions is 0)
        if (maxCachedSessions > 0) {
            evictIfNecessary()
            sessionCache[agentId] = session
            accessOrder.add(agentId)
        }

        Log.i(TAG, "Created new session for '$agentId' (model: ${config.model})")
        return session
    }

    /**
     * Remove a session from cache.
     */
    fun evict(agentId: String) {
        sessionCache.remove(agentId)
        accessOrder.remove(agentId)
        Log.d(TAG, "Evicted session for '$agentId'")
    }

    /**
     * Get list of active (cached) agent IDs in access order (oldest first).
     */
    fun getActiveAgentIds(): List<String> = accessOrder.toList()

    /**
     * Clean up all sessions.
     */
    fun cleanup() {
        sessionCache.clear()
        accessOrder.clear()
        Log.i(TAG, "All sessions cleaned up")
    }

    /**
     * Build a ModelClient configured for the given agent.
     * Marked `protected open` so tests can override with a mock.
     */
    protected open fun createModelClient(config: DataAgentConfig): ModelClient {
        val client = BailianClient()

        // Parse model string: "bailian/qwen3.5-plus" → provider=bailian, name=qwen3.5-plus
        val parts = config.model.split("/", limit = 2)
        val provider = if (parts.size > 1) parts[0] else "bailian"
        val modelName = if (parts.size > 1) parts[1] else config.model

        // Use API key from ConfigManager (same as main app)
        // In production, each agent could have its own API key
        val apiKey = ConfigManager.getModelApiKey()

        client.configure(
            provider = ModelProvider.valueOf(provider.uppercase()),
            apiKey = apiKey,
            model = modelName
        )
        return client
    }

    /**
     * Mark an agent ID as recently accessed (move to end of access order).
     */
    private fun touch(agentId: String) {
        accessOrder.remove(agentId)
        accessOrder.add(agentId)
    }

    /**
     * Evict least-recently-used sessions until cache size is under the limit.
     */
    private fun evictIfNecessary() {
        while (sessionCache.size >= maxCachedSessions && accessOrder.isNotEmpty()) {
            val oldest = accessOrder.removeAt(0)
            sessionCache.remove(oldest)
            Log.d(TAG, "LRU evicted: '$oldest'")
        }
    }
}
