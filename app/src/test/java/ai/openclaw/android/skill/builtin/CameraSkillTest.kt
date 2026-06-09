package ai.openclaw.android.skill.builtin

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraSkillTest {

    private lateinit var mockContext: Context
    private lateinit var cameraSkill: CameraSkill
    private lateinit var mockCameraManager: CameraManager

    @Before
    fun setUp() {
        mockContext = mockk()
        mockCameraManager = mockk()
        
        // Default: no cameras available
        every { mockCameraManager.cameraIdList } returns arrayOf()
        
        // Stub context.checkSelfPermission — ContextCompat calls this under the hood
        every { mockContext.checkSelfPermission(android.Manifest.permission.CAMERA) } returns PackageManager.PERMISSION_DENIED
        every { mockContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_DENIED
        // ContextCompat also calls checkPermission(String, int, int) on some API levels
        every { mockContext.checkPermission(android.Manifest.permission.CAMERA, any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { mockContext.getSystemService(Context.CAMERA_SERVICE) } returns mockCameraManager

        cameraSkill = CameraSkill(mockContext)
    }

    // ==================== Skill Metadata ====================

    @Test
    fun `skill metadata is correct`() {
        assertEquals("camera", cameraSkill.id)
        assertEquals("摄像头", cameraSkill.name)
        assertEquals(3, cameraSkill.tools.size)
        assertEquals("capture", cameraSkill.tools[0].name)
        assertEquals("record", cameraSkill.tools[1].name)
        assertEquals("gallery_latest", cameraSkill.tools[2].name)
    }

    // ==================== camera_capture ====================

    @Test
    fun `camera_capture returns error without camera permission`() = runTest {
        val captureTool = cameraSkill.tools.find { it.name == "capture" }!!
        val result = captureTool.execute(emptyMap())

        assertFalse(result.success)
        val fullMessage = result.output + " " + (result.error ?: "")
        assertTrue(fullMessage.contains("CAMERA") || fullMessage.contains("权限"))
    }

    @Test
    fun `camera_capture returns error with no cameras`() = runTest {
        every { mockContext.checkSelfPermission(android.Manifest.permission.CAMERA) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkPermission(android.Manifest.permission.CAMERA, any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val captureTool = cameraSkill.tools.find { it.name == "capture" }!!
        val result = captureTool.execute(mapOf("facing" to "back"))

        assertFalse(result.success)
        val fullMessage = result.output + " " + (result.error ?: "")
        assertTrue(fullMessage.contains("未找到") || fullMessage.contains("摄像头"))
    }

    // ==================== camera_record ====================

    @Test
    fun `camera_record returns error without camera permission`() = runTest {
        val recordTool = cameraSkill.tools.find { it.name == "record" }!!
        val result = recordTool.execute(mapOf("duration" to "5"))

        assertFalse(result.success)
        val fullMessage = result.output + " " + (result.error ?: "")
        assertTrue(fullMessage.contains("CAMERA") || fullMessage.contains("权限"))
    }

    @Test
    fun `camera_record returns error without audio permission`() = runTest {
        // Grant CAMERA, deny RECORD_AUDIO
        every { mockContext.checkSelfPermission(android.Manifest.permission.CAMERA) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkPermission(android.Manifest.permission.CAMERA, any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_DENIED
        every { mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any()) } returns PackageManager.PERMISSION_DENIED
        // Make camera available so code reaches the audio permission check
        every { mockCameraManager.cameraIdList } returns arrayOf("0")
        val mockChars = mockk<CameraCharacteristics>()
        every { mockChars.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK
        every { mockCameraManager.getCameraCharacteristics("0") } returns mockChars

        val recordTool = cameraSkill.tools.find { it.name == "record" }!!
        val result = recordTool.execute(mapOf("duration" to "5"))

        assertFalse(result.success)
        val fullMessage = result.output + " " + (result.error ?: "")
        assertTrue("Expected RECORD_AUDIO/权限 error, got: $fullMessage",
            fullMessage.contains("RECORD_AUDIO") || fullMessage.contains("权限"))
    }

    @Test
    fun `camera_record returns error with no cameras`() = runTest {
        every { mockContext.checkSelfPermission(android.Manifest.permission.CAMERA) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkPermission(android.Manifest.permission.CAMERA, any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkPermission(android.Manifest.permission.RECORD_AUDIO, any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val recordTool = cameraSkill.tools.find { it.name == "record" }!!
        val result = recordTool.execute(mapOf("duration" to "5"))

        assertFalse(result.success)
        val fullMessage = result.output + " " + (result.error ?: "")
        assertTrue(fullMessage.contains("未找到") || fullMessage.contains("摄像头"))
    }
}
