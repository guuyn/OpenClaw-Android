package ai.openclaw.android.agent

import android.content.Context
import android.util.Log
import java.io.File

/**
 * AgentPromptLoader - 从外部文件加载 system prompt / agent soul prompt
 *
 * Global prompt path: /sdcard/Android/data/ai.openclaw.android/files/system_prompt.md
 * Agent prompt path: /sdcard/Android/data/ai.openclaw.android/files/agents/<agentId>/SOUL.md
 *
 * 优势:
 * - 无需重新编译即可调整 prompt
 * - 可通过文件管理器或 adb 直接编辑
 * - 首次启动时自动从 assets 复制默认模板
 * - 支持多 agent 独立 soul prompt
 */
object AgentPromptLoader {
    private const val TAG = "AgentPromptLoader"
    private const val FILE_NAME = "system_prompt.md"
    private const val AGENTS_DIR = "agents"
    private const val SOUL_FILE = "SOUL.md"

    // Global prompt cache
    private var cachedPrompt: String? = null
    private var lastModified: Long = 0L

    // Per-agent prompt cache: agentId -> (content, lastModified)
    private val agentCache = mutableMapOf<String, Pair<String, Long>>()

    // ========================
    // Global prompt (legacy API)
    // ========================

    /**
     * 加载全局 system prompt
     * 优先读取外部文件，不存在则从 assets 复制默认
     */
    fun load(context: Context): String {
        val now = System.currentTimeMillis()

        // 如果文件未修改且已缓存，直接返回
        val cached = cachedPrompt
        if (cached != null) {
            val file = getExternalFile(context)
            if (file.exists() && file.lastModified() <= lastModified) {
                return cached
            }
        }

        val prompt = try {
            val file = getExternalFile(context)
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    Log.d(TAG, "Loaded from external file: ${file.absolutePath} (${content.length} chars)")
                    content
                } else {
                    Log.w(TAG, "External file is empty, falling back to default")
                    loadFromAssets(context)
                }
            } else {
                // 首次启动，从 assets 复制默认模板
                Log.i(TAG, "External file not found, copying default from assets")
                copyDefaultFromAssets(context)
                loadFromAssets(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load system prompt, using fallback", e)
            FALLBACK_PROMPT
        }

        cachedPrompt = prompt
        lastModified = now
        return prompt
    }

    /**
     * 强制重新加载全局 prompt（跳过缓存）
     */
    fun reload(context: Context): String {
        cachedPrompt = null
        lastModified = 0L
        return load(context)
    }

    /**
     * 获取全局外部文件的绝对路径
     */
    fun getFilePath(context: Context): String {
        return getExternalFile(context).absolutePath
    }

    // ========================
    // Per-agent prompt (new API)
    // ========================

    /**
     * 加载指定 agent 的 soul prompt
     * Path: /sdcard/Android/data/<pkg>/files/agents/<agentId>/SOUL.md
     *
     * - 优先读取外部文件
     * - 不存在则从 assets/agents/<agentId>/SOUL.md 复制默认
     * - assets 也不存在则回退到全局 prompt
     */
    fun loadForAgent(context: Context, agentId: String): String {
        val cached = agentCache[agentId]
        val file = getAgentSoulFile(context, agentId)

        // Cache hit if file unchanged
        if (cached != null && file.exists() && file.lastModified() <= cached.second) {
            return cached.first
        }

        val prompt = try {
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    Log.d(TAG, "Loaded SOUL.md for agent $agentId: ${file.absolutePath} (${content.length} chars)")
                    content
                } else {
                    Log.w(TAG, "SOUL.md for agent $agentId is empty, falling back to assets")
                    loadFromAgentAssets(context, agentId)
                }
            } else {
                Log.i(TAG, "SOUL.md not found for agent $agentId, copying default from assets")
                copyAgentDefaultFromAssets(context, agentId)
                loadFromAgentAssets(context, agentId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt for agent $agentId, using fallback", e)
            FALLBACK_PROMPT
        }

        agentCache[agentId] = prompt to System.currentTimeMillis()
        return prompt
    }

    /**
     * 强制重新加载指定 agent 的 prompt（跳过缓存）
     */
    fun reloadAgent(context: Context, agentId: String): String {
        agentCache.remove(agentId)
        return loadForAgent(context, agentId)
    }

    /**
     * 获取指定 agent 的 soul prompt 文件绝对路径
     */
    fun getAgentPromptPath(context: Context, agentId: String): String {
        return getAgentSoulFile(context, agentId).absolutePath
    }

    // ========================
    // Private helpers
    // ========================

    private fun getExternalFile(context: Context): File {
        val externalDir = context.getExternalFilesDir(null)
        return File(externalDir, FILE_NAME)
    }

    private fun getAgentSoulFile(context: Context, agentId: String): File {
        val externalDir = context.getExternalFilesDir(null)
        val agentDir = File(File(externalDir, AGENTS_DIR), agentId)
        return File(agentDir, SOUL_FILE)
    }

    private fun loadFromAssets(context: Context): String {
        return try {
            val content = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            Log.d(TAG, "Loaded from assets (${content.length} chars)")
            content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from assets", e)
            FALLBACK_PROMPT
        }
    }

    private fun loadFromAgentAssets(context: Context, agentId: String): String {
        return try {
            val path = "$AGENTS_DIR/$agentId/$SOUL_FILE"
            val content = context.assets.open(path).bufferedReader().use { it.readText() }
            Log.d(TAG, "Loaded SOUL.md from assets for agent $agentId (${content.length} chars)")
            content
        } catch (e: Exception) {
            Log.w(TAG, "No assets for agent $agentId, using global fallback")
            FALLBACK_PROMPT
        }
    }

    private fun copyDefaultFromAssets(context: Context) {
        try {
            val file = getExternalFile(context)
            file.parentFile?.mkdirs()
            context.assets.open(FILE_NAME).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Default prompt copied to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default prompt", e)
        }
    }

    private fun copyAgentDefaultFromAssets(context: Context, agentId: String) {
        try {
            val file = getAgentSoulFile(context, agentId)
            file.parentFile?.mkdirs()
            val path = "$AGENTS_DIR/$agentId/$SOUL_FILE"
            context.assets.open(path).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Default SOUL.md copied for agent: $agentId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default for agent $agentId", e)
        }
    }

    /**
     * 兜底 prompt（assets 也不存在时用）
     */
    private const val FALLBACK_PROMPT = """You are an AI assistant on an Android device with tool access.

## Rules
1. Call tools to get REAL data — never invent facts.
2. After tool returns, format results using A2UI for rich display.
3. Respond in the same language as the user.
4. Simple greetings need no tools or A2UI.

## A2UI Format
After receiving tool results, wrap the response:
[A2UI]
{"type": "<result_type>", "data": {"key": "value", ...}}
[/A2UI]
Supported types: weather, location, reminder, translation, search, generic.

## Dynamic Skills
You can create new skills dynamically using the `generate_skill` tool.
The skill definition must include: id, name, description, version, instructions, script, tools[]
"""
}
