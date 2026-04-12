package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.bridge.MemoryBridge
import ai.openclaw.script.bridge.MemoryProvider

/**
 * ScriptEngine 技能 — 将 :script 模块接入 Skill 体系
 *
 * 提供一个 execute_script 工具，LLM 生成 JS 脚本后调用此工具执行。
 */
class ScriptSkill : Skill {

    private var orchestrator: ScriptOrchestrator? = null
    private var memoryManager: Any? = null // MemoryManager, set via reflection or DI

    override val id = "script"
    override val name = "Script Engine"
    override val description = "动态执行 JS 脚本，扩展 Agent 能力"
    override val version = "0.1.0"
    override val instructions = """
# Script Engine

当现有 Tool 无法满足复杂的组合任务时，你可以生成 JavaScript 脚本并通过 `execute_script` 执行。

## 可用 API
- `fs.readFile(path)` / `fs.writeFile(path, content)` / `fs.list(dir)` / `fs.exists(path)`
- `http.get(url)` / `http.post(url, body)`
- `memory.recall(query, limit)` / `memory.store(content)`

## 限制
- 禁止 import/require/eval
- 禁止访问 java/android/process/global
- 脚本最大 50KB，执行超时 10s
- 文件操作限制在沙箱目录内
    """.trimIndent()

    override val tools = listOf(ExecuteScriptTool())

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
    }

    override fun cleanup() {
        orchestrator = null
    }

    /**
     * 注入 MemoryManager（通过 DI 或反射调用）
     */
    fun setMemoryManager(manager: Any) {
        memoryManager = manager
    }

    inner class ExecuteScriptTool : SkillTool {
        override val name = "execute_script"
        override val description = "执行一段 JavaScript 脚本并返回结果"
        override val parameters = mapOf(
            "script" to SkillParam("string", "要执行的 JavaScript 代码", true),
            "capabilities" to SkillParam("string", "需要的能力列表，逗号分隔（fs,http,memory）", false, "fs,http")
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val orch = orchestrator
                ?: return SkillResult(false, "", "ScriptEngine not initialized")

            val script = params["script"] as? String
                ?: return SkillResult(false, "", "Missing 'script' parameter")

            val capsStr = params["capabilities"] as? String ?: "fs,http"
            val capabilities = capsStr.split(",").map { it.trim() }

            // 构建 MemoryBridge（如果有 MemoryManager）
            val customBridges = buildMemoryBridge()

            val result = orch.execute(script, capabilities, customBridges)
            return SkillResult(result.success, result.output, result.error)
        }

        private fun buildMemoryBridge(): List<ai.openclaw.script.CapabilityBridge> {
            val mm = memoryManager ?: return emptyList()
            // 简单实现：用反射或接口适配
            return listOf(
                MemoryBridge(MemoryProvider { method, args ->
                    // TODO: 对接 MemoryManager 的 recall/store 方法
                    """{"error":"Memory not yet integrated"}"""
                })
            )
        }
    }
}
