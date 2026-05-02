package ai.openclaw.android.domain

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import ai.openclaw.android.voice.stt.isSpeechRecognitionAvailable

/**
 * DeviceCapabilities — runtime detection of device features.
 *
 * Used by ResponseRouter to decide how to deliver LLM responses
 * (plain text, voice, rich text, or mixed), and injected into the
 * system prompt so the LLM can choose its response format.
 */
data class DeviceCapabilities(
    val hasScreen: Boolean = true,          // Device has a screen
    val hasTts: Boolean,                    // Text-to-Speech available
    val hasStt: Boolean,                    // Speech-to-Text available
    val hasRichText: Boolean = true,        // Can render rich text / A2UI cards
    val hasNetwork: Boolean,                // Network connectivity
    val isInteractive: Boolean = true,      // Supports user interaction
    val isAudioMuted: Boolean = false       // Audio is muted
) {

    /**
     * Compute the device profile string for the system prompt.
     *
     * - FULL:     screen + TTS + STT + rich text + network
     * - MIXED:    screen + TTS + network (no STT or no rich text)
     * - SCREEN_ONLY: screen only, no TTS/STT
     * - VOICE_ONLY: TTS but no screen (headless speaker scenario)
     * - MINIMAL:  barely anything available
     */
    val profile: String
        get() = when {
            hasScreen && hasTts && hasStt && hasRichText && hasNetwork -> "FULL"
            hasScreen && hasTts && hasNetwork -> "MIXED"
            hasScreen && hasRichText && hasNetwork -> "SCREEN_ONLY"
            !hasScreen && hasTts && hasNetwork -> "VOICE_ONLY"
            hasScreen && hasNetwork -> "SCREEN_ONLY"
            hasTts && !isAudioMuted -> "VOICE_ONLY"
            else -> "MINIMAL"
        }

    /**
     * Generate a human-readable prompt section for the LLM system prompt.
     */
    fun toPromptSection(): String = buildString {
        appendLine("[设备能力]")
        appendLine("- 屏幕: ${icon(hasScreen)}")
        appendLine("- 语音合成(TTS): ${icon(hasTts)}")
        appendLine("- 语音识别(STT): ${icon(hasStt)}")
        appendLine("- 富文本渲染: ${icon(hasRichText)}")
        appendLine("- 网络连接: ${icon(hasNetwork)}")
        appendLine("- 用户交互: ${icon(isInteractive)}")
        appendLine("- 静音模式: ${if (isAudioMuted) "🔇 静音" else "🔊 正常"}")
        appendLine()
        appendLine("你的设备 profile 是: $profile")
        appendLine()
        appendLine("每次回复必须输出 JSON 格式：")
        appendLine("""{"type": "TEXT" | "VOICE" | "BOTH", "voice_text": "简短的语音播报内容（1-2句话，适合朗读）", "rich_content": {"type": "list" | "card" | "code" | null, "data": {...}}, "fallback_text": "纯文本版本的完整回复（必须包含所有信息）"}""")
        appendLine()
        appendLine("决策规则：")
        appendLine("- 问候、确认、简短回答、情感回应 → type: \"VOICE\"（语音播报）")
        appendLine("- 列表、表格、代码、链接等结构化信息 → type: \"TEXT\"")
        appendLine("- 既有语音摘要又有详情 → type: \"BOTH\"")
        appendLine("- voice_text 是这段回复的语音播报版本，要自然流畅，1-2句话")
        appendLine("- 即使 type 是 TEXT 或 BOTH，也尽量提供 voice_text（用户可手动播放）")
        appendLine("- fallback_text 必须包含完整信息，是纯文本格式")
    }

    private fun icon(enabled: Boolean) = if (enabled) "✅ 支持" else "❌ 不支持"

    companion object {
        /**
         * Detect device capabilities from Android Context.
         *
         * This performs lightweight runtime checks:
         * - TTS: whether TextToSpeech engine is available
         * - STT: whether speech recognition intent is available
         * - Network: whether ConnectivityManager reports active network
         */
        fun fromContext(context: Context): DeviceCapabilities {
            val ttsAvailable = try {
                val tts = TextToSpeech(context) { /* init callback, ignored */ }
                tts.shutdown()
                true
            } catch (_: Exception) {
                false
            }

            val sttAvailable = isSpeechRecognitionAvailable(context)

            val hasNetwork = try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (_: Exception) {
                true // assume available if we can't check
            }

            return DeviceCapabilities(
                hasTts = ttsAvailable,
                hasStt = sttAvailable,
                hasNetwork = hasNetwork
            )
        }
    }
}
