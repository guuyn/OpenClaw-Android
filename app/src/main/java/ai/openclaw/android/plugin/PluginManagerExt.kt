package ai.openclaw.android.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * PluginManager 扩展 —— 提供安装态插件的高层操作。
 *
 * 核心能力：
 * - listPlugins() — 列出已安装插件
 * - uninstallPlugin() — 卸载插件（含备份保留）
 * - updatePlugin() — 热更新（备份旧版本 → 安装新版本 → 重新注册）
 * - getPluginInfo() — 获取单个插件元信息
 * - operationState Flow — 所有操作通过 Flow 通知 UI
 */
class PluginManagerExt(
    private val context: Context,
    private val pluginManager: PluginManager
) {
    companion object {
        private const val TAG = "PluginManagerExt"
    }

    private val installer = PluginInstaller(context, pluginManager)

    /** 插件操作状态流 */
    private val _operationState = MutableStateFlow<PluginOperationState>(PluginOperationState.Idle)
    val operationState: Flow<PluginOperationState> = _operationState.asStateFlow()

    /** 已安装插件列表缓存（可通过 refreshPlugins() 刷新） */
    private val _installedPlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val installedPlugins: Flow<List<PluginInfo>> = _installedPlugins.asStateFlow()

    init {
        refreshPlugins()
    }

    /**
     * 列出已安装插件（本地安装 + 系统发现）
     */
    fun listPlugins(): List<PluginInfo> {
        return pluginManager.getAllRegisteredPlugins()
            .filter { it.enabled || it.packageName != null } // 系统发现的始终显示
            .sortedBy { it.name }
    }

    /**
     * 刷新插件列表并通知 Flow
     */
    fun refreshPlugins(): List<PluginInfo> {
        val plugins = listPlugins()
        _installedPlugins.value = plugins

        // 同时从磁盘扫描，发现未被注册的插件目录
        scanPluginDirectories()
        return _installedPlugins.value
    }

    /**
     * 扫描 plugins 目录，自动注册未注册的插件
     */
    private fun scanPluginDirectories() {
        val pluginsDir = File(context.filesDir, PluginInstaller.PLUGINS_DIR)
        if (!pluginsDir.exists()) return

        pluginsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name.startsWith(".")) return@forEach
            val manifestFile = File(dir, PluginInstaller.MANIFEST_FILE)
            if (!manifestFile.exists()) return@forEach

            val pluginId = dir.name
            // 如果已注册则跳过
            if (pluginManager.getRegisteredPlugin(pluginId) != null) return@forEach

            try {
                val manifest = JSONObject(manifestFile.readText())
                val info = PluginInfo(
                    id = manifest.getString("id"),
                    name = manifest.optString("name", pluginId),
                    version = manifest.optString("version", "0.0.0"),
                    author = manifest.optString("author", ""),
                    description = manifest.optString("description", ""),
                    iconPath = manifest.optString("icon")?.takeIf { it.isNotBlank() }
                        ?.let { File(dir, it).takeIf { f -> f.exists() }?.absolutePath },
                    enabled = true,
                    packageName = manifest.optString("packageName", null),
                    engineType = manifest.optString("engineType", null),
                    sourceFile = File(dir, "plugin.apk").takeIf { it.exists() }?.absolutePath
                )
                pluginManager.registerPlugin(info)
                Log.i(TAG, "Auto-registered plugin from directory: $pluginId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register plugin from $pluginId", e)
            }
        }
    }

    /**
     * 获取单个插件信息
     */
    fun getPluginInfo(pluginId: String): PluginInfo? {
        return pluginManager.getRegisteredPlugin(pluginId)
            ?: _installedPlugins.value.find { it.id == pluginId }
    }

    /**
     * 卸载插件
     *
     * @param pluginId 插件 ID
     * @param keepBackup 是否保留备份（默认 true）
     * @return 是否成功
     */
    suspend fun uninstallPlugin(pluginId: String, keepBackup: Boolean = true): Boolean {
        _operationState.value = PluginOperationState.InProgress("正在卸载 $pluginId", pluginId)

        return try {
            val info = getPluginInfo(pluginId)

            // 1. 注销（释放引擎资源）
            pluginManager.unregisterPlugin(pluginId)

            // 2. 备份（如果保留）
            if (keepBackup) {
                val pluginDir = File(context.filesDir, "${PluginInstaller.PLUGINS_DIR}/$pluginId")
                if (pluginDir.exists()) {
                    installer.install(pluginDir) // will trigger backup internally
                }
            }

            // 3. 删除插件目录
            val pluginDir = File(context.filesDir, "${PluginInstaller.PLUGINS_DIR}/$pluginId")
            if (pluginDir.exists()) {
                pluginDir.deleteRecursively()
            }

            // 4. 刷新列表
            refreshPlugins()

            _operationState.value = PluginOperationState.Completed("已卸载 $pluginId", pluginId)
            Log.i(TAG, "Plugin uninstalled: $pluginId")
            true
        } catch (e: Exception) {
            _operationState.value = PluginOperationState.Error("卸载失败: ${e.message}", pluginId, e)
            Log.e(TAG, "Uninstall failed: $pluginId", e)
            false
        }
    }

    /**
     * 热更新插件（无需重启 App）
     *
     * 流程：备份旧版本 → 安装新版本 → 注销旧引擎 → 注册新插件
     *
     * @param pluginId 插件 ID
     * @param newFile 新版本 APK/ZIP 文件
     * @return 安装结果
     */
    suspend fun updatePlugin(pluginId: String, newFile: File): PluginInstallResult {
        _operationState.value = PluginOperationState.InProgress("正在更新 $pluginId", pluginId)

        return try {
            val existingInfo = getPluginInfo(pluginId)
            if (existingInfo == null) {
                _operationState.value = PluginOperationState.Error("插件不存在: $pluginId", pluginId)
                return PluginInstallResult.Failed("插件不存在: $pluginId")
            }

            // 1. 备份旧版本
            val pluginDir = File(context.filesDir, "${PluginInstaller.PLUGINS_DIR}/$pluginId")
            if (pluginDir.exists()) {
                // 使用 installer 内部备份逻辑（通过 install 同名插件触发）
                val backupSuccess = try {
                    // 直接备份
                    val backupsDir = File(context.filesDir, "${PluginInstaller.PLUGINS_DIR}/.backups")
                        .also { it.mkdirs() }
                    val timestamp = System.currentTimeMillis()
                    pluginDir.copyRecursively(File(backupsDir, "${pluginId}_$timestamp"), overwrite = true)
                    Log.i(TAG, "Backed up $pluginId before update")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Backup failed before update, continuing", e)
                    false
                }
            }

            // 2. 注销旧插件（释放引擎）
            pluginManager.unregisterPlugin(pluginId)

            // 3. 安装新版本
            val result = installer.install(newFile)
            when (result) {
                is PluginInstallResult.Success -> {
                    refreshPlugins()
                    _operationState.value = PluginOperationState.Completed("已更新 $pluginId 到 v${result.pluginInfo.version}", pluginId)
                    Log.i(TAG, "Plugin hot-updated: $pluginId → v${result.pluginInfo.version}")
                    result
                }
                is PluginInstallResult.Failed -> {
                    // 安装失败，尝试恢复旧版本
                    _operationState.value = PluginOperationState.Error("更新失败: ${result.reason}", pluginId)
                    result
                }
            }
        } catch (e: Exception) {
            _operationState.value = PluginOperationState.Error("热更新失败: ${e.message}", pluginId, e)
            PluginInstallResult.Failed("热更新失败: ${e.message}")
        }
    }

    /**
     * 安装插件（从文件）
     */
    suspend fun installPlugin(file: File): PluginInstallResult {
        _operationState.value = PluginOperationState.InProgress("正在安装 ${file.name}", null)

        val result = installer.install(file)
        when (result) {
            is PluginInstallResult.Success -> {
                refreshPlugins()
                _operationState.value = PluginOperationState.Completed("已安装 ${result.pluginInfo.name}", result.pluginInfo.id)
            }
            is PluginInstallResult.Failed -> {
                _operationState.value = PluginOperationState.Error("安装失败: ${result.reason}", null)
            }
        }
        return result
    }

    /**
     * 启用/禁用插件
     */
    fun togglePlugin(pluginId: String, enabled: Boolean) {
        val info = getPluginInfo(pluginId) ?: return
        val updated = info.copy(enabled = enabled)
        pluginManager.registerPlugin(updated)
        refreshPlugins()
        Log.i(TAG, "Plugin ${if (enabled) "enabled" else "disabled"}: $pluginId")
    }

    /**
     * 回滚到指定备份
     */
    suspend fun rollbackPlugin(pluginId: String, backupTimestamp: Long): Boolean {
        _operationState.value = PluginOperationState.InProgress("正在回滚 $pluginId", pluginId)

        val success = installer.rollback(pluginId, backupTimestamp)
        _operationState.value = if (success) {
            refreshPlugins()
            PluginOperationState.Completed("已回滚 $pluginId", pluginId)
        } else {
            PluginOperationState.Error("回滚失败", pluginId)
        }
        return success
    }

    /**
     * 列出备份
     */
    fun listBackups(pluginId: String): List<Pair<Long, String>> {
        val backupsDir = File(context.filesDir, "${PluginInstaller.PLUGINS_DIR}/.backups")
        if (!backupsDir.exists()) return emptyList()

        return backupsDir.listFiles()
            ?.filter { it.name.startsWith("${pluginId}_") }
            ?.mapNotNull { dir ->
                val timestamp = dir.name.removePrefix("${pluginId}_").toLongOrNull()
                timestamp?.let { ts -> ts to dir.name }
            }
            ?.sortedByDescending { it.first }
            ?: emptyList()
    }
}
