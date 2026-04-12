package ai.openclaw.android.memory

import ai.openclaw.android.domain.memory.EmbeddingService
import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ONNX Runtime embedding model for the memory subsystem.
 *
 * Loads an ONNX export of BGE-small-zh / MiniLM-L6-v2 and produces
 * 384-dim sentence embeddings for hybrid search vector path.
 */
class EmbeddingModel(
    private val context: Context,
    private val dimension: Int = 384
) : EmbeddingService {

    companion object {
        private const val TAG = "MemoryEmbeddingModel"
        private const val MODEL_FILE = "bge-small-zh-quantized.onnx"
        private const val MODEL_DIR = "models"
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var initialized = false

    /**
     * Initialize the ONNX session. Call once before [embed].
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true

        try {
            val modelBytes = loadModelBytes() ?: run {
                Log.w(TAG, "Model file not found: $MODEL_FILE")
                return@withContext false
            }

            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env?.createSession(modelBytes, opts)
            initialized = true
            Log.i(TAG, "Initialized (dim=$dimension)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            false
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!isReady()) throw IllegalStateException("EmbeddingModel not initialized")

        val sess = session ?: throw IllegalStateException("Session is null")
        val tokenIds = tokenizeSimple(text)

        val padded = LongArray(dimension)
        for (i in tokenIds.indices) {
            padded[i] = tokenIds[i].toLong()
        }

        val inputIds = OnnxTensor.createTensor(env, padded.reshape(1, dimension))
        val output = sess.run(mapOf("input_ids" to inputIds))
        val result = (output[0].value as Array<FloatArray>)[0]

        inputIds.close()
        output.close()

        result
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = dimension

    override fun isReady(): Boolean = initialized && session != null

    fun release() {
        session?.close()
        env?.close()
        session = null
        env = null
        initialized = false
    }

    private fun loadModelBytes(): ByteArray? {
        // 1. External storage (models/)
        val external = File(File(context.getExternalFilesDir(null), MODEL_DIR), MODEL_FILE)
        if (external.exists()) {
            Log.i(TAG, "Loading from external: ${external.absolutePath}")
            return external.readBytes()
        }
        // 2. Assets
        return try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenizeSimple(text: String): IntArray {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s\\u4e00-\\u9fff]"), " ")
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
