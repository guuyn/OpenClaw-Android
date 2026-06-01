package ai.openclaw.android.plugin

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 插件安装器 — 支持从 APK / ZIP 安装插件。
 *
 * 安装流程：
 * 1. 验证文件存在
 * 2. 解析 manifest（APK→AndroidManifest, ZIP→plugin.json）
 * 3. 签名校验（APK）
 * 4. 备份旧版本
 * 5. 解压到 plugins/{id}/ 目录
 * 6. 持久化元信息到 plugin.json
 * 7. 调用 PluginManager.registerPlugin()
 */
class PluginInstaller(
    private val context: Context,
    private val pluginManager: PluginManager
) {
    companion object {
        private const val TAG = "PluginInstaller"
        const val PLUGINS_DIR = "plugins"
        const val MANIFEST_FILE = "plugin.json"
    }

    private val pluginsDir: File
        get() = File(context.filesDir, PLUGINS_DIR).also { it.mkdirs() }

    /**
     * 从文件安装插件
     *
     * @param sourceFile APK 或 ZIP 文件
     * @return 安装结果
     */
    suspend fun install(sourceFile: File): PluginInstallResult = withContext(Dispatchers.IO) {
        // 1. 验证文件
        if (!sourceFile.exists()) {
            return@withContext PluginInstallResult.Failed("文件不存在: ${sourceFile.absolutePath}")
        }
        if (!sourceFile.canRead()) {
            return@withContext PluginInstallResult.Failed("文件不可读: ${sourceFile.absolutePath}")
        }

        val fileExtension = sourceFile.extension.lowercase()
        if (fileExtension !in listOf("apk", "zip")) {
            return@withContext PluginInstallResult.Failed("不支持的文件格式: $fileExtension (仅支持 APK/ZIP)")
        }

        try {
            // 2. 解析插件元信息
            val manifest = when (fileExtension) {
                "apk" -> parseApkManifest(sourceFile)
                "zip" -> parseZipManifest(sourceFile)
                else -> null
            } ?: return@withContext PluginInstallResult.Failed("无法解析插件 manifest")

            val pluginId = manifest.getString("id")
                ?: return@withContext PluginInstallResult.Failed("manifest 缺少 id 字段")

            // 3. 检查是否已存在同名插件（版本冲突处理）
            val existingDir = File(pluginsDir, pluginId)
            if (existingDir.exists()) {
                val existingInfo = loadExistingPluginInfo(existingDir, pluginId)
                val existingVersion = existingInfo?.version
                val newVersion = manifest.optString("version", "0.0.0")

                if (existingVersion == newVersion) {
                    return@withContext PluginInstallResult.Failed("版本冲突：已安装相同版本 $newVersion")
                }
                // 旧版本存在，需要备份
                Log.i(TAG, "Backing up existing plugin $pluginId v$existingVersion → v$newVersion")
                if (!backupPlugin(existingDir, pluginId)) {
                    Log.w(TAG, "Backup failed, continuing with install (no rollback possible)")
                }
            }

            // 4. APK 签名验证
            if (fileExtension == "apk") {
                if (!verifyApkSignature(sourceFile)) {
                    return@withContext PluginInstallResult.Failed("签名验证失败：插件未通过授权签名校验")
                }
            }

            // 5. 解压到插件目录
            val targetDir = File(pluginsDir, pluginId)
            targetDir.mkdirs()

            when (fileExtension) {
                "apk" -> extractApk(sourceFile, targetDir)
                "zip" -> extractZip(sourceFile, targetDir)
            }

            // 6. 写入 plugin.json 元信息
            val pluginInfo = buildPluginInfo(manifest, targetDir, sourceFile, fileExtension)
            saveManifest(targetDir, pluginInfo)

            // 7. 注册到 PluginManager
            pluginManager.registerPlugin(pluginInfo)

            Log.i(TAG, "Plugin installed: $pluginId v${pluginInfo.version}")
            PluginInstallResult.Success(pluginInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}", e)
            PluginInstallResult.Failed("安装失败: ${e.message}")
        }
    }

    // ========== APK 解析 ==========

    private fun parseApkManifest(apkFile: File): JSONObject? {
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_META_DATA
            ) ?: return null

            val entryClass = pi.applicationInfo?.metaData?.getString(PluginManager.META_ENTRY_CLASS)
                ?: pi.applicationInfo?.metaData?.getString("ai.openclaw.plugin.entry_class")
            val engineType = pi.applicationInfo?.metaData?.getString(PluginManager.META_ENGINE_TYPE)
                ?: pi.applicationInfo?.metaData?.getString("ai.openclaw.plugin.engine_type")

            JSONObject().apply {
                put("id", pi.packageName)
                put("name", pi.applicationInfo?.loadLabel(pm) ?: pi.packageName)
                put("version", pi.versionName ?: pi.longVersionCode.toString())
                put("engineType", engineType ?: "unknown")
                put("packageName", pi.packageName)
                put("entryClass", entryClass ?: "")
                put("description", pi.applicationInfo?.loadDescription(pm) ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK manifest", e)
            null
        }
    }

    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
            ) ?: return false

            val signatures = pi.signingInfo?.apkContentsSigners ?: return false
            for (sig in signatures) {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(sig.toByteArray())
                val hash = digest.digest().joinToString("") { "%02x".format(it) }

                if (hash.equals(PluginManager.AUTHORIZED_CERT_SHA256, ignoreCase = true)) {
                    return true
                }
            }
            // 开发阶段：如果未配置授权 SHA256，放行
            PluginManager.AUTHORIZED_CERT_SHA256 == "REPLACE_WITH_ACTUAL_SHA256"
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    private fun extractApk(apkFile: File, targetDir: File) {
        // 复制 APK 到插件目录（不提取，保留原始 APK 供运行时加载）
        val destApk = File(targetDir, "plugin.apk")
        apkFile.copyTo(destApk, overwrite = true)
    }

    // ========== ZIP 解析 ==========

    private fun parseZipManifest(zipFile: File): JSONObject? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_FILE) ?: return null
                val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                JSONObject(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ZIP manifest", e)
            null
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destFile = File(targetDir, entry.name)
                // 防止 zip slip 攻击
                if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip slip detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    // ========== 备份 & 回滚 ==========

    private fun backupPlugin(sourceDir: File, pluginId: String): Boolean {
        return try {
            val backupsDir = File(pluginsDir, ".backups").also { it.mkdirs() }
            val timestamp = System.currentTimeMillis()
            val backupDir = File(backupsDir, "${pluginId}_$timestamp")
            sourceDir.copyRecursively(backupDir, overwrite = true)
            Log.i(TAG, "Backed up $pluginId → ${backupDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed for $pluginId", e)
            false
        }
    }

    /**
     * 回滚到指定备份
     */
    suspend fun rollback(pluginId: String, backupTimestamp: Long): Boolean = withContext(Dispatchers.IO) {
        val backupDir = File(pluginsDir, ".backups/${pluginId}_$backupTimestamp")
        if (!backupDir.exists()) {
            Log.e(TAG, "Backup not found: ${backupDir.absolutePath}")
            return@withContext false
        }

        val targetDir = File(pluginsDir, pluginId)
        return@withContext try {
            targetDir.deleteRecursively()
            backupDir.copyRecursively(targetDir)
            val manifestFile = File(targetDir, MANIFEST_FILE)
            if (manifestFile.exists()) {
                val manifest = JSONObject(manifestFile.readText())
                val info = buildPluginInfo(manifest, targetDir, null, "rollback")
                pluginManager.registerPlugin(info)
            }
            Log.i(TAG, "Rolled back $pluginId to backup $backupTimestamp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed for $pluginId", e)
            false
        }
    }

    // ========== 辅助方法 ==========

    private fun buildPluginInfo(
        manifest: JSONObject,
        targetDir: File,
        sourceFile: File?,
        sourceType: String
    ): PluginInfo {
        return PluginInfo(
            id = manifest.getString("id"),
            name = manifest.optString("name", manifest.getString("id")),
            version = manifest.optString("version", "0.0.0"),
            author = manifest.optString("author", ""),
            description = manifest.optString("description", ""),
            iconPath = manifest.optString("icon")?.takeIf { it.isNotBlank() }
                ?.let { File(targetDir, it).takeIf { f -> f.exists() }?.absolutePath },
            enabled = true,
            installedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            packageName = manifest.optString("packageName", null),
            engineType = manifest.optString("engineType", null),
            sourceFile = sourceFile?.absolutePath
        )
    }

    private fun saveManifest(targetDir: File, info: PluginInfo) {
        val manifestFile = File(targetDir, MANIFEST_FILE)
        val json = JSONObject().apply {
            put("id", info.id)
            put("name", info.name)
            put("version", info.version)
            put("author", info.author)
            put("description", info.description)
            put("engineType", info.engineType ?: "")
            put("packageName", info.packageName ?: "")
        }
        manifestFile.writeText(json.toString(2))
    }

    private fun loadExistingPluginInfo(pluginDir: File, pluginId: String): PluginInfo? {
        return try {
            val manifestFile = File(pluginDir, MANIFEST_FILE)
            if (!manifestFile.exists()) return null
            val manifest = JSONObject(manifestFile.readText())
            buildPluginInfo(manifest, pluginDir, null, "existing")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load existing plugin info: $pluginId", e)
            null
        }
    }
}
