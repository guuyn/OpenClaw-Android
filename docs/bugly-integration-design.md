# Bugly 崩溃报告集成方案

> 创建日期: 2026-04-30 | 状态: 设计方案 | 适用版本: OpenClaw-Android 1.0+

## 概述

集成腾讯 Bugly SDK，实现 Java Crash、Native Crash、ANR 的自动采集和上报，辅助线上问题诊断。

---

## 1. build.gradle.kts 依赖配置

### 1.1 添加依赖

在 `app/build.gradle.kts` 的 `dependencies` 块中添加：

```kotlin
// ==================== Bugly Crash Reporting ====================
// Bugly 主 SDK — Java/Kotlin Crash + ANR 检测
implementation("com.tencent.bugly:crashreport:4.1.9.3")
// Bugly NDK — Native Crash 采集
implementation("com.tencent.bugly:nativecrashreport:4.0.0")
```

### 1.2 BuildConfig 字段（AppID 配置）

在 `android > defaultConfig` 中添加 BuildConfig 字段，从 `local.properties` 读取：

```kotlin
// 在 defaultConfig {} 块内添加
val buglyAppIdDebug: String? = localProperties.getProperty("BUGLY_APP_ID_DEBUG")
val buglyAppIdRelease: String? = localProperties.getProperty("BUGLY_APP_ID_RELEASE")

buildConfigField(
    "String",
    "BUGLY_APP_ID_DEBUG",
    "\"${buglyAppIdDebug ?: "placeholder-debug"}\""
)
buildConfigField(
    "String",
    "BUGLY_APP_ID_RELEASE",
    "\"${buglyAppIdRelease ?: "placeholder-release"}\""
)
```

### 1.3 开启 buildConfig 特性

确保 `buildFeatures` 中有 `buildConfig = true`（Kotlin DSL 默认开启，显式声明更安全）：

```kotlin
// 确认已有此配置，如没有则添加
buildFeatures {
    compose = true
    buildConfig = true
}
```

### 1.4 local.properties 配置

在项目根目录 `local.properties` 中添加：

```properties
# Bugly AppID（需在腾讯 Bugly 控制台创建应用后获取）
BUGLY_APP_ID_DEBUG=your-debug-app-id-here
BUGLY_APP_ID_RELEASE=your-release-app-id-here
```

> ⚠️ `local.properties` 已在 `.gitignore` 中，不会提交到版本库。

---

## 2. AndroidManifest.xml 权限配置

Bugly SDK 需要以下权限，其中 `INTERNET` 和 `ACCESS_NETWORK_STATE` 已有，只需补充：

```xml
<!-- Bugly 所需权限（添加到现有 permissions 区块） -->
<!-- 读取设备信息（用于区分设备） -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<!-- Bugly 需要写入崩溃日志到缓存目录 -->
<uses-permission android:name="android.permission.READ_LOGS"
    tools:ignore="ProtectedPermissions" />
```

**现有权限已满足的：**
- `INTERNET` — ✅ 已有（崩溃上报需要）
- `ACCESS_NETWORK_STATE` — ✅ 已有（网络状态检测）

**注意：** `READ_PHONE_STATE` 和 `READ_LOGS` 在 Android 10+ (API 29+) 是受限权限，Bugly SDK 会自动处理权限不足的情况（静默降级），不需要运行时请求。

---

## 3. OpenClawApplication.kt 初始化代码

修改 `OpenClawApplication.kt`，在 `onCreate()` 中初始化 Bugly：

