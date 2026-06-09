package ai.openclaw.android.skill.builtin

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class FileXferSkillTest {

    private lateinit var mockContext: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var fileXferSkill: FileXferSkill

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        filesDir = java.io.File.createTempFile("test-files", null).apply { delete(); mkdir() }
        cacheDir = java.io.File.createTempFile("test-cache", null).apply { delete(); mkdir() }
        every { mockContext.filesDir } returns filesDir
        every { mockContext.cacheDir } returns cacheDir
        fileXferSkill = FileXferSkill(mockContext)
    }

    @org.junit.After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    // ==================== Skill Metadata ====================

    @Test
    fun `skill metadata is correct`() {
        assertEquals("file_xfer", fileXferSkill.id)
        assertEquals("文件传输", fileXferSkill.name)
        assertEquals(5, fileXferSkill.tools.size)
    }

    // ==================== write + read ====================

    @Test
    fun `write and read text file`() = runTest {
        val writeTool = fileXferSkill.tools.find { it.name == "write" }!!
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!

        val writeResult = writeTool.execute(mapOf(
            "path" to "files/test.txt",
            "content" to "hello world",
            "overwrite" to "true"
        ))
        assertTrue(writeResult.success)

        val readResult = readTool.execute(mapOf("path" to "files/test.txt"))
        assertTrue(readResult.success)
        assertTrue(readResult.output.contains("hello world"))
    }

    @Test
    fun `write to cache directory`() = runTest {
        val writeTool = fileXferSkill.tools.find { it.name == "write" }!!
        val result = writeTool.execute(mapOf(
            "path" to "cache/temp.txt",
            "content" to "cached data",
            "overwrite" to "true"
        ))
        assertTrue(result.success)
    }

    // ==================== list ====================

    @Test
    fun `list files directory`() = runTest {
        // Create some test files
        File(filesDir, "a.txt").writeText("a")
        File(filesDir, "b.txt").writeText("b")

        val listTool = fileXferSkill.tools.find { it.name == "list" }!!
        val result = listTool.execute(mapOf("path" to "files/"))

        assertTrue(result.success)
        assertTrue(result.output.contains("a.txt"))
        assertTrue(result.output.contains("b.txt"))
    }

    // ==================== path traversal protection ====================

    @Test
    fun `read blocks path traversal`() = runTest {
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!
        val result = readTool.execute(mapOf("path" to "files/../../etc/passwd"))

        assertFalse(result.success)
    }

    @Test
    fun `write blocks path traversal`() = runTest {
        val writeTool = fileXferSkill.tools.find { it.name == "write" }!!
        val result = writeTool.execute(mapOf(
            "path" to "files/../../tmp/malicious.txt",
            "content" to "hacked",
            "overwrite" to "true"
        ))

        assertFalse(result.success)
    }

    // ==================== read nonexistent ====================

    @Test
    fun `read returns error for nonexistent file`() = runTest {
        val readTool = fileXferSkill.tools.find { it.name == "read" }!!
        val result = readTool.execute(mapOf("path" to "files/nonexistent.txt"))

        assertFalse(result.success)
    }
}
