package ai.openclaw.android.voice.stt

import kotlinx.coroutines.flow.Flow

/**
 * Speech-to-text engine interface.
 * Implementations stream partial and final recognition results.
 */
/**
 * Check if speech recognition is available on this device.
 * Returns false when no recognition service is installed (common on Huawei without Google services).
 */
fun isSpeechRecognitionAvailable(context: android.content.Context): Boolean {
    return try {
        android.speech.SpeechRecognizer.isRecognitionAvailable(context)
    } catch (e: Exception) {
        false
    }
}

interface SpeechToTextEngine {
    /**
     * Start listening and stream recognition results.
     * Emits partial results during recognition and a final result when done.
     * The flow completes when recognition ends (by timeout, silence, or [stopListening]).
     */
    suspend fun startListening(): Flow<SttResult>

    /** Stop the current recognition session. */
    fun stopListening()

    /** Whether the engine is currently listening. */
    fun isListening(): Boolean
}

/**
 * A single recognition result from the STT engine.
 */
data class SttResult(
    /** Recognized text so far (may be partial). */
    val text: String,
    /** True if this is the final stable result for this utterance. */
    val isFinal: Boolean,
    /** Confidence score 0..1, or -1 if unavailable. */
    val confidence: Float = -1f
)
