package ai.openclaw.android.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android native SpeechRecognizer implementation.
 *
 * Must be created on the main thread (SpeechRecognizer requirement).
 * Each call to [startListening] creates a fresh SpeechRecognizer instance
 * to avoid state leaks between sessions.
 */
class AndroidSpeechRecognizer(
    private val context: Context
) : SpeechToTextEngine {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override suspend fun startListening(): Flow<SttResult> = channelFlow {
        // Must create SpeechRecognizer on the main looper thread.
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        listening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                if (text.isNotBlank()) {
                    trySend(SttResult(text = text, isFinal = false))
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val confidence = confidences?.firstOrNull() ?: -1f
                if (text.isNotBlank()) {
                    trySend(SttResult(text = text, isFinal = true, confidence = confidence))
                }
                listening = false
                close()
            }

            override fun onError(error: Int) {
                listening = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Silent / no speech — just close the flow gracefully.
                    close()
                } else {
                    close(IllegalStateException("SpeechRecognizer error: $error (${errorCodeLabel(error)})"))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)

        awaitClose {
            sr.destroy()
            if (recognizer == sr) recognizer = null
            listening = false
        }
    }

    override fun stopListening() {
        listening = false
        recognizer?.stopListening()
    }

    override fun isListening(): Boolean = listening

    private fun errorCodeLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($code)"
    }
}
