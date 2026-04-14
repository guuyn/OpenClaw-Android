package ai.openclaw.script

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 脚本编排器 — 执行入口
 *
 * 调用方只需创建 Orchestrator，调用 execute() 即可。
 * Orchestrator 负责校验 → 注册 Bridge → 执行。
 */
class ScriptOrchestrator(private val context: Context) {
    private val TAG = "ScriptOrchestrator"

    private val sandboxDir: File by lazy {
        File(context.filesDir, "script_sandbox").also { it.mkdirs() }
    }

    private val engine: ScriptEngine by lazy {
        ScriptEngine(context).also { it.initialize() }
    }

    /**
     * 执行脚本（suspend — QuickJS 需要协程）
     *
     * @param script JS 脚本代码
     * @param capabilities 需要的能力列表（"fs", "http", "memory"）
     * @param customBridges 自定义 Bridge（如 MemoryBridge 实现）
     */
    suspend fun execute(
        script: String,
        capabilities: List<String> = emptyList(),
        customBridges: List<CapabilityBridge> = emptyList()
    ): ScriptResult {
        val allBridges = resolveBridges(capabilities) + customBridges
        val policy = SandboxPolicy(sandboxDir = sandboxDir)
        return engine.execute(script, allBridges, policy)
    }

    private fun resolveBridges(capabilities: List<String>): List<CapabilityBridge> {
        val bridges = mutableListOf<CapabilityBridge>()
        val caps = capabilities.ifEmpty { listOf("fs", "http") }

        for (cap in caps) {
            when (cap) {
                "fs", "file" -> bridges.add(ai.openclaw.script.bridge.FileBridge(context, sandboxDir))
                "http", "network" -> bridges.add(ai.openclaw.script.bridge.HttpBridge())
                else -> Log.w(TAG, "Unknown capability: $cap (use customBridges parameter)")
            }
        }
        return bridges
    }
}
