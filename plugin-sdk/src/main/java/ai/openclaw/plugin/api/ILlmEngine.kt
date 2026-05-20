package ai.openclaw.plugin.api

import android.content.Context

/**
 * Core interfaces for the OpenClaw Plugin System.
 * This module (plugin-sdk) must remain lightweight and dependency-free 
 * (except for Android SDK) to ensure compatibility between Host and Plugins.
 */

// --- Plugin Metadata ---
data class PluginMeta(
    val engineType: String, // e.g., "litert", "gguf"
    val version: String,
    val supportedQuantizations: List<String>
)

// --- Callback for Streaming ---
interface IGenerationCallback {
    fun onToken(token: String)
    fun onComplete(fullText: String)
    fun onError(error: Throwable)
}

// --- Tool Definition (Simplified for Plugin Interface) ---
data class PluginTool(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string
)

// --- Main Engine Interface ---
interface ILlmEngine {
    val meta: PluginMeta

    /**
     * Initialize the engine.
     * Called immediately after instantiation.
     * @param context The Plugin's Context (for resources and native lib loading).
     * @return true if successful.
     */
    fun initialize(context: Context): Boolean

    /**
     * Unload the engine and free resources.
     */
    fun release()

    /**
     * Generate text stream.
     * @param prompt The input prompt.
     * @param tools Optional list of tools available for the model.
     * @param callback Callback to receive tokens and events.
     */
    fun generateStream(prompt: String, tools: List<PluginTool>?, callback: IGenerationCallback)

    /**
     * Stop current generation.
     */
    fun stop()
}
