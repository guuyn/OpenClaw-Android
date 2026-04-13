package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import ai.openclaw.android.skill.SkillResult
import ai.openclaw.script.ScriptOrchestrator
import ai.openclaw.script.ScriptResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class WeatherSkillTest {

    @MockK
    private lateinit var mockContext: SkillContext

    @MockK
    private lateinit var mockOrchestrator: ScriptOrchestrator

    private lateinit var weatherSkill: WeatherSkill

    private val weatherJs = """
        var url = "https://wttr.in/" + LOCATION + "?format=3";
        try {
            var resp = http.get(url);
            if (resp.status === 200) {
                JSON.stringify({success: true, data: resp.body});
            } else {
                JSON.stringify({success: false, error: "HTTP error: " + resp.status});
            }
        } catch (e) {
            JSON.stringify({success: false, error: e.message || String(e)});
        }
    """.trimIndent()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        weatherSkill = WeatherSkill()

        val mockAppContext = mockk<android.content.Context>(relaxed = true)
        val mockAssets = mockk<android.content.res.AssetManager>(relaxed = true)
        every { mockAppContext.assets } returns mockAssets
        every { mockAssets.open("scripts/weather.js") } returns ByteArrayInputStream(weatherJs.toByteArray())
        every { mockContext.applicationContext } returns mockAppContext
    }

    private fun initWithMockOrchestrator() {
        // Use reflection to set up the skill with a mock orchestrator
        weatherSkill.initialize(mockContext)
        // Replace the internal orchestrator via reflection
        val orchField = WeatherSkill::class.java.getDeclaredField("orchestrator")
        orchField.isAccessible = true
        orchField.set(weatherSkill, mockOrchestrator)
    }

    @Test
    fun `getWeather_validLocation_returnsResult`() = runTest {
        // Arrange
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success("""{"success":true,"data":"Beijing: +20°C"}""")

        initWithMockOrchestrator()

        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "Beijing")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertTrue(result.success)
        assertEquals("Beijing: +20°C", result.output)
        assertTrue(result.error?.isBlank() != false)
    }

    @Test
    fun `getWeather_scriptExecutionFails_returnsError`() = runTest {
        // Arrange
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.failure("Network error")

        initWithMockOrchestrator()

        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "Beijing")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.isNotBlank() == true)
    }

    @Test
    fun `getWeather_httpError_returnsError`() = runTest {
        // Arrange
        every {
            mockOrchestrator.execute(any(), listOf("http"), any())
        } returns ScriptResult.success("""{"success":false,"error":"HTTP error: 404"}""")

        initWithMockOrchestrator()

        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "InvalidLocation")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.contains("HTTP error: 404") == true)
    }

    @Test
    fun `getWeather_missingLocationParameter_returnsError`() = runTest {
        // Arrange
        initWithMockOrchestrator()

        val weatherTool = weatherSkill.tools[0]
        val params = emptyMap<String, Any>()

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 location 参数") == true)
    }

    @Test
    fun `getWeather_emptyLocationParameter_returnsError`() = runTest {
        // Arrange
        initWithMockOrchestrator()

        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 location 参数") == true)
    }

    @Test
    fun `initialize_loadsScriptSuccessfully`() {
        // Act & Assert — should not throw
        kotlin.runCatching {
            weatherSkill.initialize(mockContext)
        }.onFailure {
            fail("Initialization threw exception: ${it.message}")
        }
    }
}
