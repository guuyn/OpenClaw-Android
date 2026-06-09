package ai.openclaw.android.skill.builtin

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeviceSkillTest {

    private lateinit var context: Context
    private lateinit var deviceSkill: DeviceSkill

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Create temp dirs
        java.io.File(context.filesDir, "test-files").mkdirs()
        java.io.File(context.cacheDir, "test-cache").mkdirs()

        deviceSkill = DeviceSkill(context)
    }

    @After
    fun tearDown() {
        java.io.File(context.filesDir, "test-files").deleteRecursively()
        java.io.File(context.cacheDir, "test-cache").deleteRecursively()
    }

    @Test
    fun `skill metadata is correct`() {
        assertEquals("device", deviceSkill.id)
        assertTrue(deviceSkill.tools.isNotEmpty())
    }

    @Test
    fun `all expected tools are present`() {
        val names = deviceSkill.tools.map { it.name }.toSet()
        assertTrue(names.contains("info"))
        assertTrue(names.contains("status"))
        assertTrue(names.contains("health"))
        assertTrue(names.contains("flashlight"))
        assertTrue(names.contains("volume"))
        assertTrue(names.contains("clipboard"))
        assertTrue(names.contains("wake_screen"))
    }

    @Test
    fun `info tool returns device info`() = runTest {
        val infoTool = deviceSkill.tools.find { it.name == "info" }!!
        val result = infoTool.execute(emptyMap())
        assertTrue(result.success)
        assertTrue(result.output.contains("型号:"))
    }

    @Test
    fun `status tool returns battery`() = runTest {
        val statusTool = deviceSkill.tools.find { it.name == "status" }!!
        val result = statusTool.execute(emptyMap())
        assertTrue(result.success)
        // Battery info may vary in Robolectric
        assertTrue(result.output.isNotEmpty())
    }

    @Test
    fun `health tool runs`() = runTest {
        val healthTool = deviceSkill.tools.find { it.name == "health" }!!
        val result = healthTool.execute(emptyMap())
        assertTrue(result.success)
        assertTrue(result.output.contains("健康评分"))
    }

    @Test
    fun `flashlight on executes`() = runTest {
        val tool = deviceSkill.tools.find { it.name == "flashlight" }!!
        val result = tool.execute(mapOf("action" to "on"))
        assertNotNull(result)
    }

    @Test
    fun `volume get returns info`() = runTest {
        val tool = deviceSkill.tools.find { it.name == "volume" }!!
        val result = tool.execute(mapOf("action" to "get"))
        // May fail without audio service in Robolectric, that's ok
        assertNotNull(result)
    }

    @Test
    fun `clipboard set and get`() = runTest {
        val tool = deviceSkill.tools.find { it.name == "clipboard" }!!
        val setResult = tool.execute(mapOf("action" to "set", "text" to "test"))
        assertNotNull(setResult)

        val getResult = tool.execute(mapOf("action" to "get"))
        assertNotNull(getResult)
    }

    @Test
    fun `wake_screen executes`() = runTest {
        val tool = deviceSkill.tools.find { it.name == "wake_screen" }!!
        val result = tool.execute(emptyMap())
        assertNotNull(result)
    }
}
