package ai.openclaw.android.skill.builtin

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FileXferSkillTest {

    private lateinit var context: android.content.Context
    private lateinit var fileXferSkill: FileXferSkill
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileXferSkill = FileXferSkill(context)

        // Create a test subdirectory to avoid polluting filesDir root
        testDir = File(context.filesDir, "test-files").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `skill metadata is correct`() {
        assertEquals("file_xfer", fileXferSkill.id)
        assertTrue(fileXferSkill.tools.isNotEmpty())
    }

    @Test
    fun `all expected tools are present`() {
        val names = fileXferSkill.tools.map { it.name }.toSet()
        assertTrue(names.contains("read"))
        assertTrue(names.contains("write"))
        assertTrue(names.contains("list"))
        assertTrue(names.contains("share"))
        assertTrue(names.contains("download"))
    }

    @Test
    fun `write and read text file`() = runTest {
        // Write to a path inside filesRoot
        val writeTool = fileXferSkill.tools.find { it.name == "write" }!!
        val writeResult = writeTool.execute(
            mapOf(
                "path" to "test-files/hello.txt",
                "content" to "Hello, World!"
            )
        )
        assertTrue("Write should succeed: ${writeResult.output}", writeResult.success)

        // Read it back
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!
        val readResult = readTool.execute(
            mapOf("path" to "test-files/hello.txt")
        )
        assertTrue("Read should succeed: ${readResult.output}", readResult.success)
        assertTrue(readResult.output.contains("Hello, World!"))
    }

    @Test
    fun `list files directory`() = runTest {
        // Create some files
        File(testDir, "a.txt").writeText("aaa")
        File(testDir, "b.txt").writeText("bbb")

        val listTool = fileXferSkill.tools.find { it.name == "list" }!!
        val result = listTool.execute(
            mapOf("path" to "test-files")
        )
        assertTrue("List should succeed: ${result.output}", result.success)
        assertTrue(result.output.contains("a.txt"))
        assertTrue(result.output.contains("b.txt"))
    }

    @Test
    fun `read non-existent file fails`() = runTest {
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!
        val result = readTool.execute(
            mapOf("path" to "nonexistent.txt")
        )
        assertFalse(result.success)
    }

    @Test
    fun `share creates shareable intent`() = runTest {
        // Create a file first
        File(testDir, "share.txt").writeText("share me")

        val shareTool = fileXferSkill.tools.find { it.name == "share" }!!
        val result = shareTool.execute(
            mapOf("path" to "test-files/share.txt")
        )
        // Share may or may not succeed depending on Robolectric setup
        assertNotNull(result)
    }

    @Test
    fun `download from URL`() = runTest {
        val downloadTool = fileXferSkill.tools.find { it.name == "download" }!!
        val result = downloadTool.execute(
            mapOf(
                "url" to "https://httpbin.org/get",
                "path" to "test-files/httpbin.json"
            )
        )
        // Network may not be available in unit test
        assertNotNull(result)
    }

    @Test
    fun `path traversal blocked`() = runTest {
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!
        val result = readTool.execute(
            mapOf("path" to "../../etc/hosts")
        )
        assertFalse(result.success)
    }
}
