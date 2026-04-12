package ai.openclaw.script

import ai.openclaw.script.bridge.FileBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ScriptEngineTest {

    private lateinit var sandboxDir: File
    private lateinit var engine: ScriptEngine
    private lateinit var policy: SandboxPolicy

    @Before
    fun setup() {
        sandboxDir = File(System.getProperty("java.io.tmpdir"), "engine_test_${System.currentTimeMillis()}")
        sandboxDir.mkdirs()
        engine = ScriptEngine(null)
        engine.initialize()
        policy = SandboxPolicy(sandboxDir = sandboxDir)
    }

    @Test fun `arithmetic`() {
        val r = engine.execute("1 + 1", emptyList(), policy)
        assertTrue(r.success); assertEquals("2", r.output)
    }
    @Test fun `string concat`() {
        val r = engine.execute("'hello' + ' ' + 'world'", emptyList(), policy)
        assertTrue(r.success); assertEquals("hello world", r.output)
    }
    @Test fun `variables`() {
        val r = engine.execute("var x = 10; var y = 20; x + y;", emptyList(), policy)
        assertTrue(r.success); assertEquals("30", r.output)
    }
    @Test fun `array reduce`() {
        val r = engine.execute("var a = [1,2,3,4,5]; a.reduce(function(a,b){return a+b;},0);", emptyList(), policy)
        assertTrue(r.success); assertEquals("15", r.output)
    }
    @Test fun `JSON parse`() {
        val r = engine.execute("""var o = JSON.parse('{"v":42}'); o.v * 2;""", emptyList(), policy)
        assertTrue(r.success); assertEquals("84", r.output)
    }
    @Test fun `for loop`() {
        val r = engine.execute("var s=0; for(var i=1;i<=10;i++){s+=i;} s;", emptyList(), policy)
        assertTrue(r.success); assertEquals("55", r.output)
    }
    @Test fun `ternary`() {
        val r = engine.execute("15 > 10 ? 'big' : 'small';", emptyList(), policy)
        assertTrue(r.success); assertEquals("big", r.output)
    }
    @Test fun `recursive function`() {
        val r = engine.execute("function f(n){if(n<=1)return n;return f(n-1)+f(n-2);} f(10);", emptyList(), policy)
        assertTrue(r.success); assertEquals("55", r.output)
    }
    @Test fun `FileBridge write`() {
        val bridges = listOf(FileBridge(null, sandboxDir))
        val r = engine.execute("__nativeCall('fs.writeFile', JSON.stringify({path:'t.txt',content:'hi'}));", bridges, policy)
        assertTrue(r.success)
        assertEquals("hi", File(sandboxDir, "t.txt").readText())
    }
    @Test fun `FileBridge exists`() {
        val bridges = listOf(FileBridge(null, sandboxDir))
        File(sandboxDir, "e.txt").writeText("x")
        val r = engine.execute("__nativeCall('fs.exists', JSON.stringify({path:'e.txt'}));", bridges, policy)
        assertTrue(r.success); assertTrue(r.output.contains("true"))
    }
    @Test fun `empty script fails`() {
        val r = engine.execute("", emptyList(), policy)
        assertFalse(r.success); assertNotNull(r.error)
    }
    @Test fun `import rejected`() {
        val r = engine.execute("import fs from 'fs';", emptyList(), policy)
        assertFalse(r.success); assertTrue(r.error!!.contains("禁止"))
    }
    @Test fun `java access rejected`() {
        assertFalse(engine.execute("java.lang.System.exit(0);", emptyList(), policy).success)
    }
    @Test fun `timeout on infinite loop`() {
        val p = SandboxPolicy(timeoutMs = 500, sandboxDir = sandboxDir)
        val r = engine.execute("while(true){}", emptyList(), p)
        assertFalse(r.success); assertNotNull(r.error)
    }
    @Test fun `execution time tracked`() {
        val r = engine.execute("1+1", emptyList(), policy)
        assertTrue(r.success); assertTrue(r.executionTimeMs >= 0)
    }
    @Test fun `destroy safe`() { ScriptEngine(null).apply { initialize(); destroy() } }
}
