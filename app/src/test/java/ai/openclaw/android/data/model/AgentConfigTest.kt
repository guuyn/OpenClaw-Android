package ai.openclaw.android.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AgentConfig, AgentRegistry, and AgentConfigSerializer.
 * Pure JVM tests — no Android components required.
 */
class AgentConfigTest {

    // ========== Serialization / Deserialization ==========

    @Test
    fun `serialize and deserialize roundtrip preserves all fields`() {
        val original = AgentRegistry(
            agents = listOf(
                AgentConfig(
                    id = "main",
                    name = "OpenClaw",
                    model = "bailian/qwen3.5-plus",
                    systemPrompt = "你是 AI 助手",
                    tools = listOf("all"),
                    keywords = emptyList(),
                    isDefault = true
                ),
                AgentConfig(
                    id = "coder",
                    name = "Coder",
                    model = "bailian/qwen3.5-coder",
                    systemPrompt = "你是开发助手",
                    tools = listOf("script", "search"),
                    keywords = listOf("代码", "kotlin"),
                    isDefault = false
                )
            )
        )

        val json = AgentConfigSerializer.serialize(original)
        val restored = AgentConfigSerializer.deserialize(json)

        assertEquals(original.agents.size, restored.agents.size)
        assertEquals(original.agents[0].id, restored.agents[0].id)
        assertEquals(original.agents[0].name, restored.agents[0].name)
        assertEquals(original.agents[0].model, restored.agents[0].model)
        assertEquals(original.agents[0].systemPrompt, restored.agents[0].systemPrompt)
        assertEquals(original.agents[0].tools, restored.agents[0].tools)
        assertEquals(original.agents[0].keywords, restored.agents[0].keywords)
        assertEquals(original.agents[0].isDefault, restored.agents[0].isDefault)
    }

    @Test
    fun `deserializes agents json content correctly`() {
        // Simulate the content of agents.json
        val jsonContent = """
            {
              "agents": [
                {
                  "id": "main",
                  "name": "OpenClaw",
                  "model": "bailian/qwen3.5-plus",
                  "systemPrompt": "你是一个 Android 设备上的 AI 助手，拥有设备控制、技能调用和记忆检索能力。",
                  "tools": ["all"],
                  "isDefault": true
                },
                {
                  "id": "coder",
                  "name": "Coder",
                  "model": "bailian/qwen3.5-coder",
                  "systemPrompt": "你是一个 Android 开发助手，精通 Kotlin、Jetpack Compose、Gradle 构建系统和 Android SDK。回答问题时要给出具体代码示例。",
                  "tools": ["script", "search", "file"],
                  "keywords": ["代码", "java", "kotlin", "build", "gradle", "pr", "commit", "bug", "debug", "编译", "构建", "apk", "compose", "android"]
                },
                {
                  "id": "security",
                  "name": "Security",
                  "model": "bailian/qwen3.5-plus",
                  "systemPrompt": "你是一个 Android 安全审计专家，负责检测应用的安全漏洞、权限配置问题和数据泄露风险。",
                  "tools": ["search", "audit"],
                  "keywords": ["安全", "漏洞", "审计", "权限", "加密", "injection", "xss", "csrf", "token", "key"]
                }
              ]
            }
        """.trimIndent()

        val registry = AgentConfigSerializer.deserialize(jsonContent)

        assertEquals(3, registry.agents.size)
        assertEquals("main", registry.agents[0].id)
        assertEquals("coder", registry.agents[1].id)
        assertEquals("security", registry.agents[2].id)
    }

    // ========== Keyword Matching ==========

    @Test
    fun `matches returns true when message contains a keyword`() {
        val coder = AgentConfig(
            id = "coder",
            name = "Coder",
            keywords = listOf("代码", "kotlin", "build", "gradle")
        )

        assertTrue(coder.matches("帮我写一段 Kotlin 代码"))
        assertTrue(coder.matches("Gradle build 失败了"))
        assertTrue(coder.matches("KOTLIN 语法问题")) // case-insensitive
    }

    @Test
    fun `matches returns false when message contains no keywords`() {
        val coder = AgentConfig(
            id = "coder",
            name = "Coder",
            keywords = listOf("代码", "kotlin", "build")
        )

        assertFalse(coder.matches("今天天气怎么样？"))
        assertFalse(coder.matches("帮我写一首诗"))
    }

