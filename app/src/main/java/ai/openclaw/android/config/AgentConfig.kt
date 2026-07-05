package ai.openclaw.android.config

import ai.openclaw.android.domain.ReflectionStrategy

data class AgentConfig(
    val id: String,
    val name: String,
    val model: String = "",
    val systemPrompt: String = "",
    val maxContextTokens: Int = 4000,
    val tools: List<String> = emptyList(),
    val routing: RoutingConfig? = null,
    /** 自我反思策略（默认自动选择） */
    val reflectionStrategy: ReflectionStrategy = ReflectionStrategy.NONE
)

data class RoutingConfig(
    val keywords: List<String> = emptyList(),
    val targetAgent: String = ""
)
