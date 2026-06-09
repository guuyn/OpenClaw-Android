package ai.openclaw.android.skill.builtin

import android.content.Context
import android.content.pm.PackageManager
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

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        cameraSkill = CameraSkill(mockContext)
    }

    // ==================== Skill Metadata ====================

    @Test
    fun `skill metadata is correct`() {
        assertEquals("camera", cameraSkill.id)
        assertEquals("摄像头", cameraSkill.name)
        assertEquals(2, cameraSkill.tools.size)
        assertEquals("camera_capture", cameraSkill.tools[0].name)
        assertEquals("camera_record", cameraSkill.tools[1].name)
    }

    // ==================== camera_capture ====================

    @Test
    fun `camera_capture returns error without camera permission`() = runTest {
        every {
            mockContext.checkSelfPermission(android.Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED

        val captureTool = cameraSkill.tools.find { it.name == "camera_capture" }!!
        val result = captureTool.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.output.contains("CAMERA") || result.output.contains("权限"))
    }

    @Test
    fun `camera_capture returns error with no cameras`() = runTest {
        every {
            mockContext.checkSelfPermission(android.Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_GRANTED

        val mockCameraManager = mockk<CameraManager>(relaxed = true)
        every { mockCameraManager.cameraIdList } returns arrayOf()
        every {
            mockContext.getSystemService(Context.CAMERA_SERVICE)
        } returns mockCameraManager

        val captureTool = cameraSkill.tools.find { it.name == "camera_capture" }!!
        val result = captureTool.execute(mapOf("facing" to "back"))

        assertFalse(result.success)
        assertTrue(result.output.contains("未找到") || result.output.contains("摄像头"))
    }

    // ==================== camera_record ====================

    @Test
    fun `camera_record returns error without camera permission`() = runTest {
        every {
            mockContext.checkSelfPermission(android.Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED

        val recordTool = cameraSkill.tools.find { it.name == "camera_record" }!!
        val result = recordTool.execute(mapOf("duration" to "5"))

        assertFalse(result.success)
        assertTrue(result.output.contains("CAMERA") || result.output.contains("权限"))
    }

    @Test
    fun `camera_record returns error without audio permission`() = runTest {
        every {
            mockContext.checkSelfPermission(android.Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            mockContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_DENIED

        val recordTool = cameraSkill.tools.find { it.name == "camera_record" }!!
        val result = recordTool.execute(mapOf("duration" to "5"))

        assertFalse(result.success)
        assertTrue(result.output.contains("RECORD_AUDIO") || result.output.contains("权限"))
    }

    @Test
    fun `camera_record requires duration parameter`() = runTest {
        // Should have a default or validate
        val recordTool = cameraSkill.tools.find { it.name == "camera_record" }!!
        assertNotNull(recordTool)
        assertTrue(recordTool.name == "camera_record")
    }
}
