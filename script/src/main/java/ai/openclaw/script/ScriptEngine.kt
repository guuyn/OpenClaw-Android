package ai.openclaw.script

import android.content.Context
import android.util.Log
import ai.openclaw.script.bridge.FileBridge
import ai.openclaw.script.bridge.HttpBridge
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/**
 * JS 脚本执行引擎
 *
 * 主引擎: QuickJS (quickjs-kt)
 * Fallback: Rhino（QuickJS 不可用时）
 *
 * 每次 execute 创建独立实例，执行完销毁，保证隔离。
 */
class ScriptEngine(private val context: Context?) {
    private val TAG = "ScriptEngine"
    private var initialized = false
    private var useQuickJS = true

    fun initialize() {
        if (initialized) return
        initialized = true
    }

    /**
     * 执行脚本（suspend — QuickJS 需要协程）
     */
    suspend fun execute(
        script: String,
        bridges: List<CapabilityBridge>,
        policy: SandboxPolicy
    ): ScriptResult {
        val startTime = System.currentTimeMillis()

        val validation = ScriptValidator.validate(script)
        if (!validation.isValid) {
            return ScriptResult.failure(validation.error ?: "Validation failed")
        }

        return try {
            if (useQuickJS) {
                executeWithQuickJS(script, bridges, policy, startTime)
            } else {
                executeWithRhino(script, bridges, policy, startTime)
            }
        } catch (e: Throwable) {
            if (useQuickJS) {
                try { Log.w(TAG, "QuickJS failed, falling back to Rhino: ${e.message}") } catch (_: Exception) {}
                useQuickJS = false
                executeWithRhino(script, bridges, policy, startTime)
            } else {
                ScriptResult.failure(e.message ?: "Unknown error", System.currentTimeMillis() - startTime)
            }
        }
    }

    private suspend fun executeWithQuickJS(
        script: String,
        bridges: List<CapabilityBridge>,
        policy: SandboxPolicy,
        startTime: Long
    ): ScriptResult {
        val result = withTimeoutOrNull(policy.timeoutMs) {
            QuickJs.create(Dispatchers.Default).use { quickJs ->
                // 注入 Bridge — 每个 bridge 注册为一个 JS 全局对象
                for (bridge in bridges) {
                    quickJs.define(bridge.name) {
                        bridge.registerBindings(this)
                    }
                }

                val evalResult = quickJs.evaluate<Any?>(script)
                val output = evalResult?.toString() ?: ""
                val elapsed = System.currentTimeMillis() - startTime
                ScriptResult.success(output, elapsed)
            }
        }
        return result ?: ScriptResult.failure("Timeout (${policy.timeoutMs}ms)", System.currentTimeMillis() - startTime)
    }

    private suspend fun executeWithRhino(
        script: String,
        bridges: List<CapabilityBridge>,
        policy: SandboxPolicy,
        startTime: Long
    ): ScriptResult {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit(java.util.concurrent.Callable<ScriptResult> {
            val cx = org.mozilla.javascript.Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
                val scope = cx.initSafeStandardObjects()

                // Remove dangerous globals
                for (name in listOf("Packages", "java", "javax", "org", "com", "edu", "net")) {
                    try { scope.delete(name) } catch (_: Exception) {}
                }

                // Register __nativeCall
                val nativeCallFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(
                        cx2: org.mozilla.javascript.Context,
                        scope2: org.mozilla.javascript.Scriptable,
                        thisObj: org.mozilla.javascript.Scriptable?,
                        args: Array<Any?>
                    ): Any {
                        if (args.size < 2) return """{"error":"Invalid call"}"""
                        val method = org.mozilla.javascript.ScriptRuntime.toString(args[0])
                        val argJson = org.mozilla.javascript.ScriptRuntime.toString(args[1])
                        return dispatchBridge(bridges, method, argJson)
                    }
                }
                scope.put("__rhinoNativeCall", scope, nativeCallFn)

                // Inject bridge prototypes
                val injectCode = buildString {
                    appendLine("var __nativeCall = function(m, a) { return __rhinoNativeCall(m, a); };")
                    for (bridge in bridges) {
                        appendLine(bridge.getJsPrototype())
                    }
                }
                cx.evaluateString(scope, injectCode, "bridge-inject", 1, null)

                val result = cx.evaluateString(scope, script, "user-script", 1, null)
                val output = org.mozilla.javascript.ScriptRuntime.toString(result)
                ScriptResult.success(output, System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                ScriptResult.failure("Rhino: ${e.message ?: "error"}", System.currentTimeMillis() - startTime)
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        })

        return try {
            future.get(policy.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            ScriptResult.failure("Timeout (${policy.timeoutMs}ms)", System.currentTimeMillis() - startTime)
        } catch (e: java.util.concurrent.ExecutionException) {
            val msg = e.cause?.message ?: e.message ?: "Unknown"
            ScriptResult.failure("Rhino: $msg", System.currentTimeMillis() - startTime)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun dispatchBridge(
        bridges: List<CapabilityBridge>,
        method: String,
        argsJson: String
    ): String {
        val bridgeName = method.substringBefore('.')
        val bridge = bridges.find { it.name == bridgeName }
            ?: return """{"error":"Bridge not found: $bridgeName"}"""

        return when (bridge) {
            is FileBridge -> bridge.handle(method, argsJson)
            is HttpBridge -> bridge.handle(method, argsJson)
            else -> bridge.handleMethod(method, argsJson)
        }
    }

    fun destroy() {
        initialized = false
    }
}
