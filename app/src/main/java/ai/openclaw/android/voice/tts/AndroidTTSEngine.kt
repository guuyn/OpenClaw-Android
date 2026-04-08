package ai.openclaw.android.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android native TextToSpeech implementation.
 *
 * Call [init] once after construction (e.g. in a coroutine) to await engine readiness.
 */
class AndroidTTSEngine(
    context: Context
) : TextToSpeechEngine {

    private var currentVoice: VoiceProfile = VoiceProfile()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            _ready = true
            applyVoiceProfile(currentVoice)
        }
        initCont?.let {
            initCont = null
            if (status == TextToSpeech.SUCCESS) it.resume(Unit)
            else it.resumeWithException(IllegalStateException("TTS init failed: $status"))
        }
    }

    @Volatile private var _ready = false
    private var initCont: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    val ready: Boolean get() = _ready

    /** Wait for the TTS engine to initialize. Safe to call multiple times. */
    suspend fun init() {
        if (_ready) return
        suspendCancellableCoroutine { cont ->
            initCont = cont
            // If init already finished before we suspended, resolve immediately.
            if (_ready) {
                initCont = null
                cont.resume(Unit)
            }
        }
    }

    override suspend fun speak(text: String) {
        check(_ready) { "TTS engine not initialized — call init() first" }
        tts.stop()

        suspendCancellableCoroutine { cont ->
            val utteranceId = "utt_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() { tts.stop() }
    override fun isSpeaking(): Boolean = tts.isSpeaking

    override fun setVoice(voice: VoiceProfile) {
        currentVoice = voice
        if (_ready) applyVoiceProfile(voice)
    }

    private fun applyVoiceProfile(voice: VoiceProfile) {
        val locale = Locale.forLanguageTag(voice.language.replace('_', '-'))
        tts.language = locale
        tts.setSpeechRate(voice.speed)
        tts.setPitch(voice.pitch)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        _ready = false
    }
}
