package ai.openclaw.script

import android.content.Context
import android.util.Log
import ai.openclaw.script.bridge.UiBridge
import ai.openclaw.script.bridge.UiProvider
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

    /** UI Provider，由主工程注入 */
    private var uiProvider: UiProvider? = null

    /**
     * 注入 UiProvider 实现（主工程在初始化时调用）
     */
    fun setUiProvider(provider: UiProvider) {
        uiProvider = provider
    }

    /**
     * 执行脚本（suspend — QuickJS 需要协程）
     *
     * @param script JS 脚本代码
     * @param capabilities 需要的能力列表（"fs", "http", "memory", "ui"）
     * @param customBridges 自定义 Bridge（如 MemoryBridge 实现）
     * @param variables 全局变量注入（如 QUERY="天气", LOCATION="西安"）
     */
    suspend fun execute(
        script: String,
        capabilities: List<String> = emptyList(),
        customBridges: List<CapabilityBridge> = emptyList(),
        variables: Map<String, Any> = emptyMap()
    ): ScriptResult {
        val allBridges = resolveBridges(capabilities) + customBridges
        val policy = SandboxPolicy(sandboxDir = sandboxDir)
        return engine.execute(script, allBridges, policy, variables)
    }

    private fun resolveBridges(capabilities: List<String>): List<CapabilityBridge> {
        val bridges = mutableListOf<CapabilityBridge>()
        val caps = capabilities.ifEmpty { listOf("fs", "http") }

        for (cap in caps) {
            when (cap) {
                "fs", "file" -> bridges.add(ai.openclaw.script.bridge.FileBridge(context, sandboxDir))
                "http", "network" -> bridges.add(ai.openclaw.script.bridge.HttpBridge())
                "ui" -> {
                    val provider = uiProvider
                        ?: throw IllegalStateException(
                            "UiProvider not set. Call orchestrator.setUiProvider() before using 'ui' capability."
                        )
                    bridges.add(UiBridge(provider))
                }
                else -> Log.w(TAG, "Unknown capability: $cap (use customBridges parameter)")
            }
        }
        return bridges
    }
}
