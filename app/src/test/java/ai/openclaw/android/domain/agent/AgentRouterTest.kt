package ai.openclaw.android.domain.agent

import ai.openclaw.android.data.model.AgentConfig
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AgentRouter.
 * Uses mockk to mock AgentConfigManager and Android Log.
 */
class AgentRouterTest {

    private lateinit var mockManager: AgentConfigManager
    private lateinit var router: AgentRouter

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockManager = mockk(relaxed = true)
        router = AgentRouter(mockManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setupAgents() {
        val mainAgent = AgentConfig(id = "main", name = "Main", isDefault = true)
        val coderAgent = AgentConfig(
            id = "coder",
            name = "Coder",
            keywords = listOf("代码", "kotlin", "build", "gradle")
        )
        val securityAgent = AgentConfig(
            id = "security",
            name = "Security",
            keywords = listOf("安全", "漏洞", "audit", "token")
        )
        val keywordIndex = mapOf(
            "代码" to "coder", "kotlin" to "coder", "build" to "coder", "gradle" to "coder",
            "安全" to "security", "漏洞" to "security", "audit" to "security", "token" to "security"
        )

        every { mockManager.hasAgent(any()) } answers { call ->
            val id = call.invocation.args[0] as String
            id in listOf("main", "coder", "security")
        }
        every { mockManager.getAgentById("main") } returns mainAgent
        every { mockManager.getAgentById("coder") } returns coderAgent
        every { mockManager.getAgentById("security") } returns securityAgent
        every { mockManager.getAgentById(any()) } returns null
        every { mockManager.getDefaultAgent() } returns mainAgent
        every { mockManager.getKeywordIndex() } returns keywordIndex
    }

    // ========== @mention routing ==========

    @Test
    fun `route with valid @mention routes to mentioned agent`() {
        setupAgents()
        assertEquals("coder", router.route("@coder help me fix this bug"))
    }

    @Test
    fun `route with @mention in middle of message routes to mentioned agent`() {
        setupAgents()
        assertEquals("security", router.route("what do you think @security about this?"))
    }

    @Test
    fun `route with @mention and keywords routes to mentioned agent (mention wins)`() {
        setupAgents()
        // Message has "代码" keyword but @security mention should win
        assertEquals("security", router.route("@security 帮我写代码"))
    }

    // ========== Keyword matching ==========

    @Test
    fun `route with keyword routes to matching agent`() {
        setupAgents()
        assertEquals("coder", router.route("帮我写一段 Kotlin 代码"))
    }

    @Test
    fun `route with security keyword routes to security agent`() {
        setupAgents()
        assertEquals("security", router.route("检查这个 token 是否安全"))
    }

    @Test
    fun `route with no keyword routes to default agent`() {
        setupAgents()
        assertEquals("main", router.route("今天天气怎么样"))
    }

    // ========== Non-existent @mention falls back ==========

    @Test
    fun `route with non-existent @mention falls back to keyword match`() {
        setupAgents()
        every { mockManager.hasAgent("unknown") } returns false
        // @unknown doesn't exist, but "代码" matches coder
        assertEquals("coder", router.route("@unknown 帮我写代码"))
    }

    @Test
    fun `route with non-existent @mention and no keyword falls back to default`() {
        setupAgents()
        every { mockManager.hasAgent("unknown") } returns false
        assertEquals("main", router.route("@unknown 你好"))
    }

    // ========== Keyword scoring ==========

    @Test
    fun `longer keyword wins over shorter keyword for different agents`() {
        val mainAgent = AgentConfig(id = "main", name = "Main", isDefault = true)
        val coderAgent = AgentConfig(
            id = "coder",
            name = "Coder",
            keywords = listOf("代码")  // 2 chars
        )
        val dataAgent = AgentConfig(
            id = "data",
            name = "Data",
            keywords = listOf("数据分析", "report")  // "数据分析" is 4 chars
        )
        val keywordIndex = mapOf(
            "代码" to "coder",
            "数据分析" to "data", "report" to "data"
        )

        every { mockManager.hasAgent(any()) } returns false
        every { mockManager.getDefaultAgent() } returns mainAgent
        every { mockManager.getKeywordIndex() } returns keywordIndex

        // "数据分析" (4 chars) should beat "代码" (2 chars)
        assertEquals("data", router.route("帮我做数据分析和代码 review"))
    }

    // ========== Case-insensitive matching ==========

    @Test
    fun `keyword matching is case-insensitive for English keywords`() {
        setupAgents()
        assertEquals("coder", router.route("Help me BUILD this project"))
        assertEquals("coder", router.route("需要配置 Gradle 环境"))
        assertEquals("security", router.route("Run a security AUDIT"))
    }

    // ========== getExplicitMention ==========

    @Test
    fun `getExplicitMention returns agent id from @mention`() {
        assertEquals("coder", router.getExplicitMention("@coder fix this"))
    }

    @Test
    fun `getExplicitMention returns null when no mention`() {
        assertNull(router.getExplicitMention("你好世界"))
    }

    @Test
    fun `getExplicitMention returns null for at-symbol without valid id`() {
        assertNull(router.getExplicitMention("email@example.com"))
    }

    // ========== findBestMatch ==========

    @Test
    fun `findBestMatch returns mentioned agent via @mention`() {
        setupAgents()
        assertEquals("coder", router.findBestMatch("@coder help"))
    }

    @Test
    fun `findBestMatch returns keyword match when no mention`() {
        setupAgents()
        assertEquals("coder", router.findBestMatch("写代码"))
    }

    @Test
    fun `findBestMatch returns null when no mention and no keyword`() {
        setupAgents()
        assertNull(router.findBestMatch("你好"))
    }

    @Test
    fun `findBestMatch ignores non-existent @mention and falls back to keyword`() {
        setupAgents()
        every { mockManager.hasAgent("unknown") } returns false
        assertEquals("coder", router.findBestMatch("@unknown 写代码"))
    }

    @Test
    fun `findBestMatch returns null for non-existent @mention with no keyword`() {
        setupAgents()
        every { mockManager.hasAgent("unknown") } returns false
        assertNull(router.findBestMatch("@unknown 你好"))
    }
}
