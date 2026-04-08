package ai.openclaw.android.voice

import android.Manifest
import android.content.Context
import ai.openclaw.android.voice.stt.AndroidSpeechRecognizer
import ai.openclaw.android.voice.stt.SpeechToTextEngine
import ai.openclaw.android.voice.tts.AndroidTTSEngine
import ai.openclaw.android.voice.tts.TextToSpeechEngine
import ai.openclaw.android.voice.tts.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified entry point for voice interaction.
 *
 * Manages the lifecycle of STT/TTS engines and provides a simple API
 * for the UI layer to trigger voice sessions.
 *
 * Usage:
 * ```kotlin
 * val voiceManager = VoiceInteractionManager(context)
 * voiceManager.initialize()  // call once, e.g. in LaunchedEffect
 *
 * // Start a voice round-trip:
 * voiceManager.startSession { transcript ->
 *     // Process transcript with your LLM / agent
 *     agentSession.handleMessage(transcript)
 * }
 *
 * // Observe state in Compose:
 * val state by voiceManager.sessionState.collectAsState()
 * ```
 */
class VoiceInteractionManager(
    context: Context,
    private val sttEngine: SpeechToTextEngine = AndroidSpeechRecognizer(context),
    private val ttsEngine: AndroidTTSEngine = AndroidTTSEngine(context),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) : TextToSpeechEngine by ttsEngine {

    private val _sessionState = MutableStateFlow(VoiceState.Idle)
    val sessionState: StateFlow<VoiceState> = _sessionState.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var sessionJob: Job? = null
    private val appContext = context.applicationContext

    /**
     * Initialize STT/TTS engines. Must be called once before any voice session.
     * Checks RECORD_AUDIO permission before proceeding.
     */
    suspend fun initialize() {
        ttsEngine.init()
        _isInitialized.value = true
    }

    /**
     * Check if RECORD_AUDIO permission is granted.
     */
    fun hasRecordAudioPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start a voice session: listen → invoke [onTranscript] → speak the response.
     *
     * [onTranscript] receives the final recognized text and must return the response
     * text to be spoken. Return null or empty string to skip TTS.
     */
    fun startSession(onTranscript: suspend (String) -> String?) {
        if (sessionJob?.isActive == true) return
        if (!hasRecordAudioPermission()) {
            _sessionState.value = VoiceState.Idle
            return
        }

        sessionJob = coroutineScope.launch {
            val session = VoiceSession()
            observeSession(session)

            try {
                // --- LISTENING ---
                session.transitionToListening()
                val finalText = StringBuilder()
                sttEngine.startListening().collect { result ->
                    session.updateTranscript(result.text)
                    if (result.isFinal) {
                        finalText.clear()
                        finalText.append(result.text)
                    }
                }
                val userText = finalText.toString().trim()
                if (userText.isBlank()) {
                    session.transitionToIdle()
                    return@launch
                }

                // --- PROCESSING ---
                session.transitionToProcessing()
                val response = onTranscript(userText)
                if (response.isNullOrBlank()) {
                    session.transitionToIdle()
                    return@launch
                }

                // --- SPEAKING ---
                session.transitionToSpeaking(response)
                ttsEngine.speak(response)

                // --- DONE ---
                session.transitionToIdle()
            } catch (e: Exception) {
                session.setError(e)
            }
        }
    }

    /** Cancel the current voice session. */
    fun cancelSession() {
        sttEngine.stopListening()
        ttsEngine.stop()
        sessionJob?.cancel()
        sessionJob = null
        _sessionState.value = VoiceState.Idle
        _transcript.value = ""
    }

    /** Release all resources. Call when the host (Activity/Service) is destroyed. */
    fun destroy() {
        cancelSession()
        ttsEngine.shutdown()
        _isInitialized.value = false
    }

    private fun observeSession(session: VoiceSession) {
        coroutineScope.launch {
            session.state.collect { _sessionState.value = it }
        }
        coroutineScope.launch {
            session.transcript.collect { _transcript.value = it }
        }
    }
}
