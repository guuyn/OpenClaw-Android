package ai.openclaw.script

import org.junit.Assert.*
import org.junit.Test

class ScriptResultTest {
    @Test fun `success factory`() {
        val r = ScriptResult.success("hello", 100)
        assertTrue(r.success); assertEquals("hello", r.output); assertNull(r.error); assertEquals(100, r.executionTimeMs)
    }
    @Test fun `failure factory`() {
        val r = ScriptResult.failure("broke", 50)
        assertFalse(r.success); assertEquals("", r.output); assertEquals("broke", r.error); assertEquals(50, r.executionTimeMs)
    }
    @Test fun `success default time`() { assertEquals(0, ScriptResult.success("ok").executionTimeMs) }
    @Test fun `failure default time`() { assertEquals(0, ScriptResult.failure("err").executionTimeMs) }
    @Test fun `success no error`() { assertNull(ScriptResult.success("x", 1).error) }
    @Test fun `failure empty output`() { assertEquals("", ScriptResult.failure("x", 1).output) }
}
