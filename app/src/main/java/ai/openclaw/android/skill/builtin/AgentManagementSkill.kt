package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.android.agent.AgentRegistry

class AgentManagementSkill(
    private val agentRegistry: AgentRegistry
) : Skill {
    override val id = "agent_management"
    override val name = "Agent 管理"
    override val description = "管理多 Agent 配置：列表、创建、删除、查看配置"
    override val version = "1.0.0"
    override val instructions = """
# Agent 管理

使用以下工具管理 Agent：
- list_agents: 列出所有已配置的 Agent
- create_agent: 创建新 Agent（需要提供 id, name, model）
- delete_agent: 删除 Agent（不能删除 main）
- get_agent_config: 查看指定 Agent 的配置

创建 Agent 后，会自动在 /sdcard/Android/data/ai.openclaw.android/files/agents/<id>/ 目录下生成 config.yaml 和 SOUL.md。
可以直接编辑这些文件来自定义 Agent 行为。
"""
    override val tools: List<SkillTool> = listOf(
        ListAgentsTool(agentRegistry),
        CreateAgentTool(agentRegistry),
        DeleteAgentTool(agentRegistry),
        GetAgentConfigTool(agentRegistry)
    )

    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}

// ===== List Agents Tool =====

class ListAgentsTool(private val registry: AgentRegistry) : SkillTool {
    override val name = "list_agents"
    override val description = "列出所有已配置的 Agent"
    override val parameters = emptyMap<String, SkillParam>()
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val agents = registry.listAgents()
        if (agents.isEmpty()) {
            return SkillResult(true, "没有配置的 Agent", "")
        }
        val output = agents.joinToString("\n") { config ->
            "• ${config.id} (${config.name}) - model: ${config.model}, maxTokens: ${config.maxContextTokens}"
        }
        return SkillResult(true, output, "")
    }
}

// ===== Create Agent Tool =====

class CreateAgentTool(private val registry: AgentRegistry) : SkillTool {
    override val name = "create_agent"
    override val description = "创建新 Agent"
    override val parameters = mapOf(
        "id" to SkillParam("string", "Agent 唯一标识（小写字母+下划线）", true),
        "name" to SkillParam("string", "Agent 显示名称", true),
        "model" to SkillParam("string", "模型名，如 openai/qwen3.6-plus", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val name = params["name"] as? String ?: return SkillResult(false, "", "缺少参数: name")
        val model = params["model"] as? String ?: return SkillResult(false, "", "缺少参数: model")

        return try {
            val config = registry.createAgent(id, name, model)
            SkillResult(
                true,
                "Agent 创建成功: ${config.id} (${config.name})\n" +
                "配置文件: /sdcard/Android/data/ai.openclaw.android/files/agents/$id/config.yaml\n" +
                "提示词: /sdcard/Android/data/ai.openclaw.android/files/agents/$id/SOUL.md",
                ""
            )
        } catch (e: Exception) {
            SkillResult(false, "", "创建失败: ${e.message}")
        }
    }
}

// ===== Delete Agent Tool =====

class DeleteAgentTool(private val registry: AgentRegistry) : SkillTool {
    override val name = "delete_agent"
    override val description = "删除 Agent（不能删除 main）"
    override val parameters = mapOf(
        "id" to SkillParam("string", "要删除的 Agent ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val deleted = registry.deleteAgent(id)
        return if (deleted) {
            SkillResult(true, "Agent 已删除: $id", "")
        } else {
            SkillResult(false, "", "删除失败: 可能是默认 agent 或不存在")
        }
    }
}

// ===== Get Agent Config Tool =====

class GetAgentConfigTool(private val registry: AgentRegistry) : SkillTool {
    override val name = "get_agent_config"
    override val description = "查看指定 Agent 的配置"
    override val parameters = mapOf(
        "id" to SkillParam("string", "Agent ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val config = registry.getConfig(id)
        return if (config != null) {
            val output = """
ID: ${config.id}
名称: ${config.name}
模型: ${config.model}
最大上下文 Token: ${config.maxContextTokens}
启用的工具: ${config.tools.joinToString(", ").ifEmpty { "全部" }}
""".trimIndent()
            SkillResult(true, output, "")
        } else {
            SkillResult(false, "", "Agent 不存在: $id")
        }
    }
}
