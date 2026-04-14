# ScriptEngine QuickJS 集成实施计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 在 `:script` 模块中集成 `quickjs-kt v1.0.4`，替代 Rhino，保持 Bridge 接口兼容

**Architecture:** 双引擎模式 — QuickJS 优先，Rhino fallback（过渡期保证稳定性）

**Tech Stack:** `io.github.dokar3:quickjs-kt-android:1.0.4`, Kotlin Coroutines, Rhino (fallback)

---

## Task 1: 添加 quickjs-kt 依赖

**Files:**
- Modify: `script/build.gradle.kts`

在 dependencies 中添加：
```kotlin
// QuickJS 引擎（正式版）
implementation("io.github.dokar3:quickjs-kt-android:1.0.4")
// Rhino 保留作为 fallback
implementation("org.mozilla:rhino:1.7.15")
```

确认编译：`./gradlew :script:compileDebugKotlin`

---

## Task 2: 扩展 CapabilityBridge 接口

**Files:**
- Modify: `script/src/main/java/ai/openclaw/script/CapabilityBridge.kt`

增加 QuickJS 绑定方法：
```kotlin
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.ObjectDSL

/**
 * 为 QuickJS 注册 Bridge 绑定
 * 在 quickJs.define(bridgeName) { ... } 的 DSL 块中调用
 */
fun ObjectDSL.registerQuickJsBindings(bridges: List<CapabilityBridge>) {
    for (bridge in bridges) {
        define(bridge.name) {
            bridge.registerBindings(this)
        }
    }
}

/**
 * 子类覆盖此方法注册具体的 JS 函数
 */
fun CapabilityBridge.registerBindings(dsl: ObjectDSL) {
    // 默认空实现，子类覆盖
}
```

---

## Task 3: 实现 QuickJS 版 ScriptEngine

**Files:**
- Modify: `script/src/main/java/ai/openclaw/script/ScriptEngine.kt`

完全重写 ScriptEngine，使用 QuickJS 作为主引擎，Rhino 作为 fallback：

```kotlin
package ai.openclaw.script

import android.content.Context
import android.util.Log
import ai.openclaw.script.bridge.FileBridge
import ai.openclaw.script.bridge.HttpBridge
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.withTimeoutOrNull

/**
 * JS 脚本执行引擎
 *
 * 主引擎: QuickJS (quickjs-kt)
 * Fallback: Rhino（QuickJS 不可用时）
 */
class ScriptEngine(private val context: Context?) {
    private val TAG = "ScriptEngine"
    private var useQuickJS = true

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
        } catch (e: Exception) {
            if (useQuickJS) {
                Log.w(TAG, "QuickJS failed, falling back to Rhino: ${e.message}")
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
        return withTimeoutOrNull(policy.timeoutMs) {
            try {
                QuickJs.create().use { quickJs ->
                    // 注入 Bridge
                    quickJs.define("bridge") {
                        for (bridge in bridges) {
                            define(bridge.name) {
                                bridge.registerBindings(this)
                            }
                        }
                    }
                    
                    val result = quickJs.evaluate<Any?>(script)
                    val output = result?.toString() ?: ""
                    val elapsed = System.currentTimeMillis() - startTime
                    ScriptResult.success(output, elapsed)
                }
            } catch (e: Exception) {
                ScriptResult.failure("QuickJS: ${e.message ?: "error"}", System.currentTimeMillis() - startTime)
            }
        } ?: ScriptResult.failure("Timeout (${policy.timeoutMs}ms)", System.currentTimeMillis() - startTime)
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
}
```

---

## Task 4: FileBridge + HttpBridge QuickJS 适配

**Files:**
- Modify: `script/src/main/java/ai/openclaw/script/bridge/FileBridge.kt`
- Modify: `script/src/main/java/ai/openclaw/script/bridge/HttpBridge.kt`

在 FileBridge 中添加：
```kotlin
import com.dokar.quickjs.binding.ObjectDSL
import com.dokar.quickjs.binding.function

override fun registerBindings(dsl: ObjectDSL) {
    dsl.apply {
        function("readFile") { args ->
            val path = args.getOrNull(0) as? String ?: return@function "null"
            readFile(path)
        }
        function("writeFile") { args ->
            val path = args.getOrNull(0) as? String ?: return@function "null"
            val content = args.getOrNull(1) as? String ?: return@function "null"
            writeFile(path, content)
        }
        function("list") { args ->
            val dir = args.getOrNull(0) as? String ?: return@function "[]"
            list(dir)
        }
        function("exists") { args ->
            val path = args.getOrNull(0) as? String ?: return@function "false"
            exists(path)
        }
    }
}
```

