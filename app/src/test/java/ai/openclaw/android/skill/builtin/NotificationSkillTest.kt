package ai.openclaw.android.skill.builtin

import android.content.Context
import android.app.NotificationManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class NotificationSkillTest {

    private lateinit var mockContext: Context
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var notificationSkill: NotificationSkill

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        notificationSkill = NotificationSkill(mockContext)
    }

    @Test
    fun `skill has correct metadata`() {
        assertEquals("notification", notificationSkill.id)
        assertEquals("通知管理", notificationSkill.name)
        assertEquals(5, notificationSkill.tools.size)
    }

    @Test
    fun `list notifications tool has correct parameters`() {
        val tool = notificationSkill.tools.find { it.name == "list_notifications" }!!
        assertEquals("list_notifications", tool.name)
        assertTrue(tool.parameters.containsKey("packageName"))
        assertTrue(tool.parameters.containsKey("limit"))
        assertTrue(tool.parameters.containsKey("includeRead"))
    }

    @Test
    fun `send notification tool has correct parameters`() {
        val tool = notificationSkill.tools.find { it.name == "send_notification" }!!
        assertEquals("send_notification", tool.name)
        assertTrue(tool.parameters.containsKey("title"))
        assertTrue(tool.parameters.containsKey("text"))
        assertTrue(tool.parameters.containsKey("importance"))
        // title and text are required
        assertEquals(true, tool.parameters["title"]?.required)
        assertEquals(true, tool.parameters["text"]?.required)
    }

    @Test
    fun `delete notification tool has correct parameters`() {
        val tool = notificationSkill.tools.find { it.name == "delete_notification" }!!
        assertEquals("delete_notification", tool.name)
        assertTrue(tool.parameters.containsKey("notificationId"))
    }

    @Test
    fun `clear notifications tool exists`() {
        val tool = notificationSkill.tools.find { it.name == "clear_notifications" }!!
        assertEquals("clear_notifications", tool.name)
    }

    @Test
    fun `mark notification read tool exists`() {
        val tool = notificationSkill.tools.find { it.name == "mark_notification_read" }!!
        assertEquals("mark_notification_read", tool.name)
    }
}
