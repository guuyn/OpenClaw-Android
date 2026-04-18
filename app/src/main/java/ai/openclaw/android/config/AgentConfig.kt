package ai.openclaw.android.config

data class AgentConfig(
    val id: String,
    val name: String,
    val model: String = "openai/qwen3.6-plus",
    val systemPrompt: String = "",
    val maxContextTokens: Int = 4000,
    val tools: List<String> = emptyList(),
    val routing: RoutingConfig? = null
)

data class RoutingConfig(
    val keywords: List<String> = emptyList(),
    val targetAgent: String = ""
)
