package ai.openclaw.android.voice.tts

/**
 * Text-to-speech engine interface.
 */
interface TextToSpeechEngine {
    /**
     * Speak the given text. Suspends until utterance is complete or [stop] is called.
     */
    suspend fun speak(text: String)

    /** Stop current speech immediately. */
    fun stop()

    /** Whether the engine is currently speaking. */
    fun isSpeaking(): Boolean

    /** Change the voice profile used for subsequent speech. */
    fun setVoice(voice: VoiceProfile)
}

/**
 * Voice configuration for TTS.
 */
data class VoiceProfile(
    /** Language tag, e.g. "zh-CN", "en-US". */
    val language: String = "zh-CN",
    /** Speech rate: 0.5 (slow) to 2.0 (fast), default 1.0. */
    val speed: Float = 1.0f,
    /** Pitch: 0.5 (low) to 2.0 (high), default 1.0. */
    val pitch: Float = 1.0f
)
