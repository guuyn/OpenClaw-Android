package ai.openclaw.script

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SandboxPolicyTest {
    @Test fun `default values`() {
        val p = SandboxPolicy(sandboxDir = File("/tmp/sandbox"))
        assertEquals(10_000L, p.timeoutMs)
        assertEquals(16, p.maxMemoryMB)
        assertTrue(p.allowNetwork)
        assertTrue(p.allowFileWrite)
        assertNull(p.allowedDomains)
    }
    @Test fun `custom values`() {
        val p = SandboxPolicy(5_000L, 32, File("/tmp"), false, false, listOf("a.com"))
        assertEquals(5_000L, p.timeoutMs); assertEquals(32, p.maxMemoryMB)
        assertFalse(p.allowNetwork); assertFalse(p.allowFileWrite)
        assertEquals(listOf("a.com"), p.allowedDomains)
    }
}