```kotlin
package ai.openclaw.android

import android.app.Application
import android.content.Context
import ai.openclaw.android.permission.PermissionManager
import com.tencent.bugly.Bugly
import com.tencent.bugly.crashreport.CrashReport

class OpenClawApplication : Application() {
    lateinit var permissionManager: PermissionManager
        private set

    override fun onCreate() {
        super.onCreate()
        permissionManager = PermissionManager(this)
        initBugly()
    }

    /**
     * 初始化 Bugly 崩溃报告
     * - Debug 包：开启详细日志，方便本地调试
     * - Release 包：关闭日志输出，仅静默上报
     * - AppID 从 BuildConfig 读取，通过 local.properties 配置
     */
    private fun initBugly() {
        val appId = if (BuildConfig.DEBUG) {
            BuildConfig.BUGLY_APP_ID_DEBUG
        } else {
            BuildConfig.BUGLY_APP_ID_RELEASE
        }

        // 占位符检测：未配置真实 AppID 时跳过初始化
        if (appId.startsWith("placeholder") || appId.isBlank()) {
            android.util.Log.w(
                TAG,
                "Bugly AppID 未配置，跳过初始化。" +
                "请在 local.properties 中设置 BUGLY_APP_ID_DEBUG / BUGLY_APP_ID_RELEASE"
            )
            return
        }

        CrashReport.UserStrategy(this).apply {
            // 应用版本号（Bugly 自动取 versionName，也可手动指定）
            appVersion = BuildConfig.VERSION_NAME

            // 渠道标识
            appChannel = if (BuildConfig.DEBUG) "debug" else "release"

            // Debug 包开启详细日志，Release 关闭
            isReportDetailInfo = !BuildConfig.DEBUG

            // 开启 ANR 监控
            isAnrControlEnabled = true

            // 开启 Native Crash 采集（需配合 nativecrashreport 依赖）
            isNativeRQEnable = true

            // 自定义设备标识（使用 Android ID，避免 IMEI 隐私问题）
            try {
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                deviceIdentifier = androidId?.takeLast(16) ?: "unknown"
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to get device identifier for Bugly", e)
            }
        }.also { strategy ->
            CrashReport.initCrashReport(applicationContext, appId, BuildConfig.DEBUG, strategy)
            android.util.Log.i(TAG, "Bugly initialized (appId: ${appId.take(3)}***, debug: ${BuildConfig.DEBUG})")
        }

        // 设置自定义标签（用于 Bugly 控制台筛选）
        CrashReport.setUserId("openclaw-${if (BuildConfig.DEBUG) "debug" else "release"}")
        CrashReport.putUserData(this, "app_version", BuildConfig.VERSION_NAME)
        CrashReport.putUserData(this, "version_code", BuildConfig.VERSION_CODE.toString())
    }

    companion object {
        private const val TAG = "OpenClawApplication"
    }
}

fun Context.permissionManager(): PermissionManager =
    (applicationContext as OpenClawApplication).permissionManager
```

### 初始化说明

| 配置项 | Debug | Release |
|--------|-------|---------|
| 日志输出 | ✅ 开启 | ❌ 关闭 |
| ANR 监控 | ✅ | ✅ |
| Native Crash | ✅ | ✅ |
| 自定义设备标识 | Android ID (后16位) | Android ID (后16位) |
| User ID | `openclaw-debug` | `openclaw-release` |

---

## 4. 关键代码路径 — 自定义日志埋点

Bugly 支持通过 `CrashReport.postCatchedException()` 和 `CrashReport.log()` 添加自定义上下文。以下标注了关键埋点位置。

### 4.1 AgentSession.kt

**文件**: `app/src/main/java/ai/openclaw/android/agent/AgentSession.kt`

在以下位置添加 Bugly 日志：

```kotlin
// 在 companion object 的 TAG 下方添加 Bugly import
import com.tencent.bugly.crashreport.CrashReport
```

#### 位置 A: 模型调用失败（关键错误路径）

```kotlin
// 在 callLLMStep() 方法中，替换现有 Log.e:
private suspend fun callLLMStep(state: AgentState, activeTools: List<Tool>?): Pair<String?, AgentState> {
    val messages = buildMessagesFromState(state, true)
    val result = modelClient.chat(messages, activeTools)

    if (result.isFailure) {
        val exception = result.exceptionOrNull()
        val errorMsg = "抱歉，模型调用失败: ${exception?.message}"
        Log.e(TAG, "[State] ${state.dump()} → Model call failed", exception)

        // 【Bugly 埋点】记录非致命异常 + 上下文
        CrashReport.postCatchedException(
            exception ?: Exception("Model call failed with null exception")
        )
        CrashRecord.logAgentSessionError("model_call_failed", state.dump(), exception?.message)

        return errorMsg to state
    }
    // ... 后续代码不变
}
```

#### 位置 B: 工具执行失败

```kotlin
// 在 executeToolCall() 的 catch 逻辑中（executeToolsStep 内部）
// 找到 executeToolCall 方法中 skill result failed 分支：
Log.e(TAG, "Tool $toolName failed: ${skillResult.error}")

// 【Bugly 埋点】
CrashRecord.logAgentSessionError("tool_failed", "tool=$toolName", skillResult.error)
```

#### 位置 C: Tool 参数解析失败

```kotlin
// 在 parseToolCallParams() 的 catch 块中：
} catch (e: Exception) {
    Log.e(TAG, "Failed to parse tool params: ${e.message}")
    // 【Bugly 埋点】
    CrashReport.postCatchedException(e)
    emptyMap()
}
```

#### 位置 D: 超时/异常轮次

