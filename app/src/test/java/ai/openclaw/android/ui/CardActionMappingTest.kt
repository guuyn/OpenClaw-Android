package ai.openclaw.android.ui

import ai.openclaw.android.CardActionResult
import ai.openclaw.android.ChatMessage
import ai.openclaw.android.mapCardActionToMessage
import ai.openclaw.android.test.TestDataFactory
import org.junit.Assert.*
import org.junit.Test

/**
 * mapCardActionToMessage() 纯函数单元测试
 *
 * 验证 Card 操作映射到消息的完整逻辑：
 * - set_reminder → SendMessage("设置提醒")
 * - retry/resend → 有用户消息时 ResendLast，否则 NoOp
 * - cancel → SendMessage("取消")
 * - confirm → SendMessage("确认")
 * - 未知 action → SendMessage(label) 或 NoOp
 * - 空 label 的未知 action → NoOp
 */
class CardActionMappingTest {

    // ==================== set_reminder ====================

    @Test
    fun `set_reminder action maps to SendMessage with 设置提醒`() {
        val action = TestDataFactory.setReminderAction()
        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("设置提醒", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `set_reminder works regardless of message history`() {
        val action = TestDataFactory.setReminderAction()
        val msgs = listOf(TestDataFactory.createUserMessage())

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("设置提醒", (result as CardActionResult.SendMessage).text)
    }

    // ==================== retry / resend ====================

    @Test
    fun `retry action with user messages returns ResendLast`() {
        val action = TestDataFactory.retryAction()
        val msgs = listOf(
            TestDataFactory.createUserMessage(content = "Hello")
        )

        val result = mapCardActionToMessage(action, msgs)

        assertTrue("Should return ResendLast when user messages exist", result is CardActionResult.ResendLast)
    }

    @Test
    fun `retry action without user messages returns NoOp`() {
        val action = TestDataFactory.retryAction()
        val msgs = listOf(
            TestDataFactory.createAssistantMessage(content = "AI only")
        )

        val result = mapCardActionToMessage(action, msgs)

        assertTrue("Should return NoOp when no user messages exist", result is CardActionResult.NoOp)
    }

    @Test
    fun `retry action with empty list returns NoOp`() {
        val action = TestDataFactory.retryAction()

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.NoOp)
    }

    @Test
    fun `resend action with user messages returns ResendLast`() {
        val action = TestDataFactory.resendAction()
        val msgs = listOf(TestDataFactory.createUserMessage())

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.ResendLast)
    }

    @Test
    fun `resend action without user messages returns NoOp`() {
        val action = TestDataFactory.resendAction()
        val msgs = listOf(TestDataFactory.createAssistantMessage())

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.NoOp)
    }

    @Test
    fun `retry with multiple messages including user returns ResendLast`() {
        val action = TestDataFactory.retryAction()
        val msgs = listOf(
            TestDataFactory.createUserMessage(content = "First"),
            TestDataFactory.createAssistantMessage(content = "Reply"),
            TestDataFactory.createUserMessage(content = "Second")
        )

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.ResendLast)
    }

    // ==================== cancel ====================

    @Test
    fun `cancel action maps to SendMessage with 取消`() {
        val action = TestDataFactory.cancelAction()

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("取消", (result as CardActionResult.SendMessage).text)
    }

    // ==================== confirm ====================

    @Test
    fun `confirm action maps to SendMessage with 确认`() {
        val action = TestDataFactory.confirmAction()

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("确认", (result as CardActionResult.SendMessage).text)
    }

    // ==================== Unknown actions ====================

    @Test
    fun `unknown action with label sends label as message`() {
        val action = TestDataFactory.customAction(label = "查看详情")

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("查看详情", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `unknown action with empty label returns NoOp`() {
        val action = TestDataFactory.emptyLabelAction(action = "unknown_action")

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.NoOp)
    }

    @Test
    fun `unknown action with whitespace-only label returns NoOp`() {
        val action = TestDataFactory.cardAction(label = "   ", action = "mystery")

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.NoOp)
    }

    @Test
    fun `unknown action with special characters in label sends label`() {
        val action = TestDataFactory.cardAction(label = "查看 🔍 详情", action = "custom")

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("查看 🔍 详情", (result as CardActionResult.SendMessage).text)
    }

    // ==================== Button style doesn't affect mapping ====================

    @Test
    fun `Primary style confirm still sends 确认`() {
        val action = TestDataFactory.cardAction(label = "确认", action = "confirm", style = ButtonStyle.Primary)

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("确认", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `Secondary style cancel still sends 取消`() {
        val action = TestDataFactory.cardAction(label = "取消", action = "cancel", style = ButtonStyle.Secondary)

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("取消", (result as CardActionResult.SendMessage).text)
    }

    // ==================== Message list variations ====================

    @Test
    fun `only user messages in list — retry returns ResendLast`() {
        val action = TestDataFactory.retryAction()
        val msgs = listOf(
            TestDataFactory.createUserMessage(content = "A"),
            TestDataFactory.createUserMessage(content = "B")
        )

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.ResendLast)
    }

    @Test
    fun `mixed roles — retry returns ResendLast`() {
        val action = TestDataFactory.resendAction()
        val msgs = listOf(
            TestDataFactory.createAssistantMessage(),
            TestDataFactory.createUserMessage(),
            TestDataFactory.createAssistantMessage()
        )

        val result = mapCardActionToMessage(action, msgs)

        assertTrue(result is CardActionResult.ResendLast)
    }

    // ==================== Edge cases ====================

    @Test
    fun `case-sensitive action matching — RETRY is unknown`() {
        val action = TestDataFactory.cardAction(label = "RETRY", action = "RETRY")

        val result = mapCardActionToMessage(action, emptyList())

        // "RETRY" != "retry", so it falls through to unknown action
        // Since label is "RETRY" (not blank), it sends the label
        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("RETRY", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `very long label is sent as message`() {
        val longLabel = "a".repeat(1000)
        val action = TestDataFactory.cardAction(label = longLabel, action = "unknown")

        val result = mapCardActionToMessage(action, emptyList())

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals(longLabel, (result as CardActionResult.SendMessage).text)
    }
}
