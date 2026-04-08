package ai.openclaw.android.skill.builtin

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import ai.openclaw.android.skill.*

class SettingsSkill : Skill {
    override val id = "settings"
    override val name = "系统设置"
    override val description = "打开系统设置页面和控制设备功能"
    override val version = "1.0.0"

    override val instructions = """
# Settings Skill

帮助用户打开系统设置和控制设备功能。

## 用法
- 用户说"打开设置"时，调用 open_settings 工具
- 用户说"打开 WiFi 设置"时，调用 open_settings，传入 type="wifi"
- 用户说"关闭蓝牙"时，调用 toggle_bluetooth 工具，传入 value=false
- 用户说"调高音量"时，调用 volume 工具

## 注意
- 部分功能（WiFi/蓝牙开关）在 Android 13+ 上可能受限，会改为打开对应设置页面
"""

    private var context: Context? = null

    override val tools: List<SkillTool> = listOf(
        OpenSettingsTool(),
        ToggleBluetoothTool(),
        VolumeTool()
    )

    private inner class OpenSettingsTool : SkillTool {
        override val name = "open_settings"
        override val description = "打开系统设置页面，支持 wifi/bluetooth/sound/display/about 等类型"
        override val parameters = mapOf(
            "type" to SkillParam(
                type = "string",
                description = "设置类型: wifi/bluetooth/sound/display/about/location/storage/battery。不传则打开主设置页",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val ctx = context ?: return SkillResult(false, "", "Context not initialized")
            val type = params["type"] as? String

            try {
                val intent = when (type?.lowercase()) {
                    null, "" -> Intent(Settings.ACTION_SETTINGS)
                    "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                    "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "about" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                    "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
                    "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
                    "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
                    "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    else -> Intent(Settings.ACTION_SETTINGS)
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                val label = if (type.isNullOrBlank()) "系统设置" else "$type 设置"
                return SkillResult(true, "已打开$label页面")
            } catch (e: Exception) {
                return SkillResult(false, "", "打开设置失败: ${e.message}")
            }
        }
    }

    private inner class ToggleBluetoothTool : SkillTool {
        override val name = "toggle_bluetooth"
        override val description = "开启或关闭蓝牙"
        override val parameters = mapOf(
            "value" to SkillParam(
                type = "boolean",
                description = "true 开启蓝牙，false 关闭蓝牙",
                required = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val ctx = context ?: return SkillResult(false, "", "Context not initialized")
            val enable = params["value"] as? Boolean ?: true

            try {
                val bluetoothManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter

                if (adapter == null) {
                    return SkillResult(false, "", "设备不支持蓝牙")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ cannot toggle bluetooth directly from third-party apps
                    // Open bluetooth settings instead
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    return SkillResult(
                        true,
                        "Android 13+ 无法直接${if (enable) "开启" else "关闭"}蓝牙，已打开蓝牙设置页面，请手动操作"
                    )
                }

                @Suppress("DEPRECATION")
                val success = if (enable) adapter.enable() else adapter.disable()

                return if (success) {
                    SkillResult(true, "已${if (enable) "开启" else "关闭"}蓝牙")
                } else {
                    // Fallback to settings page
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    SkillResult(true, "已打开蓝牙设置页面，请手动${if (enable) "开启" else "关闭"}蓝牙")
                }
            } catch (e: SecurityException) {
                // Fallback to settings page on permission denial
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return SkillResult(true, "权限不足，已打开蓝牙设置页面，请手动操作")
            } catch (e: Exception) {
                return SkillResult(false, "", "操作蓝牙失败: ${e.message}")
            }
        }
    }

    private inner class VolumeTool : SkillTool {
        override val name = "volume"
        override val description = "控制音量：调高、调低、静音、设置具体音量"
        override val parameters = mapOf(
            "action" to SkillParam(
                type = "string",
                description = "操作: up(调高)/down(调低)/mute(静音)/unmute(取消静音)/set(设置具体值)",
                required = true
            ),
            "level" to SkillParam(
                type = "number",
                description = "音量级别 (0-15)，仅在 action=set 时使用",
                required = false
            ),
            "stream" to SkillParam(
                type = "string",
                description = "音量流: ring(铃声)/media(媒体)/alarm(闹钟)/notification(通知)，默认 media",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val ctx = context ?: return SkillResult(false, "", "Context not initialized")
            val action = params["action"] as? String ?: return SkillResult(false, "", "缺少 action 参数")

            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                val streamType = when ((params["stream"] as? String)?.lowercase()) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC // media
                }

                when (action.lowercase()) {
                    "up" -> {
                        am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        return SkillResult(true, "音量已调高")
                    }
                    "down" -> {
                        am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        return SkillResult(true, "音量已调低")
                    }
                    "mute" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            am.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        } else {
                            am.setStreamMute(streamType, true)
                        }
                        return SkillResult(true, "已静音")
                    }
                    "unmute" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            am.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                        } else {
                            am.setStreamMute(streamType, false)
                        }
                        return SkillResult(true, "已取消静音")
                    }
                    "set" -> {
                        val level = (params["level"] as? Number)?.toInt()
                            ?: return SkillResult(false, "", "设置具体音量需要 level 参数")
                        val max = am.getStreamMaxVolume(streamType)
                        val clampedLevel = level.coerceIn(0, max)
                        am.setStreamVolume(streamType, clampedLevel, AudioManager.FLAG_SHOW_UI)
                        return SkillResult(true, "音量已设置为 $clampedLevel (最大 $max)")
                    }
                    else -> return SkillResult(false, "", "不支持的操作: $action")
                }
            } catch (e: Exception) {
                return SkillResult(false, "", "控制音量失败: ${e.message}")
            }
        }
    }

    override fun initialize(context: SkillContext) {
        this.context = context.applicationContext
    }

    override fun cleanup() {
        context = null
    }
}
