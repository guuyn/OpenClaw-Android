package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * DeviceSkill — 设备基本信息、运行状态、健康检查、运行应用列表。
 *
 * 全部使用 Android SDK 内置 API，无需额外权限（除已声明的 ACCESS_NETWORK_STATE）。
 */
class DeviceSkill(
    private val context: Context
) : Skill {
    override val id = "device"
    override val name = "设备信息"
    override val description = "获取设备基本信息、运行状态、健康评分和运行中的应用"
    override val version = "1.0.0"

    override val instructions = """
# Device Skill

设备信息查询、运行状态监控、健康检查和运行应用列表。

## 可用工具
- `info` — 设备基本信息（型号、厂商、Android 版本、屏幕分辨率等）
- `status` — 设备运行状态（电池、存储、内存、网络、运行时间）
- `health` — 设备健康检查 + 综合评分
- `running_apps` — 当前运行的应用列表
""".trimIndent()

    override val tools: List<SkillTool> = listOf(
        InfoTool(),
        StatusTool(),
        HealthTool(),
        RunningAppsTool()
    )

    // ==================== device_info ====================

    private inner class InfoTool : SkillTool {
        override val name = "info"
        override val description = "获取设备基本信息。"
        override val parameters = emptyMap<String, SkillParam>()

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            return try {
                val dm = context.resources.displayMetrics
                val pm = context.packageManager

                val isEmulator = run {
                    Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.MODEL.contains("Emulator", ignoreCase = true) ||
                    Build.MODEL.contains("Android SDK", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
                    (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                    "google_sdk" == Build.PRODUCT ||
                    "sdk" == Build.PRODUCT ||
                    "sdk_google" == Build.PRODUCT ||
                    "sdk_x86" == Build.PRODUCT ||
                    "vbox86p" == Build.PRODUCT ||
                    "emu64x" == Build.PRODUCT
                }

                val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

                val result = buildString {
                    append("设备信息:\n")
                    append("型号: ${Build.MODEL}\n")
                    append("厂商: ${Build.MANUFACTURER}\n")
                    append("品牌: ${Build.BRAND}\n")
                    append("Android 版本: ${Build.VERSION.RELEASE}\n")
                    append("API Level: ${Build.VERSION.SDK_INT}\n")
                    append("Build ID: ${Build.DISPLAY}\n")
                    append("屏幕分辨率: ${dm.widthPixels}x${dm.heightPixels}\n")
                    append("屏幕密度: ${dm.densityDpi}dpi\n")
                    append("CPU 架构: $cpuAbi\n")
                    append("模拟器: $isEmulator\n")
                }

                SkillResult(true, result)
            } catch (e: Exception) {
                SkillResult(false, "", "获取设备信息失败: ${e.message}")
            }
        }
    }

    // ==================== device_status ====================

    private inner class StatusTool : SkillTool {
        override val name = "status"
        override val description = "获取设备当前运行状态（电池、存储、内存、网络、运行时间）。"
        override val parameters = emptyMap<String, SkillParam>()

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            return try {
                val batteryInfo = getBatteryInfo()
                val storageInfo = getStorageInfo()
                val ramInfo = getRamInfo()
                val networkInfo = getNetworkInfo()
                val uptime = SystemClock.elapsedRealtime() / 1000
                val timezone = java.util.TimeZone.getDefault().id
                val locale = java.util.Locale.getDefault().toString()

                val result = buildString {
                    append("设备状态:\n")
                    append("--- 电池 ---\n")
                    append("电量: ${batteryInfo.level}%\n")
                    append("充电中: ${batteryInfo.isCharging}\n")
                    append("温度: ${batteryInfo.temperature / 10.0}°C\n")
                    append("--- 存储 ---\n")
                    append("总空间: ${formatBytes(storageInfo.total)}\n")
                    append("可用空间: ${formatBytes(storageInfo.available)}\n")
                    append("使用率: ${storageInfo.usagePercent}%\n")
                    append("--- 内存 ---\n")
                    append("总内存: ${formatBytes(ramInfo.total)}\n")
                    append("可用内存: ${formatBytes(ramInfo.available)}\n")
                    append("使用率: ${ramInfo.usagePercent}%\n")
                    append("--- 网络 ---\n")
                    append("已连接: ${networkInfo.isConnected}\n")
                    append("类型: ${networkInfo.type}\n")
                    if (networkInfo.ssid != null) {
                        append("WiFi: ${networkInfo.ssid}\n")
                    }
                    append("--- 其他 ---\n")
                    append("运行时间: ${formatUptime(uptime)}\n")
                    append("时区: $timezone\n")
                    append("语言: $locale\n")
                }

                SkillResult(true, result)
            } catch (e: Exception) {
                SkillResult(false, "", "获取设备状态失败: ${e.message}")
            }
        }
    }

    // ==================== device_health ====================

    private inner class HealthTool : SkillTool {
        override val name = "health"
        override val description = "设备健康检查，返回综合健康评分和详细信息。"
        override val parameters = emptyMap<String, SkillParam>()

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            return try {
                val batteryInfo = getBatteryInfo()
                val storageInfo = getStorageInfo()
                val ramInfo = getRamInfo()
                val networkInfo = getNetworkInfo()

                // Calculate individual scores (0-100)
                val batteryScore = calculateBatteryScore(batteryInfo)
                val storageScore = calculateStorageScore(storageInfo)
                val ramScore = calculateRamScore(ramInfo)
                val networkScore = if (networkInfo.isConnected) 100 else 20
                val tempScore = calculateTempScore(batteryInfo)

                // Weighted overall score
                val overallScore = (batteryScore * 0.25 +
                        storageScore * 0.25 +
                        ramScore * 0.2 +
                        networkScore * 0.2 +
                        tempScore * 0.1).toInt()

                // Determine health status
                val healthStatus = when {
                    overallScore >= 80 -> "good"
                    overallScore >= 50 -> "warning"
                    else -> "critical"
                }

                // Generate recommendations
                val recommendations = mutableListOf<String>()
                if (batteryInfo.level < 20) {
                    recommendations.add("电池电量低于 20%，建议充电")
                }
                if (storageInfo.usagePercent > 90) {
                    recommendations.add("存储空间不足 10%，建议清理文件")
                } else if (storageInfo.usagePercent > 80) {
                    recommendations.add("存储空间低于 20%，建议定期清理")
                }
                if (ramInfo.usagePercent > 85) {
                    recommendations.add("内存使用率较高，建议关闭不需要的应用")
                }
                if (batteryInfo.temperature > 400) {
                    recommendations.add("设备温度较高 (${batteryInfo.temperature / 10.0}°C)，建议降温")
                }
                if (!networkInfo.isConnected) {
                    recommendations.add("网络未连接")
                }

                val result = buildString {
                    append("设备健康检查:\n")
                    append("综合评分: $overallScore / 100\n")
                    append("健康状态: $healthStatus\n")
                    append("---\n")
                    append("电池: ${getHealthLabel(batteryScore)} (${batteryInfo.level}%)\n")
                    append("存储: ${getHealthLabel(storageScore)} (可用 ${storageInfo.usagePercent}%)\n")
                    append("内存: ${getHealthLabel(ramScore)} (可用 ${ramInfo.usagePercent}%)\n")
                    append("温度: ${getHealthLabel(tempScore)} (${batteryInfo.temperature / 10.0}°C)\n")
                    append("网络: ${getHealthLabel(networkScore)} (${if (networkInfo.isConnected) "已连接" else "未连接"})\n")
                    if (recommendations.isNotEmpty()) {
                        append("---\n")
                        append("建议:\n")
                        recommendations.forEachIndexed { index, rec ->
                            append("${index + 1}. $rec\n")
                        }
                    }
                }

                SkillResult(true, result)
            } catch (e: Exception) {
                SkillResult(false, "", "健康检查失败: ${e.message}")
            }
        }
    }

    // ==================== device_running_apps ====================

    private inner class RunningAppsTool : SkillTool {
        override val name = "running_apps"
        override val description = "获取当前运行的应用列表。"
        override val parameters = mapOf(
            "limit" to SkillParam(
                type = "number",
                description = "最大返回数量，默认 20",
                required = false,
                default = 20
            ),
            "user_only" to SkillParam(
                type = "boolean",
                description = "仅返回用户安装的应用，默认 true",
                required = false,
                default = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            return try {
                val limit = (params["limit"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 20
                val userOnly = params["user_only"] as? Boolean ?: true

                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val pm = context.packageManager

                val runningTasks = am.appTasks

                val result = StringBuilder()
                result.append("运行中的应用:\n")
                result.append("---\n")

                var count = 0
                for (taskInfo in runningTasks) {
                    if (count >= limit) break

                    val baseInfo = taskInfo.taskInfo.baseIntent
                    val packageName = baseInfo.component?.packageName
                        ?: taskInfo.taskInfo.baseActivity?.packageName
                        ?: continue

                    // Skip if user-only and this is a system app
                    if (userOnly) {
                        try {
                            val appInfo = pm.getApplicationInfo(packageName, 0)
                            if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                                continue
                            }
                        } catch (e: Exception) {
                            // Can't get app info, skip
                            continue
                        }
                    }

                    val label = try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName
                    }

                    val pid = taskInfo.taskInfo.id
                    result.append("- $label ($packageName) PID: $pid\n")
                    count++
                }

                if (count == 0) {
                    result.append("未检测到运行中的应用\n")
                } else {
                    result.append("---\n")
                    result.append("共显示 $count 个应用\n")
                }

                SkillResult(true, result.toString())
            } catch (e: Exception) {
                // Fallback: try getRunningAppProcesses
                try {
                    return getRunningAppsFallback(params)
                } catch (e2: Exception) {
                    SkillResult(false, "", "获取运行应用失败: ${e2.message}")
                }
            }
        }

        private suspend fun getRunningAppsFallback(params: Map<String, Any>): SkillResult {
            val limit = (params["limit"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 20
            val userOnly = params["user_only"] as? Boolean ?: true

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager

            val runningProcesses = am.runningAppProcesses
                ?: return SkillResult(false, "", "无法获取运行进程")

            val result = StringBuilder()
            result.append("运行中的应用:\n")
            result.append("---\n")

            var count = 0
            // Sort by importance (foreground first)
            val sorted = runningProcesses.sortedBy { it.importance }
            for (proc in sorted) {
                if (count >= limit) break
                if (proc.pkgList.isEmpty()) continue

                val packageName = proc.pkgList.firstOrNull() ?: continue

                if (userOnly) {
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                            continue
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }

                val label = try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }

                val importance = when (proc.importance) {
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "可感知"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "不可保存"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "服务"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "缓存"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "已隐藏"
                    else -> "未知(${proc.importance})"
                }

                result.append("- $label ($packageName) PID: ${proc.pid} 状态: $importance\n")
                count++
            }

            if (count == 0) {
                result.append("未检测到运行中的应用\n")
            } else {
                result.append("---\n")
                result.append("共显示 $count 个应用\n")
            }

            return SkillResult(true, result.toString())
        }
    }

    // ==================== Data helpers ====================

    data class BatteryInfo(
        val level: Int = 0,
        val isCharging: Boolean = false,
        val temperature: Int = 0 // in tenths of a degree Celsius
    )

    data class StorageInfo(
        val total: Long = 0L,
        val available: Long = 0L,
        val usagePercent: Int = 0
    )

    data class RamInfo(
        val total: Long = 0L,
        val available: Long = 0L,
        val usagePercent: Int = 0
    )

    data class NetworkInfo(
        val isConnected: Boolean = false,
        val type: String = "unknown",
        val ssid: String? = null
    )

    private fun getBatteryInfo(): BatteryInfo {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        // Temperature from intent
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        return BatteryInfo(level, isCharging, temperature)
    }

    private fun getStorageInfo(): StorageInfo {
        return try {
            // Internal storage
            val path = context.filesDir
            val statFs = StatFs(path.path)
            val total = statFs.blockCountLong * statFs.blockSizeLong
            val available = statFs.availableBlocksLong * statFs.blockSizeLong
            val usagePercent = if (total > 0) ((total - available) * 100 / total).toInt() else 0

            StorageInfo(total, available, usagePercent)
        } catch (e: Exception) {
            Log.w("DeviceSkill", "Failed to get storage info: ${e.message}")
            StorageInfo()
        }
    }

    private fun getRamInfo(): RamInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val total = memInfo.totalMem
        val available = memInfo.availMem
        val usagePercent = if (total > 0) ((total - available) * 100 / total).toInt() else 0

        return RamInfo(total, available, usagePercent)
    }

    private fun getNetworkInfo(): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as?
        android.net.ConnectivityManager ?: return NetworkInfo()

        val activeNetwork = cm.activeNetworkInfo
        val isConnected = activeNetwork?.isConnected == true

        val type = when (activeNetwork?.type) {
            android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
            android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
            android.net.ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            android.net.ConnectivityManager.TYPE_VPN -> "vpn"
            else -> if (isConnected) "other" else "none"
        }

        // Try to get WiFi SSID
        val ssid = try {
            if (type == "wifi") {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                wifiInfo?.ssid?.removeSurrounding("\"")
            } else null
        } catch (e: Exception) {
            null
        }

        return NetworkInfo(isConnected, type, ssid)
    }

    // ==================== Health scoring ====================

    private fun calculateBatteryScore(info: BatteryInfo): Int {
        return when {
            info.level >= 80 -> 100
            info.level >= 50 -> 80
            info.level >= 20 -> 60
            info.level >= 10 -> 40
            else -> 20
        }
    }

    private fun calculateStorageScore(info: StorageInfo): Int {
        return when {
            info.usagePercent <= 50 -> 100
            info.usagePercent <= 70 -> 80
            info.usagePercent <= 80 -> 60
            info.usagePercent <= 90 -> 40
            else -> 20
        }
    }

    private fun calculateRamScore(info: RamInfo): Int {
        return when {
            info.usagePercent <= 50 -> 100
            info.usagePercent <= 70 -> 80
            info.usagePercent <= 85 -> 60
            info.usagePercent <= 95 -> 40
            else -> 20
        }
    }

    private fun calculateTempScore(info: BatteryInfo): Int {
        val temp = info.temperature / 10.0
        return when {
            temp < 30 -> 80
            temp in 30.0..38.0 -> 100
            temp in 38.1..42.0 -> 60
            temp in 42.1..45.0 -> 40
            else -> 20
        }
    }

    private fun getHealthLabel(score: Int): String {
        return when {
            score >= 80 -> "良好"
            score >= 50 -> "一般"
            else -> "需注意"
        }
    }

    // ==================== Formatting helpers ====================

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes < 1024L * 1024 * 1024 * 1024 -> String.format(
            "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0)
        )

        else -> String.format("%.2f TB", bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0))
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return if (days > 0) "${days}天 ${hours}小时 ${minutes}分钟"
        else if (hours > 0) "${hours}小时 ${minutes}分钟"
        else "${minutes}分钟"
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        Log.i("DeviceSkill", "Initialized")
    }

    override fun cleanup() {}
}
