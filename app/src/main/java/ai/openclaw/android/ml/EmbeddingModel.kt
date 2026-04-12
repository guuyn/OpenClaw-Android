package ai.openclaw.android.ml

import ai.openclaw.android.domain.memory.EmbeddingService
import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime-based embedding service.
 *
 * Loads an ONNX export of MiniLM-L6-v2 (or compatible model) and produces
 * 384-dim sentence embeddings.
 */
class EmbeddingModel(
    private val context: Context,
    private val dimension: Int = 384
) : EmbeddingService {

    companion object {
        private const val TAG = "EmbeddingModel"
        private const val MODEL_FILE = "minilm-l6-v2.onnx"
        private const val EXTERNAL_MODEL_DIR = "models"
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isInitialized = false
    private val cache = android.util.LruCache<String, FloatArray>(100)

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelBytes = loadModelBytes() ?: run {
                Log.w(TAG, "ONNX model file not found")
                return@withContext false
            }

            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = env?.createSession(modelBytes, opts)
            isInitialized = true
            Log.i(TAG, "ONNX embedding model initialized (dim=$dimension)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX model", e)
            false
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!isReady()) throw IllegalStateException("ONNX EmbeddingModel not initialized")
        cache[text]?.let { return@withContext it }

        val sess = session ?: throw IllegalStateException("Session is null")
        val tokenIds = tokenizeSimple(text)

        val padded = LongArray(384)
        for (i in tokenIds.indices) {
            padded[i] = tokenIds[i].toLong()
        }
        // remaining elements default to 0 (padding token ID)

        val inputIds = OnnxTensor.createTensor(
            env, padded.reshape(1, 384)
        )

        // Only use input_ids for simplicity; attention_mask defaults to all 1s
        val output = sess.run(mapOf("input_ids" to inputIds))
        val resultTensor = output[0].value as Array<FloatArray>
        val result = resultTensor[0]

        inputIds.close()
        output.close()

        cache.put(text, result)
        result
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = dimension

    override fun isReady(): Boolean = isInitialized && session != null

    fun release() {
        session?.close()
        env?.close()
        session = null
        env = null
        isInitialized = false
    }

    private fun loadModelBytes(): ByteArray? {
        // 1. External storage
        val externalModel = File(
            File(context.getExternalFilesDir(null), EXTERNAL_MODEL_DIR), MODEL_FILE
        )
        if (externalModel.exists()) {
            Log.i(TAG, "Loading ONNX model from external: ${externalModel.absolutePath}")
            return externalModel.readBytes()
        }
        // 2. Assets
        return try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun tokenizeSimple(text: String): IntArray {
        // Minimal whitespace tokenizer; real use should plug in BERT tokenizer
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { Math.abs(it.hashCode()) % 30000 }
            .toIntArray()
    }

    private fun LongArray.reshape(rows: Int, cols: Int): LongArray {
        val padded = LongArray(rows * cols)
        System.arraycopy(this, 0, padded, 0, this.size.coerceAtMost(padded.size))
        return padded
    }
}
