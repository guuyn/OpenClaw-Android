package ai.openclaw.android.voice

import android.Manifest
import android.content.Context
import android.util.Log
import ai.openclaw.android.voice.stt.AndroidSpeechRecognizer
import ai.openclaw.android.voice.stt.SherpaSttEngine
import ai.openclaw.android.voice.stt.SherpaSttManager
import ai.openclaw.android.voice.stt.SpeechToTextEngine
import ai.openclaw.android.voice.stt.SttResult
import ai.openclaw.android.voice.stt.isSpeechRecognitionAvailable
import ai.openclaw.android.voice.tts.AndroidTTSEngine
import ai.openclaw.android.voice.tts.SherpaTtsEngine
import ai.openclaw.android.voice.tts.TextToSpeechEngine
import ai.openclaw.android.voice.tts.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified entry point for voice interaction.
 *
 * Press-and-hold pattern (like Feishu):
 * ```kotlin
 * // User presses mic button
 * voiceManager.startListening().collect { result ->
 *     // result.text has real-time transcript updates
 * }
 *
 * // User releases mic button
 * voiceManager.stopListening()
 *
 * // Get the final transcript
 * val text = voiceManager.finalTranscript.value
 * ```
 */
class VoiceInteractionManager(
    private val context: Context
) : TextToSpeechEngine {

    // --- STT ---
    private var sttEngine: SpeechToTextEngine? = null
    private var sttManager: SherpaSttManager? = null
    private var sherpaStt: SherpaSttEngine? = null
    private var androidStt: AndroidSpeechRecognizer? = null

    // --- TTS ---
    private var ttsEngine: TextToSpeechEngine? = null
    private var sherpaTts: SherpaTtsEngine? = null
    private var androidTts: AndroidTTSEngine? = null

    // --- State ---
    private val _sessionState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val sessionState: StateFlow<VoiceState> = _sessionState.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _finalTranscript = MutableStateFlow("")
    val finalTranscript: StateFlow<String> = _finalTranscript.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** Which STT engine is active. */
    val activeSttEngine: String get() = when (sttEngine) {
        is SherpaSttEngine -> "sherpa-onnx"
        is AndroidSpeechRecognizer -> "android"
        else -> "none"
    }

    /** Which TTS engine is active. */
    val activeTtsEngine: String get() = when (ttsEngine) {
        is SherpaTtsEngine -> "sherpa-onnx"
        is AndroidTTSEngine -> "android"
        else -> "none"
    }

    private var sessionJob: Job? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "VoiceInteractionMgr"
    }

    /**
     * Initialize STT/TTS engines. Must be called once before any voice session.
     */
    suspend fun initialize(
        sttModelPath: String? = null,
        ttsModelPath: String? = null
    ) {
        Log.i(TAG, "Initializing voice engines...")

        withContext(Dispatchers.IO) {
            // --- Initialize STT ---
            sttManager = SherpaSttManager.getInstance(appContext)
            sherpaStt = sttManager?.getEngine(sttModelPath)

            if (sherpaStt != null) {
                sttEngine = sherpaStt
                Log.i(TAG, "Using SherpaSttEngine for STT")
            } else if (isSpeechRecognitionAvailable(appContext)) {
                androidStt = AndroidSpeechRecognizer(appContext)
                sttEngine = androidStt
                Log.i(TAG, "Using AndroidSpeechRecognizer for STT (fallback)")
            } else {
                Log.w(TAG, "No STT engine available")
            }

            // --- Initialize TTS ---
            sherpaTts = SherpaTtsEngine(appContext)
            val ttsReady = ttsModelPath?.let { sherpaTts?.initialize(it) } == true

            if (ttsReady) {
                ttsEngine = sherpaTts
                Log.i(TAG, "Using SherpaTtsEngine for TTS")
            } else {
                androidTts = AndroidTTSEngine(appContext)
                try {
                    androidTts?.init()
                    ttsEngine = androidTts
                    Log.i(TAG, "Using AndroidTTSEngine for TTS (fallback)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize any TTS engine", e)
                }
            }

            _isInitialized.value = true
            Log.i(TAG, "Voice engines initialized: STT=$activeSttEngine, TTS=$activeTtsEngine")
        }
    }

    /** Check if RECORD_AUDIO permission is granted. */
    fun hasRecordAudioPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start listening for voice input.
     *
     * Returns a Flow that emits real-time transcript updates.
     * The flow completes when [stopListening] is called.
     */
    fun startListening(): Flow<SttResult> {
        val stt = sttEngine ?: run {
            Log.w(TAG, "No STT engine available")
            return kotlinx.coroutines.flow.emptyFlow()
        }

        _transcript.value = ""
        _finalTranscript.value = ""
        _sessionState.value = VoiceState.Listening

        return kotlinx.coroutines.flow.channelFlow {
            withContext(Dispatchers.IO) {
                stt.startListening().collect { result ->
                    _transcript.value = result.text
                    if (result.isFinal) {
                        _finalTranscript.value = result.text
                    }
                    trySend(result)
                }
            }
            _sessionState.value = VoiceState.Idle
        }
    }

    /**
     * Stop the current listening session.
     * The Flow returned by [startListening] will complete after this.
     */
    fun stopListening() {
        sttEngine?.stopListening()
        sessionJob?.cancel()
        sessionJob = null
    }

    /** Release all resources. Call when the host (Activity/Service) is destroyed. */
    fun destroy() {
        stopListening()
        sherpaStt?.release()
        sherpaTts?.release()
        androidTts?.shutdown()
        sttManager?.release()
        sttEngine = null
        ttsEngine = null
        _isInitialized.value = false
    }

    // --- TextToSpeechEngine delegation ---
    override suspend fun speak(text: String) {
        ttsEngine?.speak(text)
    }

    override fun stop() {
        (ttsEngine as? AndroidTTSEngine)?.stop()
        (ttsEngine as? SherpaTtsEngine)?.stop()
    }

    override fun isSpeaking(): Boolean {
        val androidSpeaking = (ttsEngine as? AndroidTTSEngine)?.isSpeaking() ?: false
        val sherpaSpeaking = (ttsEngine as? SherpaTtsEngine)?.isSpeaking() ?: false
        return androidSpeaking || sherpaSpeaking
    }

    override fun setVoice(voice: VoiceProfile) {
        ttsEngine?.setVoice(voice)
    }

    // --- Engine switching ---

    fun switchToSherpaStt(modelPath: String? = null): Boolean {
        val path = modelPath ?: SherpaSttManager.defaultModelPath(appContext)
        val manager = SherpaSttManager.getInstance(appContext)
        val engine = manager.getEngine(path)
        if (engine != null) {
            sttEngine = engine
            sherpaStt = engine
            Log.i(TAG, "Switched to SherpaSttEngine")
            return true
        }
        return false
    }

    fun switchToAndroidStt(): Boolean {
        if (!isSpeechRecognitionAvailable(appContext)) return false
        androidStt = AndroidSpeechRecognizer(appContext)
        sttEngine = androidStt
        Log.i(TAG, "Switched to AndroidSpeechRecognizer")
        return true
    }

    fun switchToSherpaTts(modelPath: String? = null): Boolean {
        val path = modelPath ?: defaultTtsModelPath()
        val engine = SherpaTtsEngine(appContext)
        if (engine.initialize(path)) {
            ttsEngine = engine
            sherpaTts = engine
            Log.i(TAG, "Switched to SherpaTtsEngine")
            return true
        }
        return false
    }

    fun switchToAndroidTts() {
        val engine = AndroidTTSEngine(appContext)
        coroutineScope.launch {
            try {
                engine.init()
                ttsEngine = engine
                androidTts = engine
                Log.i(TAG, "Switched to AndroidTTSEngine")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch to AndroidTTSEngine", e)
            }
        }
    }

    private fun defaultTtsModelPath(): String {
        val extDir = appContext.getExternalFilesDir(null)
        return if (extDir != null) {
            "${extDir.absolutePath}/models/tts"
        } else {
            "${appContext.filesDir.absolutePath}/models/tts"
        }
    }
}