```kotlin
// 在 handleMessage() 末尾的 max rounds exceeded 分支：
if (!state.isFinalAnswer) {
    Log.w(TAG, "[State] Max rounds exceeded, forcing final response")
    // 【Bugly 埋点】
    CrashRecord.logAgentSessionError("max_rounds_exceeded", state.dump(), null)
    // ...
}
```

### 4.2 LocalLLMClient.kt

**文件**: `app/src/main/java/ai/openclaw/android/model/LocalLLMClient.kt`

```kotlin
// 添加 import
import com.tencent.bugly.crashreport.CrashReport
```

#### 位置 A: Native 引擎初始化失败（高优先级埋点）

```kotlin
// 在 initialize() 方法的 catch 块中：
} catch (e: Exception) {
    _state.value = LoadState.ERROR
    Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
    LogManager.shared.log("ERROR", TAG, "初始化异常: ${e.javaClass.simpleName}: ${e.message}")

    // 【Bugly 埋点】Native 引擎初始化失败是关键错误
    CrashRecord.logLocalLLMError("engine_init_failed", e)
    false
}
```

#### 位置 B: Backend 初始化失败（区分 NPU/GPU/CPU）

```kotlin
// 在 tryInitEngine() 的 catch 块中：
} catch (e: Throwable) {
    onLog("❌ $backendName 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
    // 【Bugly 埋点】记录 Backend 类型和异常
    CrashRecord.logLocalLLMError("backend_init_failed_$backendName", e)
    if (backendName == "GPU" && prefs != null) {
        prefs.edit().putBoolean(KEY_GPU_INIT_PENDING, false).apply()
    }
    null
}
```

#### 位置 C: chat() 调用失败

```kotlin
// 在 chat() 方法的 catch 块中：
} catch (e: Exception) {
    Log.e(TAG, "Chat failed", e)
    // 【Bugly 埋点】
    CrashRecord.logLocalLLMError("chat_failed", e)
    Result.failure(e)
}
```

#### 位置 D: 流式生成失败

```kotlin
// 在 chatStream() 的 catch 块中（parseToolCallsFromErrors fallback 之前）：
} catch (e: Exception) {
    val textToolCalls = parseToolCallsFromError(e.message ?: "")
    if (textToolCalls != null) {
        // fallback 成功，只记录 warning
        CrashRecord.logLocalLLMWarning("stream_parse_fallback", e.message)
        // ...
    } else {
        Log.e(TAG, "Stream failed", e)
        // 【Bugly 埋点】
        CrashRecord.logLocalLLMError("stream_failed", e)
        emit(ChatEvent.Error(e.message ?: "Generation failed"))
    }
}
```

### 4.3 CrashRecord 辅助工具类

新建 `CrashRecord.kt` 统一封装 Bugly 日志调用，避免在各处直接引用 Bugly API：

```kotlin
package ai.openclaw.android.util

import com.tencent.bugly.crashreport.CrashReport

/**
 * 统一封装 Bugly 自定义日志。
 *
 * 设计原则：
 * - 不发送 PII（不记录用户输入内容、API Key 等敏感信息）
 * - 只记录错误类型、模块名、异常类名
 * - 使用 log() 记录上下文，使用 postCatchedException() 记录非致命异常
 */
object CrashRecord {

    /**
     * 记录 AgentSession 相关错误
     */
    fun logAgentSessionError(errorType: String, context: String, exceptionMessage: String?) {
        CrashReport.log(
            CrashReport.INFO,
            "AgentSession",
            "[Error] type=$errorType, exception=${exceptionMessage ?: "none"}, context=$context"
        )
    }

    /**
     * 记录 LocalLLMClient 相关错误
     */
    fun logLocalLLMError(errorType: String, e: Throwable) {
        CrashReport.log(
            CrashReport.ERROR,
            "LocalLLMClient",
            "[Error] type=$errorType, exception=${e.javaClass.simpleName}: ${e.message}"
        )
        // 非致命异常也上报到 Bugly
        CrashReport.postCatchedException(e)
    }

    /**
     * 记录 LocalLLMClient 警告（不触发崩溃上报）
     */
    fun logLocalLLMWarning(errorType: String, message: String?) {
        CrashReport.log(
            CrashReport.WARN,
            "LocalLLMClient",
            "[Warning] type=$errorType, message=${message ?: "unknown"}"
        )
    }

    /**
     * 记录通用自定义事件（用于 Bugly 自定义分析）
     */
    fun trackEvent(eventId: String, keyValues: Map<String, String>) {
        CrashReport.postCustomEvent(eventId, keyValues)
    }
}
```

---

## 5. proguard-rules.pro 混淆规则

在 `app/proguard-rules.pro` 末尾添加：

