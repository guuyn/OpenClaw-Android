package ai.openclaw.script

import android.content.Context
import ai.openclaw.script.bridge.FileBridge
import ai.openclaw.script.bridge.HttpBridge
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JS 脚本执行引擎
 *
 * 原型阶段：使用 Rhino 执行 JS
 * 正式版：替换为 QuickJS JNI
 *
 * 每次 execute 创建独立 Context，执行完销毁，保证隔离。
 */
class ScriptEngine(private val context: Context?) {
    private val TAG = "ScriptEngine"
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
    }

    fun execute(
        script: String,
        bridges: List<CapabilityBridge>,
        policy: SandboxPolicy
    ): ScriptResult {
        val startTime = System.currentTimeMillis()

        val validation = ScriptValidator.validate(script)
        if (!validation.isValid) {
            return ScriptResult.failure(validation.error ?: "Validation failed")
        }

        // 带超时执行（Rhino Context 必须在执行线程中创建）
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable<ScriptResult> {
            val cx = org.mozilla.javascript.Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6

                val scope = cx.initSafeStandardObjects()
                removeDangerousGlobals(scope)

                // 注册 __nativeCall → 分发到 Bridge
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

                // 注入 Bridge 原型代码
                val injectCode = buildString {
                    appendLine("var __nativeCall = function(m, a) { return __rhinoNativeCall(m, a); };")
                    for (bridge in bridges) {
                        appendLine(bridge.getJsPrototype())
                    }
                }
                cx.evaluateString(scope, injectCode, "bridge-inject", 1, null)

                // 执行用户脚本
                val result = cx.evaluateString(scope, script, "user-script", 1, null)
                val output = org.mozilla.javascript.ScriptRuntime.toString(result)
                val elapsed = System.currentTimeMillis() - startTime
                ScriptResult.success(output, elapsed)
            } catch (e: Exception) {
                ScriptResult.failure(e.message ?: "Unknown error", System.currentTimeMillis() - startTime)
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        })

        return try {
            future.get(policy.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            ScriptResult.failure("Timeout (${policy.timeoutMs}ms)", System.currentTimeMillis() - startTime)
        } catch (e: java.util.concurrent.ExecutionException) {
            val msg = e.cause?.message ?: e.message ?: "Unknown"
            ScriptResult.failure(msg, System.currentTimeMillis() - startTime)
        } finally {
            executor.shutdownNow()
        }
    }

    fun destroy() {
        initialized = false
    }

    private fun removeDangerousGlobals(scope: org.mozilla.javascript.Scriptable) {
        for (name in listOf("Packages", "java", "javax", "org", "com", "edu", "net")) {
            try { scope.delete(name) } catch (_: Exception) {}
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
}
