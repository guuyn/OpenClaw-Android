package ai.openclaw.script.bridge

import com.dokar.quickjs.binding.ObjectBindingScope
import org.junit.Assert.*
import org.junit.Test

class HttpBridgeTest {

    @Test fun `registerBindings method exists`() {
        // Verify registerBindings method is defined on the class with correct signature
        val bridge = HttpBridge()
        val method = HttpBridge::class.java.getMethod("registerBindings", ObjectBindingScope::class.java)
        assertNotNull(method)
        assertEquals("registerBindings", method.name)
    }

    @Test fun `handle get unknown url returns error`() {
        val bridge = HttpBridge()
        val r = bridge.handle("http.get", """{"url":""}""")
        // Empty URL should cause an error (OkHttp throws)
        assertTrue(r.contains("error"))
    }

    @Test fun `handle unknown method returns error`() {
        val bridge = HttpBridge()
        val r = bridge.handle("http.delete", """{"url":"http://example.com"}""")
        assertTrue(r.contains("error"))
    }

    @Test fun `name is http`() {
        assertEquals("http", HttpBridge().name)
    }
}
