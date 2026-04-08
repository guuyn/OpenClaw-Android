package ai.openclaw.android.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine for a single voice interaction cycle.
 *
 * ```
 * Idle → Listening → Processing → Speaking → Idle
 *   ↑                                          │
 *   └──────────────────────────────────────────┘
 * ```
 */
enum class VoiceState {
    Idle,
    Listening,
    Processing,
    Speaking
}

/**
 * Represents one complete voice interaction (listen → process → speak).
 */
class VoiceSession {

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Partial or final transcription text from the current listening phase. */
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** The response text being spoken (set during Speaking state). */
    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    val currentState: VoiceState get() = _state.value

    fun transitionToListening() {
        check(_state.value == VoiceState.Idle) { "Can only start listening from Idle, but was ${_state.value}" }
        _transcript.value = ""
        _responseText.value = ""
        _error.value = null
        _state.value = VoiceState.Listening
    }

    fun updateTranscript(text: String) {
        check(_state.value == VoiceState.Listening) { "Can only update transcript while Listening" }
        _transcript.value = text
    }

    fun transitionToProcessing() {
        check(_state.value == VoiceState.Listening) { "Can only process after Listening, but was ${_state.value}" }
        _state.value = VoiceState.Processing
    }

    fun transitionToSpeaking(responseText: String) {
        check(_state.value == VoiceState.Processing) { "Can only speak after Processing, but was ${_state.value}" }
        _responseText.value = responseText
        _state.value = VoiceState.Speaking
    }

    /** Transition directly from Processing back to Idle (e.g. empty response). */
    fun transitionToIdle() {
        _state.value = VoiceState.Idle
    }

    fun setError(throwable: Throwable) {
        _error.value = throwable
        _state.value = VoiceState.Idle
    }
}
