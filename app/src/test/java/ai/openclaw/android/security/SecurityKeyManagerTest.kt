package ai.openclaw.android.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SecurityKeyManager using Robolectric + MockK static mocking
 * to bypass AndroidKeyStore (unavailable in unit-test JVMs).
 *
 * Robolectric does NOT provide AndroidKeyStore, so we mock the static
 * EncryptedSharedPreferences.create() and MasterKey.Builder.build() to return
 * a regular SharedPreferences. This lets us exercise the application logic
 * (key generation, persistence, retrieval) without the real KeyStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SecurityKeyManagerTest {

    private lateinit var context: Context
    private lateinit var securePrefs: SharedPreferences
    private lateinit var keyManager: SecurityKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Use a real file-backed SharedPreferences so getOrCreateDatabaseKey() can
        // actually persist between calls. We'll mask it as the encrypted prefs.
        securePrefs = context.getSharedPreferences(
            "openclaw_secure_prefs_test",
            Context.MODE_PRIVATE
        )
        securePrefs.edit().clear().commit()

        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
            )
        } returns securePrefs
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk<MasterKey>(relaxed = true)

        keyManager = SecurityKeyManager(context)
    }

    @After
    fun tearDown() {
        securePrefs.edit().clear().commit()
        unmockkAll()
    }

    @Test
    fun `getOrCreateDatabaseKey returns 32-byte key`() {
        val key = keyManager.getOrCreateDatabaseKey()
        assertNotNull(key)
        assertEquals("AES-256 key must be 32 bytes", 32, key.size)
    }

    @Test
    fun `getOrCreateDatabaseKey returns same key on repeated calls`() {
        val first = keyManager.getOrCreateDatabaseKey()
        val second = keyManager.getOrCreateDatabaseKey()
        assertArrayEquals("Key should be stable across calls", first, second)
    }

    @Test
    fun `getOrCreateDatabaseKey returns persisted key on new instance`() {
        // First instance: create and persist a key
        val first = keyManager.getOrCreateDatabaseKey()

        // Second instance over the same prefs → should read the persisted key
        val secondManager = SecurityKeyManager(context)
        val second = secondManager.getOrCreateDatabaseKey()
        assertArrayEquals(
            "New instance must read the previously persisted key",
            first,
            second
        )
    }

    @Test
    fun `multiple SecurityKeyManager instances over the same prefs share the key`() {
        val manager1 = SecurityKeyManager(context)
        val key1 = manager1.getOrCreateDatabaseKey()
        val manager2 = SecurityKeyManager(context)
        val key2 = manager2.getOrCreateDatabaseKey()
        assertArrayEquals("Shared prefs must yield same key", key1, key2)
    }

    @Test
    fun `key is not all zeros`() {
        val key = keyManager.getOrCreateDatabaseKey()
        val allZero = key.all { it == 0.toByte() }
        assertFalse("Generated key must not be all zeros", allZero)
    }

    @Test
    fun `key has byte variety`() {
        val key = keyManager.getOrCreateDatabaseKey()
        val uniqueBytes = key.toSet().size
        assertTrue(
            "Generated key should have byte variety (found $uniqueBytes unique bytes)",
            uniqueBytes >= 4
        )
    }

    @Test
    fun `clearing prefs causes new key generation`() {
        val firstKey = keyManager.getOrCreateDatabaseKey()
        securePrefs.edit().clear().commit()

        val newManager = SecurityKeyManager(context)
        val newKey = newManager.getOrCreateDatabaseKey()

        assertEquals(32, newKey.size)
        assertFalse(
            "After prefs clear, new key should differ from old key",
            firstKey.contentEquals(newKey)
        )
    }

    @Test
    fun `key value is base64-encoded when stored`() {
        val key = keyManager.getOrCreateDatabaseKey()
        // The class Base64-encodes before storage and decodes on read — verify round trip
        // by recreating an instance and confirming we get back the same bytes
        val recreated = SecurityKeyManager(context).getOrCreateDatabaseKey()
        assertArrayEquals("Base64 round-trip preserves key bytes", key, recreated)
    }
}