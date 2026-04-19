package ai.openclaw.android.voice.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-ONNX based Speech-to-Text engine.
 *
 * Uses streaming paraformer or zipformer models for Chinese speech recognition.
 * All processing is done locally — no network or Google services required.
 */
class SherpaSttEngine(
    private val context: Context
) : SpeechToTextEngine {

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var _initialized = false
    private val _listening = AtomicBoolean(false)

    companion object {
        private const val TAG = "SherpaSttEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Synchronized
    fun initialize(modelDirPath: String): Boolean {
        if (_initialized) return true
        val config = buildRecognizerConfig(modelDirPath) ?: return false
        return try {
            // When using absolute paths (SD card models), assetManager must be null
            // See: https://github.com/k2-fsa/sherpa-onnx/issues/2562
            recognizer = OnlineRecognizer(null, config)
            _initialized = true
            Log.i(TAG, "Initialized from: $modelDirPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OnlineRecognizer", e)
            false
        }
    }

    fun hasModel(modelDirPath: String): Boolean = detectModelType(modelDirPath) != null

    override suspend fun startListening(): Flow<SttResult> = channelFlow {
        val rec = recognizer ?: run {
            close(IllegalStateException("SherpaSttEngine not initialized"))
            return@channelFlow
        }
        if (!prepareAudioRecord()) {
            close(IllegalStateException("Failed to initialize AudioRecord"))
            return@channelFlow
        }

        val record = audioRecord ?: return@channelFlow
        record.startRecording()
        _listening.set(true)

        val stream = rec.createStream()
        val bufferSize = (0.1f * SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)
        var lastText = ""

        try {
            while (_listening.get()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    val samples = FloatArray(n) { i -> buffer[i] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)

                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }

                    val isEndpoint = rec.isEndpoint(stream)
                    val text = rec.getResult(stream).text

                    if (text.isNotBlank() && text != lastText) {
                        trySend(SttResult(text = text, isFinal = false))
                        lastText = text
                    }

                    if (isEndpoint) {
                        rec.reset(stream)
                        if (text.isNotBlank()) {
                            trySend(SttResult(text = text, isFinal = true))
                            lastText = ""
                        }
                    }
                }
            }

            val finalText = rec.getResult(stream).text
            if (finalText.isNotBlank()) {
                trySend(SttResult(text = finalText, isFinal = true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error", e)
            close(e)
        } finally {
            stream.release()
            releaseAudioRecord()
            _listening.set(false)
        }

        awaitClose {
            releaseAudioRecord()
            _listening.set(false)
        }
    }

    override fun stopListening() {
        _listening.set(false)
        releaseAudioRecord()
    }

    override fun isListening(): Boolean = _listening.get()

    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        _initialized = false
    }

    private fun prepareAudioRecord(): Boolean {
        val numBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (numBytes <= 0) return false
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, numBytes * 2
        )
        return audioRecord?.state == AudioRecord.STATE_INITIALIZED
    }

    private fun releaseAudioRecord() {
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioRecord = null
    }

    private fun buildRecognizerConfig(modelDir: String): OnlineRecognizerConfig? {
        val type = detectModelType(modelDir) ?: return null
        val d = "$modelDir/"

        val featConfig = FeatureConfig(SAMPLE_RATE, 80)
        val endpointConfig = EndpointConfig(
            EndpointRule(false, 2.4f, 0.0f),
            EndpointRule(true, 1.4f, 0.0f),
            EndpointRule(false, 0.0f, 20.0f)
        )

        val modelConfig = when (type) {
            ModelType.ZipformerZh14M -> {
                val mc = OnlineModelConfig()
                mc.transducer = OnlineTransducerModelConfig(
                    "${d}encoder-epoch-99-avg-1.int8.onnx",
                    "${d}decoder-epoch-99-avg-1.onnx",
                    "${d}joiner-epoch-99-avg-1.int8.onnx"
                )
                mc.tokens = "${d}tokens.txt"
                mc.modelType = "zipformer"
                mc.numThreads = 2
                mc
            }
            ModelType.StreamingParaformerBilingual -> {
                val mc = OnlineModelConfig()
                mc.paraformer = OnlineParaformerModelConfig(
                    "${d}encoder.int8.onnx",
                    "${d}decoder.int8.onnx"
                )
                mc.tokens = "${d}tokens.txt"
                mc.modelType = "paraformer"
                mc.numThreads = 2
                mc
            }
            ModelType.ZipformerSmallZh -> {
                val mc = OnlineModelConfig()
                mc.transducer = OnlineTransducerModelConfig(
                    "${d}encoder.int8.onnx",
                    "${d}decoder.onnx",
                    "${d}joiner.int8.onnx"
                )
                mc.tokens = "${d}tokens.txt"
                mc.modelType = "zipformer2"
                mc.numThreads = 2
                mc
            }
            ModelType.ZipformerSmallCtcZh -> {
                val mc = OnlineModelConfig()
                mc.zipformer2Ctc = OnlineZipformer2CtcModelConfig("${d}model.int8.onnx")
                mc.tokens = "${d}tokens.txt"
                mc.modelType = "zipformer2_ctc"
                mc.numThreads = 2
                mc
            }
        }

        val config = OnlineRecognizerConfig()
        config.featConfig = featConfig
        config.modelConfig = modelConfig
        config.decodingMethod = "greedy_search"
        config.maxActivePaths = 4
        config.enableEndpoint = true
        config.endpointConfig = endpointConfig
        return config
    }

    private fun detectModelType(modelDir: String): ModelType? {
        val f = { name: String -> java.io.File(modelDir, name).exists() }
        if (f("encoder-epoch-99-avg-1.int8.onnx") && f("decoder-epoch-99-avg-1.onnx") &&
            f("joiner-epoch-99-avg-1.int8.onnx") && f("tokens.txt")) {
            return ModelType.ZipformerZh14M
        }
        if (f("encoder.int8.onnx") && f("decoder.int8.onnx") && f("tokens.txt")) {
            return ModelType.StreamingParaformerBilingual
        }
        if (f("encoder.int8.onnx") && f("decoder.onnx") && f("joiner.int8.onnx") && f("tokens.txt")) {
            return ModelType.ZipformerSmallZh
        }
        if (f("model.int8.onnx") && f("tokens.txt")) {
            return ModelType.ZipformerSmallCtcZh
        }
        return null
    }

    private enum class ModelType {
        ZipformerZh14M,
        StreamingParaformerBilingual,
        ZipformerSmallZh,
        ZipformerSmallCtcZh  // 20MB CTC model, single file
    }
}
