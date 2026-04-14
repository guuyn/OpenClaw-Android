package ai.openclaw.script

import org.junit.Test
import org.junit.Assert.*

class CapabilityBridgeTest {

    @Test
    fun `bridge has name`() {
        val bridge = TestBridge()
        assertEquals("test", bridge.name)
    }

    @Test
    fun `getJsPrototype returns valid JS`() {
        val bridge = TestBridge()
        val js = bridge.getJsPrototype()
        assertTrue(js.contains("var test"))
    }

    @Test
    fun `handleMethod returns JSON`() {
        val bridge = TestBridge()
        val result = bridge.handleMethod("test.hello", """{"msg":"hi"}""")
        assertTrue(result.contains("result"))
    }

    private class TestBridge : CapabilityBridge {
        override val name = "test"
        override fun getJsPrototype() = """
            var test = {
                hello: function(msg) { return JSON.parse(__nativeCall('test.hello', JSON.stringify({msg: msg}))); }
            };
        """.trimIndent()
        override fun handleMethod(method: String, argsJson: String): String {
            return """{"result":"ok"}"""
        }
    }
}
