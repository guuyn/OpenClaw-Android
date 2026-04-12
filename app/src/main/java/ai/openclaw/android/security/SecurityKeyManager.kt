package ai.openclaw.android.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Manages database encryption keys via Android Keystore.
 *
 * Flow:
 * 1. Generate a 256-bit AES key, store it in EncryptedSharedPreferences.
 * 2. On subsequent runs, read the key from EncryptedSharedPreferences.
 * 3. The key is passed to SQLCipher's SupportFactory.
 */
class SecurityKeyManager(context: Context) {

    companion object {
        private const val KEY_ALIAS = "openclaw_db_key"
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_FIELD = "db_encryption_key"
    }

    private val securePrefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get or create the database encryption passphrase as ByteArray.
     * The key is a 32-byte (256-bit) random key, Base64-encoded in storage.
     */
    fun getOrCreateDatabaseKey(): ByteArray {
        val existing = securePrefs.getString(KEY_FIELD, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        // Generate new 256-bit key
        val keyBytes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES).apply {
            init(256)
        }.generateKey().encoded

        securePrefs.edit()
            .putString(KEY_FIELD, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            .apply()

        return keyBytes
    }
}
