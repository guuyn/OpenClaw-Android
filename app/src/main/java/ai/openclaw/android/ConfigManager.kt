package ai.openclaw.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ConfigManager - Manages app configuration with encrypted storage
 */
object ConfigManager {
    
    private const val PREFS_NAME = "openclaw_config"
    private const val SECRET_PREFS_NAME = "openclaw_secrets"
    
    // Keys
    private const val KEY_MODEL_API_KEY = "model_api_key"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_MODEL_PROVIDER = "model_provider"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    
    private lateinit var prefs: SharedPreferences
    private lateinit var secretPrefs: SharedPreferences
    private var isInitialized = false
    
    /**
     * Initialize ConfigManager
     */
    fun init(context: Context) {
        if (isInitialized) return
        
        // Normal preferences for non-sensitive data
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Encrypted preferences for sensitive data
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        secretPrefs = EncryptedSharedPreferences.create(
            context,
            SECRET_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        isInitialized = true
    }
    
    // ==================== Model Configuration ====================
    
    fun getModelApiKey(): String {
        return secretPrefs.getString(KEY_MODEL_API_KEY, "") ?: ""
    }
    
    fun setModelApiKey(apiKey: String) {
        secretPrefs.edit().putString(KEY_MODEL_API_KEY, apiKey).apply()
    }
    
    fun getModelName(): String {
        return prefs.getString(KEY_MODEL_NAME, "MiniMax-M2.5") ?: "MiniMax-M2.5"
    }
    
    fun setModelName(modelName: String) {
        prefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
    }
    
    fun getModelProvider(): String {
        return prefs.getString(KEY_MODEL_PROVIDER, "BAILIAN") ?: "BAILIAN"
    }
    
    fun setModelProvider(provider: String) {
        prefs.edit().putString(KEY_MODEL_PROVIDER, provider).apply()
    }
    
    fun hasModelCredentials(): Boolean {
        return getModelApiKey().isNotEmpty()
    }
    
    // ==================== Service State ====================
    
    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, false)
    }
    
    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }
    
    // ==================== Bulk Operations ====================
    
    /**
     * Check if all required configuration is present
     */
    fun isConfigured(): Boolean {
        return hasModelCredentials()
    }
    
    /**
     * Clear all configuration
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        secretPrefs.edit().clear().apply()
    }
    
    /**
     * Export configuration (without secrets) for debugging
     */
    fun exportConfig(): Map<String, Any?> {
        return mapOf(
            "model_name" to getModelName(),
            "model_provider" to getModelProvider(),
            "service_enabled" to isServiceEnabled(),
            "has_model_key" to getModelApiKey().isNotEmpty()
        )
    }
}