HttpBridge 类似，但用 `asyncFunction`（因为 HTTP 调用是 suspend 的）。

注意：quickjs-kt 的 `function` 是同步的，如果 HttpBridge 需要异步调用，需要用 `asyncFunction` 或者在 function 内部用 `runBlocking`。

---

## Task 5: 测试

**Files:**
- Modify: `script/src/test/java/ai/openclaw/script/ScriptEngineTest.kt`

更新现有测试以支持 QuickJS + Rhino 双引擎：
```kotlin
@Test fun `QuickJS executes simple expression`() { ... }
@Test fun `QuickJS bridge call works`() { ... }
@Test fun `Rhino fallback works when QuickJS fails`() { ... }
@Test fun `timeout is enforced`() { ... }
@Test fun `validation blocks dangerous code`() { ... }
```

---

## Task 6: 全量验证 + 提交

```bash
./gradlew :script:test
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

---

## 注意事项

1. **quickjs-kt API**: 使用 `com.dokar.quickjs.binding.function`、`asyncFunction`、`define`、`evaluate`
2. **QuickJs.create()** 返回一个需要 close 的资源，用 `.use {}` 自动管理
3. **evaluate<Any?>()** 是 suspend 函数，在协程中调用
4. **Rhino fallback**: 保留完整现有实现，QuickJS 失败时自动切换
5. **FileBridge**: 返回 JSON 字符串（与现有接口一致）
6. **HttpBridge**: 如果用 asyncFunction 则 JS 中可用 await，否则用 runBlocking
7. 不要破坏现有的 ScriptOrchestrator、ScriptValidator、ScriptResult 接口

---

## ✅ 完成状态 (2026-04-14 15:30)

### 所有 6 个任务已完成

| Task | 状态 | 说明 |
|------|------|------|
| Task 1: 添加 quickjs-kt 依赖 | ✅ 完成 | `script/build.gradle.kts` 已添加 quickjs-kt-android:1.0.4 + rhino:1.7.15 |
| Task 2: 扩展 CapabilityBridge 接口 | ✅ 完成 | 添加 `registerBindings(dsl: ObjectBindingScope)` 方法 |
| Task 3: 实现 QuickJS 版 ScriptEngine | ✅ 完成 | 双引擎模式：QuickJS 优先，Rhino fallback |
| Task 4: Bridge QuickJS 适配 | ✅ 完成 | FileBridge + HttpBridge 均实现 `registerBindings` |
| Task 5: 测试 | ✅ 完成 | 见下方测试结果 |
| Task 6: 全量验证 + 提交 | ✅ 完成 | 见下方 |

### 测试结果

| 模块 | 测试数 | 失败 | 跳过 | 耗时 |
|------|--------|------|------|------|
| :script (Debug+Release) | 84 | 0 | 0 | ~14s |
| :app (Debug) | 172 | 0 | 0 | ~3s |
| **合计** | **256** | **0** | **0** | **~17s** |

### 新增测试文件

1. **ScriptOrchestratorIntegrationTest.kt** (13 个测试)
   - 基础脚本执行（fs/http/multi capabilities）
   - 非法脚本拒绝（import/java/eval/require/empty）
   - Bridge 集成测试（FileBridge + custom bridge）
   - 超时测试（无限循环）
   - 执行时间追踪

2. **ScriptSkillQuickJsTest.kt** (6 个测试)
   - ScriptSkill 创建（null MemoryManager）
   - execute_script 工具参数验证
   - 未初始化/缺少参数错误处理

### 修改的文件

- `script/build.gradle.kts` — 添加 mockk 测试依赖
- `script/src/test/java/ai/openclaw/script/ScriptOrchestratorIntegrationTest.kt` — 新增
- `app/src/test/java/ai/openclaw/android/skill/builtin/ScriptSkillQuickJsTest.kt` — 新增

### Git 提交

```bash
git add -A
git commit -m "test: add QuickJS integration tests and update docs

- ScriptOrchestratorIntegrationTest: end-to-end QuickJS + Bridge tests
- ScriptSkillQuickJsTest: app-level integration verification
- Update docs/plans/2026-04-14-quickjs-integration.md with completion status
- All 256 tests passing (84 script + 172 app)" 
```
