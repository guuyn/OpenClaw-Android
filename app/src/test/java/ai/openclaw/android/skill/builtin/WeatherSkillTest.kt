package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import ai.openclaw.android.skill.SkillResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class WeatherSkillTest {

    @MockK
    private lateinit var mockContext: SkillContext

    @MockK
    private lateinit var mockHttpClient: OkHttpClient

    private lateinit var weatherSkill: WeatherSkill

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        weatherSkill = WeatherSkill()
        
        every { mockContext.httpClient } returns mockHttpClient
        every { mockContext.applicationContext } returns mockk(relaxed = true)
    }

    @Test
    fun `getWeather_validLocation_returnsResult`() = runTest {
        // Arrange
        val mockCall = mockk<Call>(relaxed = true)
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://wttr.in/Beijing?format=3").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("Beijing: +20°C".toResponseBody("text/plain".toMediaType()))
            .build()

        coEvery { mockHttpClient.newCall(any()).execute() } returns mockResponse
        every { mockHttpClient.newCall(any()) } returns mockCall
        
        // Initialize the skill with the mocked context
        weatherSkill.initialize(mockContext)

        // Get the tool instance and execute it
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "Beijing")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertTrue(result.success)
        assertTrue(result.data.isNotBlank())
        assertTrue(result.error.isBlank())
    }

    @Test
    fun `getWeather_invalidLocation_returnsError`() = runTest {
        // Arrange
        val mockCall = mockk<Call>(relaxed = true)
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://wttr.in/InvalidLocation?format=3").build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("Location not found".toResponseBody("text/plain".toMediaType()))
            .build()

        coEvery { mockHttpClient.newCall(any()).execute() } returns mockResponse
        every { mockHttpClient.newCall(any()) } returns mockCall
        
        // Initialize the skill with the mocked context
        weatherSkill.initialize(mockContext)

        // Get the tool instance and execute it
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "InvalidLocation")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.isNotBlank())
    }

    @Test
    fun `initialize_setsHttpClientCorrectly`() {
        // Arrange
        every { mockContext.httpClient } returns mockHttpClient
        every { mockContext.applicationContext } returns mockk(relaxed = true)

        // Act
        weatherSkill.initialize(mockContext)

        // Assert
        // Since we can't directly verify the internal state, 
        // we'll test that initialization doesn't throw exceptions
        assertDoesNotThrow { 
            weatherSkill.initialize(mockContext) 
        }
    }

    @Test
    fun `getWeather_missingLocationParameter_returnsError`() = runTest {
        // Arrange
        weatherSkill.initialize(mockContext)

        // Get the tool instance and execute it with empty params
        val weatherTool = weatherSkill.tools[0]
        val params = emptyMap<String, Any>()

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.contains("缺少 location 参数"))
    }

    @Test
    fun `getWeather_emptyLocationParameter_returnsError`() = runTest {
        // Arrange
        weatherSkill.initialize(mockContext)

        // Get the tool instance and execute it with empty location
        val weatherTool = weatherSkill.tools[0]
        val params = mapOf("location" to "")

        // Act
        val result = weatherTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.contains("缺少 location 参数"))
    }
}