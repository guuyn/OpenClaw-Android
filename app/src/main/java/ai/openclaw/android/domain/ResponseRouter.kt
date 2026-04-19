package ai.openclaw.android.domain

import android.util.Log

/**
 * ResponseRouter — routes an LLM AgentResponse to a Deliverable
 * based on the device's runtime capabilities.
 *
 * Implements automatic degradation: if the LLM requests a delivery mode
 * the device can't support, it falls back to the next best option.
 *
 * Decision Tree:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ TEXT intent:                                                 │
 * │   ✅ screen + richText + richContent != null → RichText      │
 * │   ✅ screen + richText                     → PlainText       │
 * │   ❌ no screen + TTS                       → Voice           │
 * │   ❌ fallback                              → PlainText       │
 * │                                                              │
 * │ VOICE intent:                                                │
 * │   ✅ TTS + not muted                       → Voice           │
 * │   ❌ fallback                              → PlainText       │
 * │                                                              │
 * │ BOTH intent:                                                 │
 * │   ✅ screen + TTS + not muted              → Mixed           │
 * │   ✅ screen + no TTS                       → Mixed(null, ..) │
 * │   ❌ no screen + TTS + not muted           → Voice           │
 * │   ❌ fallback                              → PlainText       │
 * └─────────────────────────────────────────────────────────────┘
 */
class ResponseRouter(
    private val capabilities: DeviceCapabilities
) {

    companion object {
        private const val TAG = "ResponseRouter"
    }

    fun route(response: AgentResponse): Deliverable {
        return when (response.type) {
            ResponseType.TEXT -> routeText(response)
            ResponseType.VOICE -> routeVoice(response)
            ResponseType.BOTH -> routeBoth(response)
        }
    }

    private fun routeText(response: AgentResponse): Deliverable {
        if (capabilities.hasScreen) {
            if (capabilities.hasRichText && response.richContent != null) {
                Log.d(TAG, "TEXT → RichText (richContent available)")
                return Deliverable.RichText(response.richContent)
            }
            Log.d(TAG, "TEXT → PlainText")
            return Deliverable.PlainText(response.fallbackText)
        }
        // No screen
        if (capabilities.hasTts && !capabilities.isAudioMuted) {
            Log.d(TAG, "TEXT → Voice (no screen, TTS fallback)")
            return Deliverable.Voice(response.voiceText ?: response.fallbackText)
        }
        Log.d(TAG, "TEXT → PlainText (last resort)")
        return Deliverable.PlainText(response.fallbackText)
    }

    private fun routeVoice(response: AgentResponse): Deliverable {
        if (capabilities.hasTts && !capabilities.isAudioMuted) {
            val text = response.voiceText ?: response.fallbackText
            Log.d(TAG, "VOICE → Voice")
            return Deliverable.Voice(text)
        }
        Log.d(TAG, "VOICE → PlainText (TTS unavailable)")
        return Deliverable.PlainText(response.fallbackText)
    }

    private fun routeBoth(response: AgentResponse): Deliverable {
        if (capabilities.hasScreen) {
            if (capabilities.hasTts && !capabilities.isAudioMuted) {
                Log.d(TAG, "BOTH → Mixed (voice + rich)")
                return Deliverable.Mixed(
                    voice = response.voiceText,
                    rich = response.richContent
                )
            }
            // Screen but no TTS
            Log.d(TAG, "BOTH → Mixed (text + rich, no TTS)")
            return Deliverable.Mixed(
                voice = null,
                rich = response.richContent
            )
        }
        // No screen
        if (capabilities.hasTts && !capabilities.isAudioMuted) {
            Log.d(TAG, "BOTH → Voice (no screen)")
            return Deliverable.Voice(response.voiceText ?: response.fallbackText)
        }
        Log.d(TAG, "BOTH → PlainText (last resort)")
        return Deliverable.PlainText(response.fallbackText)
    }
}

/**
 * Deliverable — the final form a response takes after routing.
 * The UI layer pattern-matches on this to decide how to render.
 */
sealed class Deliverable {
    /** Plain text message (fallback or simplest delivery) */
    data class PlainText(val text: String) : Deliverable()

    /** Voice-only delivery (speak the text) */
    data class Voice(val text: String) : Deliverable()

    /** Rich text / card display (no voice) */
    data class RichText(val content: RichContent) : Deliverable()

    /** Mixed delivery: voice + rich content */
    data class Mixed(val voice: String?, val rich: RichContent?) : Deliverable()
}
