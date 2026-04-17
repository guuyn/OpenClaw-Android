package ai.openclaw.android.agent

import ai.openclaw.android.config.AgentConfig
import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * AgentRegistry - Manages multi-agent configuration and sessions
 *
 * Directory structure:
 * /sdcard/Android/data/<pkg>/files/agents/
 * ├── main/
 * │   ├── SOUL.md
 * │   └── config.yaml
 * └── <custom>/
 *     ├── SOUL.md
 *     └── config.yaml
 */
class AgentRegistry(
    private val context: Context,
    private val modelClientFactory: (String) -> ai.openclaw.android.model.ModelClient,
    private val skillManager: ai.openclaw.android.skill.SkillManager,
    private val accessibilityBridge: ai.openclaw.android.accessibility.AccessibilityBridge
) {
    companion object {
        private const val TAG = "AgentRegistry"
        private const val AGENTS_DIR = "agents"
        private const val CONFIG_FILE = "config.yaml"
    }

    private val yaml = Yaml()
    private val configs = mutableMapOf<String, AgentConfig>()
    private val sessions = mutableMapOf<String, AgentSession>()
    private val defaultAgentId = "main"

    init {
        loadAllConfigs()
    }

    /**
     * Get or create an AgentSession for the given agent
     */
    fun getSession(agentId: String): AgentSession {
        return sessions.getOrPut(agentId) {
            val config = configs[agentId] ?: run {
                Log.w(TAG, "Agent $agentId not found, falling back to $defaultAgentId")
                configs[defaultAgentId] ?: throw IllegalStateException("No agents configured")
            }
            createSession(config)
        }
    }

    /**
     * List all configured agents
     */
    fun listAgents(): List<AgentConfig> = configs.values.toList()

    /**
     * Get config for a specific agent
     */
    fun getConfig(agentId: String): AgentConfig? = configs[agentId]

    /**
     * Get default agent
     */
    fun getDefaultAgent(): AgentConfig = configs[defaultAgentId]
        ?: throw IllegalStateException("No default agent configured")

    /**
     * Create a new agent directory with default files
     */
    fun createAgent(id: String, name: String, model: String): AgentConfig {
        val agentDir = getAgentDir(id)
        if (agentDir.exists()) {
            throw IllegalArgumentException("Agent '$id' already exists")
        }

        agentDir.mkdirs()

        // Create default config.yaml
        val configYaml = """
id: $id
name: $name
model: $model
maxContextTokens: 4000
tools: []
""".trimIndent()
        File(agentDir, CONFIG_FILE).writeText(configYaml)

        // Create default SOUL.md
        val soulMd = "You are an AI assistant named $name."
        File(agentDir, "SOUL.md").writeText(soulMd)

        val config = AgentConfig(id = id, name = name, model = model)
        configs[id] = config
        Log.i(TAG, "Agent created: $id at ${agentDir.absolutePath}")
        return config
    }

    /**
     * Delete an agent (cannot delete default)
     */
    fun deleteAgent(id: String): Boolean {
        if (id == defaultAgentId) {
            Log.w(TAG, "Cannot delete default agent")
            return false
        }

        val agentDir = getAgentDir(id)
        if (!agentDir.exists()) return false

        val deleted = agentDir.deleteRecursively()
        if (deleted) {
            configs.remove(id)
            sessions.remove(id)
            Log.i(TAG, "Agent deleted: $id")
        }
        return deleted
    }

    /**
     * Reload all configs from disk
     */
    fun reloadAll() {
        configs.clear()
        sessions.clear()
        loadAllConfigs()
    }

    /**
     * Reload a single agent config
     */
    fun reloadAgent(id: String): AgentConfig? {
        val config = loadSingleConfig(id)
        if (config != null) {
            configs[id] = config
            sessions.remove(id) // Force session recreation
        }
        return config
    }

    // ==================== Internal ====================

    private fun loadAllConfigs() {
        val agentsDir = File(context.getExternalFilesDir(null), AGENTS_DIR)
        if (!agentsDir.exists()) {
            Log.i(TAG, "Agents directory not found, creating with default main agent")
            copyDefaultAgentsFromAssets()
        }

        agentsDir.mkdirs()
        agentsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val config = loadSingleConfig(dir.name)
            if (config != null) {
                configs[dir.name] = config
            }
        }

        Log.i(TAG, "Loaded ${configs.size} agent configs: ${configs.keys}")
        configs.values.forEach { config ->
            Log.i(TAG, "Agent config: id=${config.id}, name=${config.name}, maxContextTokens=${config.maxContextTokens}, model=${config.model}")
        }
    }

    private fun loadSingleConfig(agentId: String): AgentConfig? {
        val configFile = File(getAgentDir(agentId), CONFIG_FILE)
        return try {
            if (!configFile.exists()) {
                Log.w(TAG, "No config.yaml for agent: $agentId")
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val map = yaml.load<Map<String, Any>>(configFile.inputStream())

            val parsedConfig = AgentConfig(
                id = map["id"] as? String ?: agentId,
                name = map["name"] as? String ?: agentId,
                model = map["model"] as? String ?: "bailian/qwen3.6-plus",
                maxContextTokens = (map["maxContextTokens"] as? Number)?.toInt() ?: 4000,
                tools = (map["tools"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            )
            Log.d(TAG, "Parsed config for $agentId: name=${parsedConfig.name}, maxContextTokens=${parsedConfig.maxContextTokens}, model=${parsedConfig.model}")
            parsedConfig
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config for agent: $agentId", e)
            null
        }
    }

    private fun createSession(config: AgentConfig): AgentSession {
        val modelClient = modelClientFactory(config.model)
        val systemPrompt = AgentPromptLoader.loadForAgent(context, config.id)

        val session = AgentSession(
            modelClient = modelClient,
            skillManager = skillManager,
            maxContextTokens = config.maxContextTokens
        ).apply {
            setSystemPrompt(systemPrompt)
            setToolsWithSkills(
                accessTools = accessibilityBridge.getTools(),
                executor = { toolCall ->
                    accessibilityBridge.execute(toolCall) ?: "Tool execution failed"
                }
            )
        }

        Log.i(TAG, "Session created for agent: ${config.id}")
        return session
    }

    private fun getAgentDir(agentId: String): File {
        val externalDir = context.getExternalFilesDir(null)
        return File(File(externalDir, AGENTS_DIR), agentId)
    }

    private fun copyDefaultAgentsFromAssets() {
        try {
            val agentsDir = File(context.getExternalFilesDir(null), AGENTS_DIR)
            val assetManager = context.assets
            val agentIds = assetManager.list(AGENTS_DIR) ?: emptyArray()

            for (agentId in agentIds) {
                val targetDir = File(agentsDir, agentId)
                if (targetDir.exists()) continue

                targetDir.mkdirs()
                val files = assetManager.list("$AGENTS_DIR/$agentId") ?: emptyArray()
                for (file in files) {
                    assetManager.open("$AGENTS_DIR/$agentId/$file").use { input ->
                        File(targetDir, file).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Log.i(TAG, "Default agent copied from assets: $agentId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default agents from assets", e)
        }
    }
}
