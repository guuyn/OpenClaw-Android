package ai.openclaw.android.ml

import ai.openclaw.android.domain.memory.EmbeddingService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfLiteEmbeddingService(private val context: Context) : EmbeddingService {

    companion object {
        private const val TAG = "TfLiteEmbeddingService"
        private const val MODEL_FILE = "minilm-l6-v2.tflite"
        private const val VOCAB_FILE = "vocab.txt"
        private const val MAX_SEQ_LENGTH = 128
        private const val EMBEDDING_DIM = 384
    }

    private var interpreter: Interpreter? = null
    private var tokenizer: BertTokenizer? = null
    private var isInitialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 加载 tokenizer
            tokenizer = BertTokenizer(context.assets.open(VOCAB_FILE))

            // 加载 TFLite 模型
            val modelFile = loadModelFile()
            if (modelFile != null) {
                val options = Interpreter.Options()
                interpreter = Interpreter(modelFile, options)
                isInitialized = true
                Log.i(TAG, "Embedding service initialized successfully")
                true
            } else {
                Log.w(TAG, "Model file not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            false
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!isReady()) {
            throw IllegalStateException("Embedding service not initialized")
        }

        val tokens = tokenizer!!.tokenize(text, MAX_SEQ_LENGTH)
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }

        // 准备输入
        val inputIds = arrayOf(tokens.first)
        val attentionMask = arrayOf(tokens.second)

        // 运行推理
        interpreter?.run(mapOf(
            "input_ids" to inputIds,
            "attention_mask" to attentionMask
        ), output)

        output[0]
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun getDimension(): Int = EMBEDDING_DIM

    override fun isReady(): Boolean = isInitialized && interpreter != null && tokenizer != null

    private fun loadModelFile(): ByteBuffer? {
        return try {
            val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply {
                put(modelBytes)
                rewind()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        tokenizer = null
        isInitialized = false
    }
}