    @Test
    fun `matches returns false when keywords list is empty`() {
        val main = AgentConfig(
            id = "main",
            name = "OpenClaw",
            keywords = emptyList()
        )

        assertFalse(main.matches("任何消息都不会匹配"))
    }

    @Test
    fun `matches is case insensitive`() {
        val coder = AgentConfig(
            id = "coder",
            name = "Coder",
            keywords = listOf("Kotlin", "GRADLE")
        )

        assertTrue(coder.matches("kotlin is great"))
        assertTrue(coder.matches("gradle build"))
        assertTrue(coder.matches("KOTLIN GRADLE"))
    }

    // ========== AgentRegistry ==========

    @Test
    fun `getAgentById returns correct agent`() {
        val registry = AgentRegistry(
            agents = listOf(
                AgentConfig(id = "main", name = "OpenClaw", isDefault = true),
                AgentConfig(id = "coder", name = "Coder"),
                AgentConfig(id = "security", name = "Security")
            )
        )

        assertNotNull(registry.getAgentById("main"))
        assertNotNull(registry.getAgentById("coder"))
        assertNotNull(registry.getAgentById("security"))
        assertNull(registry.getAgentById("nonexistent"))
    }

    @Test
    fun `getDefaultAgent returns agent marked as default`() {
        val registry = AgentRegistry(
            agents = listOf(
                AgentConfig(id = "coder", name = "Coder"),
                AgentConfig(id = "main", name = "OpenClaw", isDefault = true),
                AgentConfig(id = "security", name = "Security")
            )
        )

        val defaultAgent = registry.getDefaultAgent()
        assertEquals("main", defaultAgent.id)
    }

    @Test
    fun `getDefaultAgent falls back to first agent when no default`() {
        val registry = AgentRegistry(
            agents = listOf(
                AgentConfig(id = "first", name = "First"),
                AgentConfig(id = "second", name = "Second")
            )
        )

        val defaultAgent = registry.getDefaultAgent()
        assertEquals("first", defaultAgent.id)
    }

    // ========== Default Values ==========

    @Test
    fun `AgentConfig uses correct default values`() {
        val config = AgentConfig(id = "test", name = "Test")

        assertEquals("bailian/qwen3.5-plus", config.model)
        assertNull(config.systemPrompt)
        assertEquals(listOf("all"), config.tools)
        assertEquals(emptyList<String>(), config.keywords)
        assertFalse(config.isDefault)
    }

    @Test
    fun `AgentConfig allows overriding defaults`() {
        val config = AgentConfig(
            id = "custom",
            name = "Custom",
            model = "custom/model",
            systemPrompt = "Custom prompt",
            tools = listOf("script"),
            keywords = listOf("test"),
            isDefault = true
        )

        assertEquals("custom/model", config.model)
        assertEquals("Custom prompt", config.systemPrompt)
        assertEquals(listOf("script"), config.tools)
        assertEquals(listOf("test"), config.keywords)
        assertTrue(config.isDefault)
    }

    // ========== Edge Cases ==========

    @Test
    fun `deserializes with missing optional fields`() {
        val json = """
            {
              "agents": [
                {
                  "id": "minimal",
                  "name": "Minimal"
                }
              ]
            }
        """.trimIndent()

        val registry = AgentConfigSerializer.deserialize(json)
        val agent = registry.agents.first()

        assertEquals("minimal", agent.id)
        assertEquals("Minimal", agent.name)
        assertEquals("bailian/qwen3.5-plus", agent.model)
        assertNull(agent.systemPrompt)
        assertEquals(listOf("all"), agent.tools)
        assertEquals(emptyList<String>(), agent.keywords)
        assertFalse(agent.isDefault)
    }

    @Test
    fun `deserializes with null systemPrompt`() {
        val json = """
            {
              "agents": [
                {
                  "id": "null-prompt",
                  "name": "NullPrompt",
                  "systemPrompt": null
                }
              ]
            }
        """.trimIndent()

        val registry = AgentConfigSerializer.deserialize(json)
        assertNull(registry.agents.first().systemPrompt)
    }

    @Test
    fun `serialize produces valid JSON`() {
        val registry = AgentRegistry(
            agents = listOf(
                AgentConfig(id = "test", name = "Test", isDefault = true)
            )
        )

        val json = AgentConfigSerializer.serialize(registry)

        assertTrue(json.contains("\"agents\""))
        assertTrue(json.contains("\"id\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"test\""))
        assertTrue(json.contains("\"isDefault\""))
    }
}
