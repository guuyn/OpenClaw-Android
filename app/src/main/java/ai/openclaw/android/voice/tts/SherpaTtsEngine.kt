package ai.openclaw.android.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Sherpa-ONNX based Text-to-Speech engine.
 * Uses VITS or Melo TTS models for Chinese speech synthesis.
 * All processing is done locally.
 */
class SherpaTtsEngine(
    private val context: Context
) : TextToSpeechEngine {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var currentVoice = VoiceProfile()
    private var _initialized = false
    @Volatile private var _isSpeaking = false
    @Volatile private var _stopped = false

    companion object {
        private const val TAG = "SherpaTtsEngine"
    }

    @Synchronized
    fun initialize(modelDirPath: String): Boolean {
        if (_initialized) return true
        val config = buildTtsConfig(modelDirPath) ?: return false
        return try {
            // When using absolute paths (SD card models), assetManager must be null
            // See: https://github.com/k2-fsa/sherpa-onnx/issues/2562
            tts = OfflineTts(null, config)
            initAudioTrack()
            _initialized = true
            Log.i(TAG, "Initialized from: $modelDirPath, sampleRate=${tts?.sampleRate()}, speakers=${tts?.numSpeakers()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OfflineTts", e)
            false
        }
    }

    fun hasModel(modelDirPath: String): Boolean {
        val f = { name: String -> File(modelDirPath, name).exists() }
        return f("model.onnx") && f("tokens.txt")
    }

    override suspend fun speak(text: String) {
        val t = tts ?: throw IllegalStateException("TTS engine not initialized")

        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                _stopped = false
                _isSpeaking = true

                audioTrack?.let { track ->
                    track.pause()
                    track.flush()
                    track.play()
                }

                val speed = currentVoice.speed
                val sample = t.generate(text, 0, speed)

                if (sample.samples.isNotEmpty()) {
                    playSamples(sample.samples)
                }

                _isSpeaking = false
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun stop() {
        _stopped = true
        _isSpeaking = false
        audioTrack?.let {
            try { it.pause(); it.flush() } catch (_: Exception) {}
        }
    }

    override fun isSpeaking(): Boolean = _isSpeaking

    override fun setVoice(voice: VoiceProfile) {
        currentVoice = voice
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        tts?.release()
        tts = null
        _initialized = false
    }

    private fun initAudioTrack() {
        val t = tts ?: return
        val sampleRate = t.sampleRate()
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        audioTrack = AudioTrack(
            attr, format, bufSize.coerceAtLeast(4096),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    private fun playSamples(samples: FloatArray) {
        val track = audioTrack ?: return
        var offset = 0
        val chunkSize = 4096
        while (offset < samples.size && !_stopped) {
            val remaining = samples.size - offset
            val toWrite = minOf(chunkSize, remaining)
            track.write(samples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
            offset += toWrite
        }
    }

    private fun buildTtsConfig(modelDir: String): OfflineTtsConfig? {
        val f = { name: String -> File(modelDir, name).exists() }
        val d = "$modelDir/"

        if (f("model.onnx") && f("tokens.txt")) {
            val lexicon = if (f("lexicon.txt")) "${d}lexicon.txt" else ""
            val dataDir = if (f("espeak-ng-data")) "$modelDir" else ""

            val vitsConfig = OfflineTtsVitsModelConfig()
            vitsConfig.model = "${d}model.onnx"
            vitsConfig.lexicon = lexicon
            vitsConfig.tokens = "${d}tokens.txt"
            vitsConfig.dataDir = dataDir

            val modelConfig = OfflineTtsModelConfig()
            modelConfig.vits = vitsConfig
            modelConfig.numThreads = 2
            modelConfig.debug = false
            modelConfig.provider = "cpu"

            val config = OfflineTtsConfig()
            config.model = modelConfig
            return config
        }

        if (f("model-steps-3.onnx") && f("vocos-22khz-univ.onnx")) {
            val lexicon = if (f("lexicon.txt")) "${d}lexicon.txt" else ""
            val dataDir = if (f("espeak-ng-data")) "$modelDir" else ""

            val matchaConfig = OfflineTtsMatchaModelConfig()
            matchaConfig.acousticModel = "${d}model-steps-3.onnx"
            matchaConfig.vocoder = "$modelDir/vocos-22khz-univ.onnx"
            matchaConfig.lexicon = lexicon
            matchaConfig.tokens = "${d}tokens.txt"
            matchaConfig.dataDir = dataDir

            val modelConfig = OfflineTtsModelConfig()
            modelConfig.matcha = matchaConfig
            modelConfig.numThreads = 2

            val config = OfflineTtsConfig()
            config.model = modelConfig
            return config
        }

        Log.e(TAG, "No supported TTS model found in: $modelDir")
        return null
    }
}
