package ai.openclaw.script.bridge

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class FileBridgeTest {

    private lateinit var sandboxDir: File
    private lateinit var bridge: FileBridge

    @Before
    fun setup() {
        sandboxDir = File(System.getProperty("java.io.tmpdir"), "script_test_${System.currentTimeMillis()}")
        sandboxDir.mkdirs()
        bridge = FileBridge(null, sandboxDir)
    }

    @Test fun `writeFile creates file`() {
        val r = bridge.handle("fs.writeFile", """{"path":"test.txt","content":"hello world"}""")
        assertTrue(r.contains(""""success":true"""))
        assertTrue(r.contains(""""bytes":11"""))
    }

    @Test fun `readFile returns content`() {
        bridge.handle("fs.writeFile", """{"path":"read_test.txt","content":"hello script"}""")
        val r = bridge.handle("fs.readFile", """{"path":"read_test.txt"}""")
        assertTrue(r.contains("hello script"))
    }

    @Test fun `readFile non-existent returns error`() {
        val r = bridge.handle("fs.readFile", """{"path":"nonexistent.txt"}""")
        assertTrue(r.contains("error"))
    }

    @Test fun `writeFile in subdirectory`() {
        val r = bridge.handle("fs.writeFile", """{"path":"sub/dir/file.txt","content":"nested"}""")
        assertTrue(r.contains(""""success":true"""))
    }

    @Test fun `list returns entries`() {
        bridge.handle("fs.writeFile", """{"path":"a.txt","content":"a"}""")
        bridge.handle("fs.writeFile", """{"path":"b.txt","content":"b"}""")
        val r = bridge.handle("fs.list", """{"dir":"."}""")
        assertTrue(r.contains("a.txt"))
        assertTrue(r.contains("b.txt"))
    }

    @Test fun `list non-existent dir returns error`() {
        val r = bridge.handle("fs.list", """{"dir":"no_such_dir"}""")
        assertTrue(r.contains("error"))
    }

    @Test fun `exists true for existing file`() {
        bridge.handle("fs.writeFile", """{"path":"exist_test.txt","content":"x"}""")
        val r = bridge.handle("fs.exists", """{"path":"exist_test.txt"}""")
        assertTrue(r.contains(""""exists":true"""))
    }

    @Test fun `exists false for non-existent file`() {
        val r = bridge.handle("fs.exists", """{"path":"no_such_file.txt"}""")
        assertTrue(r.contains(""""exists":false"""))
    }

    @Test fun `path traversal dotdot blocked`() {
        val r = bridge.handle("fs.readFile", """{"path":"../../../etc/passwd"}""")
        assertTrue(r.contains("error"))
    }

    @Test fun `extractField string`() { assertEquals("test", extractField("""{"name":"test","value":42}""", "name")) }
    @Test fun `extractField number`() { assertEquals("42", extractField("""{"name":"test","value":42}""", "value")) }
    @Test fun `extractField missing`() { assertEquals("", extractField("""{"name":"test"}""", "missing")) }
    @Test fun `jsonEscape special chars`() {
        val r = jsonEscape("hello\nworld\"test")
        assertTrue(r.contains("\\n"))
        assertTrue(r.contains("\\\""))
    }
    @Test fun `jsonEscape empty`() { assertEquals("\"\"", jsonEscape("")) }
}
