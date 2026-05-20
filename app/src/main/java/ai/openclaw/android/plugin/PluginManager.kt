package ai.openclaw.android.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import ai.openclaw.plugin.api.ILlmEngine
import java.security.MessageDigest

/**
 * Manages the lifecycle of LLM plugins.
 * Responsible for discovery, signature verification, and loading.
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

    data class PluginInfo(
        val packageName: String,
        val entryClassName: String,
        val engineType: String,
        val label: String
    )

    /**
     * Scans for installed plugins that declare the correct Intent Filter
     * and match the authorized signature.
     */
    fun discoverPlugins(): List<PluginInfo> {
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

            PluginInfo(
                packageName = serviceInfo.packageName,
                entryClassName = entryClass,
                engineType = engineType,
                label = serviceInfo.loadLabel(context.packageManager).toString()
            )
        }
    }

    /**
     * Loads the plugin engine into memory.
     * Uses Context.createPackageContext to isolate resources and native libraries.
     */
    fun loadEngine(pluginInfo: PluginInfo): ILlmEngine {
        try {
            // 1. Create Plugin Context
            val pluginContext = context.createPackageContext(
                pluginInfo.packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )

            // 2. Load Class via Plugin ClassLoader
            val clazz = pluginContext.classLoader.loadClass(pluginInfo.entryClassName)
            
            // 3. Instantiate
            val instance = clazz.getDeclaredConstructor().newInstance()

            // 4. Cast & Return
            return instance as? ILlmEngine 
                ?: throw IllegalStateException("Class ${pluginInfo.entryClassName} does not implement ILlmEngine")
                
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.packageName}", e)
            throw RuntimeException("Plugin load failed", e)
        }
    }

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
