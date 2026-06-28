package ai.openclaw.android

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
 * Unit tests for ConfigManager — verifies encrypted storage of API keys,
 * defaults fallback, and provider/model/baseUrl switching.
 *
 * EncryptedSharedPreferences / MasterKey.Builder require AndroidKeyStore which
 * is unavailable in Robolectric. We mock the static factory methods with MockK
 * so the init() flow runs against real (file-backed) SharedPreferences,
 * letting us exercise all getters/setters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ConfigManagerTest {

    private lateinit var securePrefs: SharedPreferences
    private lateinit var plainPrefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Real file-backed SharedPreferences to back both encrypted and plain
        // storage. Two different files keep the tests independent.
        plainPrefs = context.getSharedPreferences(
            "openclaw_config_test_plain",
            Context.MODE_PRIVATE
        )
        plainPrefs.edit().clear().commit()

        securePrefs = context.getSharedPreferences(
            "openclaw_secrets_test",
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

        ConfigManager.init(context)
        ConfigManager.clearAll()
    }

    @After
    fun tearDown() {
        plainPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()
        ConfigManager.clearAll()
        unmockkAll()
    }

    // ==================== Model API key (encrypted) ====================

    @Test
    fun `getModelApiKey returns empty string by default`() {
        assertEquals("", ConfigManager.getModelApiKey())
        assertFalse(ConfigManager.hasModelCredentials())
    }

    @Test
    fun `setModelApiKey persists value`() {
        ConfigManager.setModelApiKey("sk-test-1234567890abcdef")
        assertEquals("sk-test-1234567890abcdef", ConfigManager.getModelApiKey())
        assertTrue(ConfigManager.hasModelCredentials())
    }

    @Test
    fun `api key survives ConfigManager re-init`() {
        ConfigManager.setModelApiKey("sk-persisted-key")
        ConfigManager.init(context)
        assertEquals("sk-persisted-key", ConfigManager.getModelApiKey())
    }

    @Test
    fun `api key with special characters round-trips`() {
        val key = "sk-test_with+special=chars/and\\more:\"quotes\""
        ConfigManager.setModelApiKey(key)
        assertEquals(key, ConfigManager.getModelApiKey())
    }

    @Test
    fun `empty api key clears credentials flag`() {
        ConfigManager.setModelApiKey("sk-something")
        assertTrue(ConfigManager.hasModelCredentials())
        ConfigManager.setModelApiKey("")
        assertFalse(ConfigManager.hasModelCredentials())
    }

    // ==================== Model name / provider / baseUrl ====================

    @Test
    fun `default model name is non-empty`() {
        val name = ConfigManager.getModelName()
        assertTrue("Default model name should not be empty", name.isNotEmpty())
    }

    @Test
    fun `setModelName persists value`() {
        ConfigManager.setModelName("gpt-4-turbo")
        assertEquals("gpt-4-turbo", ConfigManager.getModelName())
    }

    @Test
    fun `default provider is OPENAI`() {
        assertEquals("OPENAI", ConfigManager.getModelProvider())
    }

    @Test
    fun `setModelProvider supports all known providers`() {
        for (provider in listOf("OPENAI", "ANTHROPIC", "LOCAL")) {
            ConfigManager.setModelProvider(provider)
            assertEquals(provider, ConfigManager.getModelProvider())
        }
    }

    @Test
    fun `setModelBaseUrl stores custom URL`() {
        ConfigManager.setModelBaseUrl("https://api.example.com/v1")
        assertEquals("https://api.example.com/v1", ConfigManager.getModelBaseUrl())
    }

    @Test
    fun `effective base URL falls back to openai default when empty`() {
        ConfigManager.setModelProvider("OPENAI")
        ConfigManager.setModelBaseUrl("")
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ConfigManager.getEffectiveBaseUrl()
        )
    }

    @Test
    fun `effective base URL returns anthropic default for anthropic provider`() {
        ConfigManager.setModelProvider("ANTHROPIC")
        ConfigManager.setModelBaseUrl("")
        assertEquals(
            "https://api.anthropic.com",
            ConfigManager.getEffectiveBaseUrl()
        )
    }

    @Test
    fun `effective base URL prefers custom URL over default`() {
        ConfigManager.setModelProvider("OPENAI")
        ConfigManager.setModelBaseUrl("https://my-proxy.example.com/v1")
        assertEquals(
            "https://my-proxy.example.com/v1",
            ConfigManager.getEffectiveBaseUrl()
        )
    }

    @Test
    fun `effective base URL falls back to dashscope for unknown provider`() {
        ConfigManager.setModelProvider("UNKNOWN")
        ConfigManager.setModelBaseUrl("")
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ConfigManager.getEffectiveBaseUrl()
        )
    }

    // ==================== Service state ====================

    @Test
    fun `service is disabled by default`() {
        assertFalse(ConfigManager.isServiceEnabled())
    }

    @Test
    fun `setServiceEnabled toggles state`() {
        ConfigManager.setServiceEnabled(true)
        assertTrue(ConfigManager.isServiceEnabled())
        ConfigManager.setServiceEnabled(false)
        assertFalse(ConfigManager.isServiceEnabled())
    }

    // ==================== Feishu credentials (encrypted) ====================

    @Test
    fun `feishu credentials are empty by default`() {
        assertEquals("", ConfigManager.getFeishuAppId())
        assertEquals("", ConfigManager.getFeishuAppSecret())
        assertFalse(ConfigManager.hasFeishuCredentials())
    }

    @Test
    fun `setFeishuAppId and setFeishuAppSecret persist`() {
        ConfigManager.setFeishuAppId("cli_abc123")
        ConfigManager.setFeishuAppSecret("secret_xyz789")

        assertEquals("cli_abc123", ConfigManager.getFeishuAppId())
        assertEquals("secret_xyz789", ConfigManager.getFeishuAppSecret())
        assertTrue(ConfigManager.hasFeishuCredentials())
    }

    @Test
    fun `feishu credentials require both id and secret`() {
        // Only id → not configured
        ConfigManager.setFeishuAppId("only_id")
        ConfigManager.setFeishuAppSecret("")
        assertFalse(ConfigManager.hasFeishuCredentials())

        // Only secret → not configured
        ConfigManager.setFeishuAppId("")
        ConfigManager.setFeishuAppSecret("only_secret")
        assertFalse(ConfigManager.hasFeishuCredentials())

        // Both → configured
        ConfigManager.setFeishuAppId("id_and_secret")
        ConfigManager.setFeishuAppSecret("the_secret")
        assertTrue(ConfigManager.hasFeishuCredentials())
    }

    // ==================== Test mode ====================

    @Test
    fun `test mode is disabled by default`() {
        assertFalse(ConfigManager.isTestModeEnabled())
    }

    @Test
    fun `setTestModeEnabled persists value`() {
        ConfigManager.setTestModeEnabled(true)
        assertTrue(ConfigManager.isTestModeEnabled())
        ConfigManager.setTestModeEnabled(false)
        assertFalse(ConfigManager.isTestModeEnabled())
    }

    // ==================== isConfigured ====================

    @Test
    fun `isConfigured returns true for LOCAL provider regardless of api key`() {
        ConfigManager.setModelProvider("LOCAL")
        assertTrue(
            "LOCAL provider should be 'configured' without API key",
            ConfigManager.isConfigured()
        )
    }

    @Test
    fun `isConfigured returns false for OPENAI without api key`() {
        ConfigManager.setModelProvider("OPENAI")
        ConfigManager.setModelApiKey("")
        assertFalse(ConfigManager.isConfigured())
    }

    @Test
    fun `isConfigured returns true for OPENAI with api key`() {
        ConfigManager.setModelProvider("OPENAI")
        ConfigManager.setModelApiKey("sk-valid")
        assertTrue(ConfigManager.isConfigured())
    }

    // ==================== Bulk operations ====================

    @Test
    fun `clearAll wipes all config`() {
        ConfigManager.setModelApiKey("sk-test")
        ConfigManager.setModelName("gpt-4")
        ConfigManager.setModelProvider("ANTHROPIC")
        ConfigManager.setModelBaseUrl("https://example.com")
        ConfigManager.setServiceEnabled(true)
        ConfigManager.setFeishuAppId("cli_x")
        ConfigManager.setFeishuAppSecret("secret_x")

        ConfigManager.clearAll()

        assertEquals("", ConfigManager.getModelApiKey())
        assertFalse(ConfigManager.isServiceEnabled())
        assertEquals("", ConfigManager.getFeishuAppId())
        assertEquals("", ConfigManager.getFeishuAppSecret())
        assertFalse(ConfigManager.hasModelCredentials())
        assertFalse(ConfigManager.hasFeishuCredentials())
    }

    @Test
    fun `exportConfig omits secrets`() {
        ConfigManager.setModelApiKey("sk-SECRET")
        ConfigManager.setModelName("gpt-4")
        ConfigManager.setModelProvider("OPENAI")
        ConfigManager.setServiceEnabled(true)

        val exported = ConfigManager.exportConfig()
        assertEquals("gpt-4", exported["model_name"])
        assertEquals("OPENAI", exported["model_provider"])
        assertEquals(true, exported["service_enabled"])
        assertEquals(true, exported["has_model_key"])
        assertFalse("Export must not contain the raw API key", exported.containsKey("api_key"))
        assertFalse(
            "Export must not contain the raw API key value",
            exported.values.any { (it as? String) == "sk-SECRET" }
        )
    }
}