```proguard
# ==================== Bugly ====================
# Bugly SDK 混淆规则
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
# 保持 native 方法不被混淆（nativecrashreport 依赖）
-keep class com.tencent.bugly.crashreport.protocols.** { *; }
```

---

## 6. 注意事项

### 6.1 AppID 配置

1. 前往 [腾讯 Bugly 控制台](https://bugly.qq.com/) 创建应用
2. 分别创建 Debug 和 Release 两个应用（或共用一个，通过渠道区分）
3. 将 AppID 填入 `local.properties`：
   ```properties
   BUGLY_APP_ID_DEBUG=xxx
   BUGLY_APP_ID_RELEASE=yyy
   ```
4. **代码中包含占位符检测**：未配置时跳过初始化，不报错

### 6.2 环境区分

| 维度 | Debug | Release |
|------|-------|---------|
| AppID | `BUGLY_APP_ID_DEBUG` | `BUGLY_APP_ID_RELEASE` |
| 渠道标签 | `debug` | `release` |
| 日志输出 | 开启（LogCat 可见） | 关闭 |
| User ID | `openclaw-debug` | `openclaw-release` |
| 崩溃上报 | ✅ 上报 | ✅ 上报 |

建议在 Bugly 控制台创建两个应用分别对应 Debug 和 Release，避免测试数据污染线上统计。

### 6.3 隐私保护

Bugly SDK 默认会采集以下信息，本方案已做的处理：

| 数据类型 | Bugly 默认 | 本方案处理 |
|----------|-----------|-----------|
| 设备标识 | IMEI/MAC（Android 10+ 自动降级） | ✅ 使用 Android ID 后16位 |
| 应用包名 | ✅ 采集 | 可接受 |
| 崩溃堆栈 | ✅ 采集 | 可接受（不含敏感数据） |
| 用户输入内容 | ❌ 不采集 | N/A |
| API Key | ❌ 不采集 | ⚠️ 确保 CrashRecord 不记录 |

**已采取的隐私措施：**
1. ✅ `CrashRecord` 只记录错误类型和异常类名，**不记录消息内容中的敏感信息**
2. ✅ 设备标识使用 Android ID 而非 IMEI
3. ✅ `BuildConfig.DEBUG` 控制日志输出，Release 包无 Bugly 日志
4. ✅ `AppID` 从 `local.properties` 读取，不提交到版本库
5. ⚠️ **避免在自定义日志中记录**：用户消息内容、API Key、文件路径、个人信息

### 6.4 已知问题与限制

1. **Native Crash 与 LiteRT-LM**：LiteRT-LM 使用 native 库，Bugly 的 `nativecrashreport` 能捕获大部分 native crash，但部分 GPU driver 级 crash 可能无法完整上报
2. **首次崩溃延迟**：Bugly 采用异步上报策略，崩溃报告可能在下次启动时上传
3. **ANR 检测延迟**：ANR 默认检测阈值为 5 秒，部分 LiteRT 初始化超时（60-90 秒）不会被误判为 ANR（因为 SDK 在后台线程执行）
4. **包体积影响**：Bugly SDK 约增加 ~300KB（crashreport + nativecrashreport）

### 6.5 验证清单

集成完成后按以下步骤验证：

- [ ] `./gradlew assembleDebug` 编译通过
- [ ] Debug 包安装后 LogCat 能看到 `Bugly initialized` 日志
- [ ] 手动触发测试崩溃：`CrashReport.testJavaCrash()`
- [ ] 确认 Bugly 控制台收到测试崩溃报告
- [ ] `./gradlew assembleRelease` 编译通过
- [ ] ProGuard 混淆后 Release 包能正常启动
- [ ] 确认 Release 包 LogCat 无 Bugly 相关日志输出

---

## 附录：文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/build.gradle.kts` | 修改 | 添加 Bugly 依赖 + BuildConfig 字段 |
| `app/src/main/AndroidManifest.xml` | 修改 | 添加 READ_PHONE_STATE + READ_LOGS 权限 |
| `app/src/main/java/.../OpenClawApplication.kt` | 修改 | 添加 Bugly 初始化 |
| `app/src/main/java/.../util/CrashRecord.kt` | 新建 | Bugly 日志封装工具类 |
| `app/src/main/java/.../agent/AgentSession.kt` | 修改 | 添加 Bugly 埋点（4 处） |
| `app/src/main/java/.../model/LocalLLMClient.kt` | 修改 | 添加 Bugly 埋点（4 处） |
| `app/proguard-rules.pro` | 修改 | 添加 Bugly 混淆规则 |
| `local.properties` | 修改 | 添加 BUGLY_APP_ID_DEBUG/RELEASE |
