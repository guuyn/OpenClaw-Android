package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import ai.openclaw.android.skill.SkillResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WeatherSkillTest {

    @MockK
    private lateinit var mockContext: SkillContext

    private lateinit var weatherSkill: WeatherSkill

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        weatherSkill = WeatherSkill()
    }

    @Test
    fun `getWeather_missingLocationParameter_returnsError`() = runTest {
        // Arrange: skill not initialized (httpClient = null)
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
        // Arrange: skill not initialized
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.contains("缺少 location 参数") == true)
    }

    @Test
    fun `getWeather_nullLocation_returnsError`() = runTest {
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf<String, Any>("location" to "")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
    }

    @Test
    fun `getWeather_httpClientNotInitialized_returnsError`() = runTest {
        // Arrange: location provided but HTTP client not initialized
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "Beijing")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error?.contains("HTTP client not initialized") == true)
    }

    @Test
    fun `initialize_doesNotThrow`() {
        // Arrange
        val mockAppContext = mockk<android.content.Context>(relaxed = true)
        val mockHttpClient = mockk<OkHttpClient>(relaxed = true)
        every { mockContext.applicationContext } returns mockAppContext
        every { mockContext.httpClient } returns mockHttpClient

        // Act & Assert — should not throw
        kotlin.runCatching {
            weatherSkill.initialize(mockContext)
        }.onFailure {
            fail("Initialization threw exception: ${it.message}")
        }
    }

    @Test
    fun `skill_hasCorrectMetadata`() {
        assertEquals("weather", weatherSkill.id)
        assertEquals("天气查询", weatherSkill.name)
        assertEquals("2.0.0", weatherSkill.version)
        assertTrue(weatherSkill.tools.isNotEmpty())
        assertEquals("get_weather", weatherSkill.tools[0].name)
    }

    @Test
    fun `get_weather_tool_hasCorrectParameters`() {
        val tool = weatherSkill.tools[0]
        val locationParam = tool.parameters["location"]
        assertNotNull(locationParam)
        assertEquals("string", locationParam?.type)
        assertTrue(locationParam?.required == true)
    }
}
