package ai.openclaw.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ScriptEngineDiagTest {

    private lateinit var sandboxDir: File

    @Before
    fun setup() {
        sandboxDir = File(System.getProperty("java.io.tmpdir"), "diag_${System.currentTimeMillis()}")
        sandboxDir.mkdirs()
        println("sandboxDir: $sandboxDir (exists=${sandboxDir.exists()})")
    }

    @Test fun `create engine with null context`() = runTest {
        try {
            val engine = ScriptEngine(null)
            println("Engine created OK")
            engine.initialize()
            println("Engine initialized OK")
            val policy = SandboxPolicy(sandboxDir = sandboxDir)
            println("Policy created OK")
            val r = engine.execute("1 + 1", emptyList(), policy)
            println("Result: success=${r.success} output='${r.output}' error='${r.error}'")
            assertTrue(r.success)
        } catch (e: Exception) {
            e.printStackTrace()
            fail("Exception: ${e.message}")
        }
    }
}
