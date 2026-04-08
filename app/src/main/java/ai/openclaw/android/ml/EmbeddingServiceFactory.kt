package ai.openclaw.android.ml

import ai.openclaw.android.domain.memory.EmbeddingService
import android.content.Context
import android.util.Log

/**
 * Factory for creating the best available EmbeddingService
 * 
 * Priority:
 * 1. TfLiteEmbeddingService (if model file exists)
 * 2. SimpleEmbeddingService (fallback)
 */
object EmbeddingServiceFactory {
    
    private const val TAG = "EmbeddingServiceFactory"
    
    /**
     * Create the best available embedding service
     */
    fun create(context: Context): EmbeddingService {
        return when {
            hasTFLiteModel(context) -> {
                Log.i(TAG, "Using TFLite embedding service")
                TfLiteEmbeddingService(context)
            }
            else -> {
                Log.i(TAG, "Using simple embedding service (model not found)")
                SimpleEmbeddingService(context)
            }
        }
    }
    
    /**
     * Check if TFLite model file exists
     */
    private fun hasTFLiteModel(context: Context): Boolean {
        return try {
            val files = context.assets.list("") ?: emptyArray()
            files.contains("minilm-l6-v2.tflite")
        } catch (e: Exception) {
            false
        }
    }
}