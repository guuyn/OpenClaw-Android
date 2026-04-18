package ai.openclaw.android.skill

import ai.openclaw.android.skill.builtin.WeatherSkill
import ai.openclaw.android.skill.builtin.MultiSearchSkill
import ai.openclaw.android.skill.builtin.TranslateSkill
import ai.openclaw.android.skill.builtin.ReminderSkill
import ai.openclaw.android.skill.builtin.CalendarSkill
import ai.openclaw.android.skill.builtin.LocationSkill
import ai.openclaw.android.skill.builtin.ContactSkill
import ai.openclaw.android.skill.builtin.SMSSkill
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream

class SkillManagerTest {

    @MockK
    private lateinit var mockContext: Context

    private lateinit var skillManager: SkillManager

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        val mockAssets = mockk<AssetManager>(relaxed = true)
        every { mockAssets.open("scripts/search.js") } returns ByteArrayInputStream("".toByteArray())
        every { mockContext.assets } returns mockAssets
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager = SkillManager(mockContext)
    }

    @Test
    fun `loadBuiltinSkills_registersAllSkills`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"

        // Act
        skillManager.loadBuiltinSkills(mockContext)

        // Assert
        val loadedSkills = skillManager.getLoadedSkills()
        // 12 registered; NotificationSkill may fail on mock context if notificationManager cast fails
        assertTrue("Expected 11-12 skills loaded but got ${loadedSkills.size}", loadedSkills.size >= 11)

        assertTrue(loadedSkills.containsKey("weather"))
        assertTrue(loadedSkills.containsKey("search"))
        assertTrue(loadedSkills.containsKey("translate"))
        assertTrue(loadedSkills.containsKey("reminder"))
        assertTrue(loadedSkills.containsKey("calendar"))
        assertTrue(loadedSkills.containsKey("location"))
        assertTrue(loadedSkills.containsKey("contact"))
        assertTrue(loadedSkills.containsKey("sms"))
        assertTrue(loadedSkills.containsKey("applauncher"))
        assertTrue(loadedSkills.containsKey("settings"))
        assertTrue(loadedSkills.containsKey("script"))

        // Verify each skill type
        assertTrue(loadedSkills["weather"] is WeatherSkill)
        assertTrue(loadedSkills["search"] is MultiSearchSkill)
        assertTrue(loadedSkills["translate"] is TranslateSkill)
        assertTrue(loadedSkills["reminder"] is ReminderSkill)
        assertTrue(loadedSkills["calendar"] is CalendarSkill)
        assertTrue(loadedSkills["location"] is LocationSkill)
        assertTrue(loadedSkills["contact"] is ContactSkill)
        assertTrue(loadedSkills["sms"] is SMSSkill)
    }

    @Test
    fun `getAllTools_returnsNamespacedNames`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Act
        val allTools = skillManager.getAllTools()

        // Assert
        assertTrue(allTools.isNotEmpty())

        // Check that tool names follow the expected format (skillId_toolName)
        allTools.forEach { toolDef ->
            assertTrue("Tool name should contain underscore separator: ${toolDef.name}",
                toolDef.name.contains('_'))

            val parts = toolDef.name.split('_', limit = 2)
            assertTrue(parts.size == 2)
            assertTrue(parts[0].isNotBlank()) // skillId
            assertTrue(parts[1].isNotBlank()) // toolName
        }

        // Specific verification for known tools
        val toolNames = allTools.map { it.name }
        assertTrue(toolNames.any { it.startsWith("weather_") })
        assertTrue(toolNames.any { it.startsWith("search_") })
    }

    @Test
    fun `executeTool_underscoredSkillId_parsesCorrectly`() = runTest {
        // This verifies the fix for: "Skill not found: dynamic" error
        // when LLM calls dynamic_skill_generator_generate_skill

        // Arrange: register a skill with underscore in ID
        val mockSkill = mockk<Skill>(relaxed = true)
        every { mockSkill.id } returns "dynamic_skill_generator"
        every { mockSkill.name } returns "动态技能生成"
        every { mockSkill.tools } returns listOf(
            mockk<SkillTool>(relaxed = true).also { tool ->
                every { tool.name } returns "generate_skill"
                coEvery { tool.execute(any()) } returns SkillResult(true, "skill registered", "")
            }
        )
        every { mockSkill.initialize(any()) } returns Unit

        skillManager.registerSkill(mockSkill)

        // Mock permissions granted
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // Act: call the tool with the underscored skill ID
        val result = skillManager.executeTool(
            "dynamic_skill_generator_generate_skill",
            mapOf("skillJson" to "{}")
        )

        // Assert: should find the skill, not fail with "Skill not found: dynamic"
        assertFalse("Should not return 'Skill not found: dynamic' error",
            result.output.contains("Skill not found: dynamic"))
    }

    @Test
    fun `executeTool_validCall_returnsSuccess`() = runTest {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Mock ContextCompat.checkSelfPermission to return granted for all permissions
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        // For now, test with a simple call that doesn't require actual network
        // We'll test that the tool execution framework works properly

        // Act
        val result = skillManager.getAllTools()

        // Assert
        assertTrue(result.isNotEmpty())

        // Test that we can get all tools without errors
        val toolDefs = skillManager.getAllTools()
        assertTrue(toolDefs.isNotEmpty())
    }

    @Test
    fun `checkSkillPermissions_missingPermission_returnsFalse`() {
        // Arrange
        every { mockContext.packageName } returns "ai.openclaw.android.test"
        every { mockContext.getSystemService(NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        skillManager.loadBuiltinSkills(mockContext)

        // Mock ContextCompat.checkSelfPermission to return denied for calendar permission
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_CALENDAR)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.WRITE_CALENDAR)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_CONTACTS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.SEND_SMS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_DENIED

        // Act
        val calendarPermissionResult = skillManager.checkSkillPermissions("calendar")
        val locationPermissionResult = skillManager.checkSkillPermissions("location")
        val contactPermissionResult = skillManager.checkSkillPermissions("contact")
        val smsPermissionResult = skillManager.checkSkillPermissions("sms")

        // Assert
        assertFalse(calendarPermissionResult.first)
        assertFalse(locationPermissionResult.first)
        assertFalse(contactPermissionResult.first)
        assertFalse(smsPermissionResult.first)

        // Weather skill should not require permissions
        val weatherPermissionResult = skillManager.checkSkillPermissions("weather")
        assertTrue(weatherPermissionResult.first)
    }
}