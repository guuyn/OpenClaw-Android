# OpenClaw LLM 插件化架构设计方案

> **版本**: v1.0
> **日期**: 2026-05-20
> **状态**: 提案 (Proposal)

## 一、 核心设计理念

为了解决端侧 LLM 模型在 Android 碎片化环境下的兼容性、体积限制和网络分发问题，本方案采用 **AOSP SystemUI Overlay 模式（进程内反射加载）**。

1.  **In-Process Loading (零 IPC 开销)**: 插件代码运行在主 App 进程内，方法调用直接跳转，流式输出 (Streaming) 延迟极低，无 Binder 通信损耗。
2.  **Shared Interface Contract (契约模式)**: 主 App 和插件通过共享一个纯接口库 (`openclaw-plugin-sdk`) 进行通信，确保类型安全，避免全反射带来的脆弱性。
3.  **Native Isolation (Native 隔离)**: 利用插件 APK 安装后的独立 Library 路径，通过插件 Context 加载 `.so`，解决 Android Linker Namespace 隔离问题。

## 二、 架构概览

### 1. 模块划分

| 模块 | 说明 | 依赖 |
|------|------|------|
| **`app` (Host)** | 主 App。包含 UI、Agent 逻辑、插件管理器。 | `plugin-sdk` |
| **`plugin-sdk` (Contract)** | 纯接口库。定义 `ILlmEngine` 和回调接口。 | 无 |
| **`plugin-litert` (Plugin)** | LiteRT 插件 APK。包含 LiteRT 引擎和 NPU 加速。 | `plugin-sdk`, `litertlm` |
| **`plugin-gguf` (Plugin)** | GGUF 插件 APK。包含 llama.cpp 引擎。 | `plugin-sdk`, `llama-android` |

### 2. 数据流

```text
[用户输入] -> [主 App Agent] -> [PluginManager] -> [Plugin Context] -> [Native Engine]
                                                    (In-Process)
       ^                                                     |
       |                                                     v
       +------- [Streaming Callback (Direct Call)] <-------+
```

## 三、 核心接口定义 (`plugin-sdk`)

插件与宿主通过该 SDK 交互。

```kotlin
// plugin-sdk: ILlmEngine.kt
package ai.openclaw.plugin.api

interface ILlmEngine {
    // 获取插件信息
    val meta: PluginMeta
    
    // 加载模型
    fun loadModel(config: ModelConfig): Result<Unit>
    
    // 流式生成 (核心方法)
    fun generateStream(prompt: String, tools: List<Tool>, callback: IGenerationCallback)
    
    // 停止生成
    fun stop()
    
    // 卸载模型释放内存
    fun unload()
}

data class PluginMeta(val name: String, val version: String, val engineType: String)
```

## 四、 核心实现机制

### 1. 插件发现与签名校验 (Host)

```kotlin
class PluginManager(private val context: Context) {
    
    fun discoverPlugins(): List<PluginPackage> {
        val intent = Intent("ai.openclaw.engine.LLM_PLUGIN")
        val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
        
        return resolveInfos.mapNotNull { info ->
            // 1. 获取插件签名
            val sigs = context.packageManager.getPackageInfo(
                info.serviceInfo.packageName, 
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo
            
            // 2. 校验签名 (必须匹配主 App 签名，防止恶意注入)
            if (verifySignature(sigs)) {
                PluginPackage(
                    packageName = info.serviceInfo.packageName,
                    className = info.serviceInfo.metaData.getString("entry_class")
                        ?: throw IllegalStateException("Missing entry_class metadata"),
                    engineType = info.serviceInfo.metaData.getString("engine_type") ?: "unknown"
                )
            } else {
                Log.w("PluginManager", "Signature mismatch for ${info.serviceInfo.packageName}")
                null
            }
        }
    }
}
```

### 2. 反射加载与 Native 注入 (Host)

```kotlin
fun loadPlugin(pluginPkg: PluginPackage): ILlmEngine {
    // 1. 获取插件 Context (关键：包含代码和资源)
    val pluginContext = context.createPackageContext(
        pluginPkg.packageName,
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
    )
    
    // 2. 通过插件 ClassLoader 加载入口类
    val clazz = pluginContext.classLoader.loadClass(pluginPkg.className)
    
    // 3. 实例化 (入口类必须在插件 ClassLoader 下实例化，才能正确调用 System.loadLibrary)
    val instance = clazz.getDeclaredConstructor().newInstance()
    
    // 4. 强转接口 (零反射开销，直接方法调用)
    return instance as ILlmEngine
}
```

### 3. 插件端实现 (Plugin)

```kotlin
// plugin-litert: LiteRTEngine.kt
class LiteRTEngine : ILlmEngine {
    // 关键：在插件 Context 下，System.loadLibrary 会自动查找 APK 的 lib/ 目录
    init {
        try {
            System.loadLibrary("litert_lm_engine")
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException("Failed to load LiteRT native lib", e)
        }
    }

    override val meta = PluginMeta("LiteRT Engine", "1.0", "litert")

    override fun generateStream(prompt: String, tools: List<Tool>, callback: IGenerationCallback) {
        // 直接调用 JNI 方法，Native 代码运行在宿主进程中
        nativeGenerate(prompt, object : NativeCallback {
            override fun onToken(token: String) = callback.onToken(token)
            override fun onToolCall(name: String, args: String) = callback.onToolCall(name, args)
            override fun onComplete() = callback.onComplete()
        })
    }

    private external fun nativeGenerate(prompt: String, callback: NativeCallback)
}
```

## 五、 关键痛点解决方案

### 1. Native 库加载陷阱 (Android 10+ Linker Namespace)
*   **问题**: 宿主进程直接加载插件类时，可能找不到插件的 `.so`。
*   **解法**: 必须确保**插件 APK 已安装**。调用 `System.loadLibrary` 时，当前线程的 ClassLoader 必须是插件的 ClassLoader（上述 `pluginContext.classLoader.loadClass` 保证了这一点）。

### 2. 依赖冲突 (Classpath Collision)
*   **解法**: 
    *   `plugin-sdk` 必须使用**纯 Java 接口**或**基础 Kotlin 类型**，不依赖第三方库。
    *   宿主和插件统一第三方库版本，或在插件打包时使用 **Shading** (重命名依赖包路径)。

### 3. 模型文件存储
*   **路径**: `/storage/emulated/0/OpenClaw/Models/`
*   **权限**: 插件负责下载/校验，主 App 负责管理。双方通过绝对路径字符串传递。

### 4. 内存管理
*   **OOM 保护**: 主 App 监控 `ActivityManager.MemoryInfo`。当可用内存低于阈值时，自动调用 `engine.unload()` 释放模型。

## 六、 插件 Manifest 模板

```xml
<manifest package="ai.openclaw.plugin.litert">
    <application>
        <service
            android:name=".LiteRTService"
            android:exported="true">
            <intent-filter>
                <action android:name="ai.openclaw.engine.LLM_PLUGIN" />
            </intent-filter>
            <meta-data
                android:name="entry_class"
                android:value="ai.openclaw.plugin.litert.LiteRTEngine" />
            <meta-data
                android:name="engine_type"
                android:value="litert" />
        </service>
    </application>
</manifest>
```

## 七、 实施计划

1.  **Phase 1**: 创建 `plugin-sdk` 库，实现 `PluginManager`。
2.  **Phase 2**: 开发 `plugin-litert`，验证 Native 加载和流式输出。
3.  **Phase 3**: 开发 `plugin-gguf`，完善模型下载器。
4.  **Phase 4**: 内存监控、异常隔离、真机兼容性测试。
