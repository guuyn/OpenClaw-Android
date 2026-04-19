package ai.openclaw.android.voice.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle manager for the Sherpa STT engine.
 *
 * Singleton that handles:
 * - Lazy initialization on first use
 * - Model path management
 * - Engine lifecycle (init/release)
 *
 * Usage:
 * ```kotlin
 * val manager = SherpaSttManager.getInstance(context)
 * val engine = manager.getEngine() // lazily initializes
 * ```
 */
class SherpaSttManager private constructor(
    private val context: Context
) {
    private var engine: SherpaSttEngine? = null
    private val initializing = AtomicBoolean(false)

    companion object {
        private const val TAG = "SherpaSttManager"

        @Volatile
        private var INSTANCE: SherpaSttManager? = null

        fun getInstance(context: Context): SherpaSttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SherpaSttManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Default model storage path on external storage. */
        fun defaultModelPath(context: Context): String {
            val extDir = context.getExternalFilesDir(null)
            return if (extDir != null) {
                "${extDir.absolutePath}/models/stt"
            } else {
                "${context.filesDir.absolutePath}/models/stt"
            }
        }

        fun clearInstance() {
            INSTANCE?.release()
            INSTANCE = null
        }
    }

    /**
     * Get or create the STT engine.
     * If not yet initialized, attempts lazy initialization from the default model path.
     */
    @Synchronized
    fun getEngine(modelDir: String? = null): SherpaSttEngine? {
        if (engine != null) return engine

        if (initializing.get()) {
            Log.w(TAG, "Already initializing, returning null")
            return null
        }

        val path = modelDir ?: defaultModelPath(context)
        return tryInitialize(path)
    }

    /**
     * Pre-initialize the engine with a specific model directory.
     * Returns true if initialization succeeded.
     */
    @Synchronized
    fun preInitialize(modelDir: String): Boolean {
        if (engine != null) return true
        if (initializing.get()) return false
        return tryInitialize(modelDir) != null
    }

    /** Check if the engine is ready. */
    fun isReady(): Boolean = engine != null

    /** Check if models are downloaded. */
    fun hasModel(modelDir: String? = null): Boolean {
        val path = modelDir ?: defaultModelPath(context)
        val tempEngine = SherpaSttEngine(context)
        return tempEngine.hasModel(path)
    }

    /** Release the engine and free resources. */
    @Synchronized
    fun release() {
        engine?.let {
            it.release()
            engine = null
            Log.i(TAG, "Engine released")
        }
    }

    private fun tryInitialize(modelDir: String): SherpaSttEngine? {
        initializing.set(true)
        return try {
            val eng = SherpaSttEngine(context)
            if (eng.initialize(modelDir)) {
                engine = eng
                Log.i(TAG, "Engine initialized from: $modelDir")
                eng
            } else {
                Log.e(TAG, "Failed to initialize engine from: $modelDir")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            null
        } finally {
            initializing.set(false)
        }
    }
}
