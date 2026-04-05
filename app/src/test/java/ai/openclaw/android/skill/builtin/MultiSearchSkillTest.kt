package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.SkillContext
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
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

class MultiSearchSkillTest {

    @MockK
    private lateinit var mockContext: SkillContext

    @MockK
    private lateinit var mockHttpClient: OkHttpClient

    private lateinit var multiSearchSkill: MultiSearchSkill

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        multiSearchSkill = MultiSearchSkill()
        
        every { mockContext.httpClient } returns mockHttpClient
        every { mockContext.applicationContext } returns mockk(relaxed = true)
    }

    @Test
    fun `search_validQuery_returnsResult`() = runTest {
        // Arrange
        val mockCall = mockk<Call>(relaxed = true)
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://searx.work/search?q=test&format=json").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""
                {
                  "results": [
                    {
                      "title": "Test Result 1",
                      "content": "This is the first test result",
                      "url": "https://example.com/1"
                    },
                    {
                      "title": "Test Result 2", 
                      "content": "This is the second test result",
                      "url": "https://example.com/2"
                    }
                  ]
                }
            """.trimIndent().toResponseBody("application/json".toMediaType()))
            .build()

        every { mockHttpClient.newCall(any()) } returns mockCall
        coEvery { mockCall.execute() } returns mockResponse
        
        // Initialize the skill with the mocked context
        multiSearchSkill.initialize(mockContext)

        // Get the tool instance and execute it
        val searchTool = multiSearchSkill.tools[0]
        val params = mapOf("query" to "test")

        // Act
        val result = searchTool.execute(params)

        // Assert
        assertTrue(result.success)
        assertTrue(result.data.isNotBlank())
        assertTrue(result.data.contains("Test Result 1"))
        assertTrue(result.data.contains("Test Result 2"))
        assertTrue(result.error.isBlank())
    }

    @Test
    fun `search_invalidQuery_returnsError`() = runTest {
        // Arrange
        val mockCall = mockk<Call>(relaxed = true)
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://searx.work/search?q=invalid&format=json").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("Invalid query".toResponseBody("text/plain".toMediaType()))
            .build()

        every { mockHttpClient.newCall(any()) } returns mockCall
        coEvery { mockCall.execute() } returns mockResponse
        
        // Initialize the skill with the mocked context
        multiSearchSkill.initialize(mockContext)

        // Get the tool instance and execute it
        val searchTool = multiSearchSkill.tools[0]
        val params = mapOf("query" to "invalid")

        // Act
        val result = searchTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.isNotBlank())
    }

    @Test
    fun `parseSearchResult_correctlyExtractsFields`() = runTest {
        // Arrange
        val mockCall = mockk<Call>(relaxed = true)
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://searx.work/search?q=example&format=json").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""
                {
                  "results": [
                    {
                      "title": "Example Title",
                      "content": "Example content description",
                      "url": "https://example.com/result"
                    }
                  ]
                }
            """.trimIndent().toResponseBody("application/json".toMediaType()))
            .build()

        every { mockHttpClient.newCall(any()) } returns mockCall
        coEvery { mockCall.execute() } returns mockResponse
        
        // Initialize the skill with the mocked context
        multiSearchSkill.initialize(mockContext)

        // Get the tool instance and execute it
        val searchTool = multiSearchSkill.tools[0]
        val params = mapOf("query" to "example")

        // Act
        val result = searchTool.execute(params)

        // Assert
        assertTrue(result.success)
        assertTrue(result.data.contains("Example Title"))
        assertTrue(result.data.contains("Example content description"))
        assertTrue(result.data.contains("example.com"))
    }

    @Test
    fun `initialize_setsHttpClientCorrectly`() {
        // Arrange
        every { mockContext.httpClient } returns mockHttpClient
        every { mockContext.applicationContext } returns mockk(relaxed = true)

        // Act
        multiSearchSkill.initialize(mockContext)

        // Assert
        // Since we can't directly verify the internal state, 
        // we'll test that initialization doesn't throw exceptions
        assertDoesNotThrow { 
            multiSearchSkill.initialize(mockContext) 
        }
    }

    @Test
    fun `search_missingQueryParameter_returnsError`() = runTest {
        // Arrange
        multiSearchSkill.initialize(mockContext)

        // Get the tool instance and execute it with empty params
        val searchTool = multiSearchSkill.tools[0]
        val params = emptyMap<String, Any>()

        // Act
        val result = searchTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.contains("缺少 query 参数"))
    }

    @Test
    fun `search_emptyQueryParameter_returnsError`() = runTest {
        // Arrange
        multiSearchSkill.initialize(mockContext)

        // Get the tool instance and execute it with empty query
        val searchTool = multiSearchSkill.tools[0]
        val params = mapOf("query" to "")

        // Act
        val result = searchTool.execute(params)

        // Assert
        assertFalse(result.success)
        assertTrue(result.error.contains("缺少 query 参数"))
    }
}