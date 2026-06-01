package ai.openclaw.android.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import ai.openclaw.plugin.api.ILlmEngine
import java.security.MessageDigest

/**
 * Manages the lifecycle of LLM plugins.
 * Responsible for discovery, signature verification, loading, and runtime registration.
 *
 * 支持两种插件来源：
 * 1. 运行时发现（discoverPlugins）— 扫描已安装 APK 中声明 ACTION_LLM_PLUGIN 的 Service
 * 2. 运行时注册（registerPlugin）— 从本地 filesDir/plugins 安装的插件元信息
 */
class PluginManager(private val context: Context) {
    companion object {
        private const val TAG = "PluginManager"
        const val ACTION_LLM_PLUGIN = "ai.openclaw.engine.LLM_PLUGIN"
        const val META_ENTRY_CLASS = "entry_class"
        const val META_ENGINE_TYPE = "engine_type"

        // TODO: Replace with actual certificate SHA-256
        // Obtain via: keytool -printcert -jarfile app.apk
        const val AUTHORIZED_CERT_SHA256 = "REPLACE_WITH_ACTUAL_SHA256"
    }

    /** 运行时发现的系统 APK 插件（由 PackageManager 发现） */
    data class DiscoveredPlugin(
        val packageName: String,
        val entryClassName: String,
        val engineType: String,
        val label: String
    )

    /** 已注册的插件元信息（运行时注册 + 系统发现） */
    private val registeredPlugins = mutableMapOf<String, ai.openclaw.android.plugin.PluginInfo>()

    /** 已加载的引擎实例（按需缓存） */
    private val loadedEngines = mutableMapOf<String, ILlmEngine>()

    /**
     * 注册插件元信息（供 PluginInstaller / PluginManagerExt 调用）
     */
    fun registerPlugin(info: ai.openclaw.android.plugin.PluginInfo) {
        registeredPlugins[info.id] = info
        Log.i(TAG, "Plugin registered: ${info.id} v${info.version}")
    }

    /**
     * 注销插件（供卸载 / 热更新调用）
     */
    fun unregisterPlugin(pluginId: String) {
        registeredPlugins.remove(pluginId)
        val engine = loadedEngines.remove(pluginId)
        engine?.release()
        Log.i(TAG, "Plugin unregistered: $pluginId")
    }

    /**
     * 获取已注册插件列表（包含系统发现 + 本地安装）
     */
    fun getAllRegisteredPlugins(): List<ai.openclaw.android.plugin.PluginInfo> {
        // 合并系统发现的 APK 插件
        val discovered = discoverPlugins().map { dp ->
            ai.openclaw.android.plugin.PluginInfo(
                id = dp.packageName,
                name = dp.label,
                version = "",
                engineType = dp.engineType,
                packageName = dp.packageName,
                enabled = true
            )
        }
        val registered = registeredPlugins.values.toList()
        // 去重：以 id 为 key，本地安装优先
        val merged = registered.associateBy { it.id }.toMutableMap()
        discovered.forEach { dp ->
            merged.putIfAbsent(dp.id, dp)
        }
        return merged.values.toList()
    }

    /**
     * 获取单个插件信息
     */
    fun getRegisteredPlugin(pluginId: String): ai.openclaw.android.plugin.PluginInfo? {
        return registeredPlugins[pluginId]
    }

    /**
     * 加载插件引擎（带缓存）
     */
    fun loadEngine(pluginId: String): ILlmEngine? {
        loadedEngines[pluginId]?.let { return it }

        val info = registeredPlugins[pluginId] ?: return null
        val packageName = info.packageName ?: return null
        val apkFile = info.sourceFile?.let { java.io.File(it) } ?: return null

        return try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_META_DATA
            ) ?: throw IllegalStateException("Cannot read APK: ${apkFile.absolutePath}")

            val entryClass = pi.applicationInfo?.metaData?.getString(META_ENTRY_CLASS)
                ?: throw IllegalStateException("Missing entry_class meta-data")

            val pluginContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )

            val clazz = pluginContext.classLoader.loadClass(entryClass)
            val instance = clazz.getDeclaredConstructor().newInstance()

            (instance as? ILlmEngine)?.also { engine ->
                engine.initialize(pluginContext)
                loadedEngines[pluginId] = engine
            } ?: throw IllegalStateException("$entryClass does not implement ILlmEngine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load engine for $pluginId", e)
            null
        }
    }

    /**
     * Scans for installed plugins that declare the correct Intent Filter
     * and match the authorized signature.
     */
    fun discoverPlugins(): List<DiscoveredPlugin> {
        val intent = Intent(ACTION_LLM_PLUGIN)
        val resolveInfos = context.packageManager.queryIntentServices(
            intent, 
            PackageManager.GET_META_DATA or PackageManager.GET_SERVICES
        )
        
        return resolveInfos.mapNotNull { info ->
            val serviceInfo = info.serviceInfo
            val metaData = serviceInfo.metaData
            
            if (metaData == null) return@mapNotNull null

            val entryClass = metaData.getString(META_ENTRY_CLASS)
            val engineType = metaData.getString(META_ENGINE_TYPE)

            if (entryClass.isNullOrEmpty() || engineType.isNullOrEmpty()) {
                Log.w(TAG, "Plugin ${serviceInfo.packageName} missing required meta-data")
                return@mapNotNull null
            }

            if (!verifySignature(serviceInfo.packageName)) {
                Log.w(TAG, "Signature mismatch for ${serviceInfo.packageName}")
                return@mapNotNull null
            }

            DiscoveredPlugin(
                packageName = serviceInfo.packageName,
                entryClassName = entryClass,
                engineType = engineType,
                label = serviceInfo.loadLabel(context.packageManager).toString()
            )
        }
    }

    fun verifySignatureForPackage(packageName: String): Boolean = verifySignature(packageName)

    private fun verifySignature(packageName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signatures = packageInfo.signingInfo?.apkContentsSigners ?: return false
            
            for (sig in signatures) {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(sig.toByteArray())
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                
                if (hash.equals(AUTHORIZED_CERT_SHA256, ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Signature check failed", e)
            false
        }
    }
}
