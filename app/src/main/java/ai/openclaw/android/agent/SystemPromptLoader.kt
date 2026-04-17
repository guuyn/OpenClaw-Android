package ai.openclaw.android.agent

import android.content.Context

/**
 * SystemPromptLoader - 全局 system prompt 加载器（已废弃）
 *
 * @deprecated Use [AgentPromptLoader] instead.
 * This object is kept for backward compatibility and delegates to AgentPromptLoader.
 */
@Deprecated(
    message = "Use AgentPromptLoader instead",
    replaceWith = ReplaceWith(
        expression = "AgentPromptLoader",
        imports = ["ai.openclaw.android.agent.AgentPromptLoader"]
    )
)
object SystemPromptLoader {
    /**
     * 加载全局 system prompt
     * @deprecated Use AgentPromptLoader.load(context)
     */
    @Deprecated("Use AgentPromptLoader.load(context)", ReplaceWith("AgentPromptLoader.load(context)", "ai.openclaw.android.agent.AgentPromptLoader"))
    @JvmStatic
    fun load(context: Context): String = AgentPromptLoader.load(context)

    /**
     * 强制重新加载全局 prompt
     * @deprecated Use AgentPromptLoader.reload(context)
     */
    @Deprecated("Use AgentPromptLoader.reload(context)", ReplaceWith("AgentPromptLoader.reload(context)", "ai.openclaw.android.agent.AgentPromptLoader"))
    @JvmStatic
    fun reload(context: Context): String = AgentPromptLoader.reload(context)

    /**
     * 获取全局 prompt 文件绝对路径
     * @deprecated Use AgentPromptLoader.getFilePath(context)
     */
    @Deprecated("Use AgentPromptLoader.getFilePath(context)", ReplaceWith("AgentPromptLoader.getFilePath(context)", "ai.openclaw.android.agent.AgentPromptLoader"))
    @JvmStatic
    fun getFilePath(context: Context): String = AgentPromptLoader.getFilePath(context)
}
