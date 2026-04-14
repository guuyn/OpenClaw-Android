package ai.openclaw.android

import ai.openclaw.android.ui.ButtonStyle
import ai.openclaw.android.ui.CardAction
import ai.openclaw.android.ui.RiskLevel
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for card action button callback mapping.
 * Tests the pure function mapCardActionToMessage (not Compose UI).
 */
class ActionCallbackTest {

    // ========== Test: set_reminder generates correct message ==========

    @Test
    fun `set_reminder action generates set reminder message`() {
        val action = CardAction(label = "设置提醒", action = "set_reminder", style = ButtonStyle.Primary)
        val messages = listOf(ChatMessage(role = "assistant", content = "需要设置提醒吗？"))

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("设置提醒", (result as CardActionResult.SendMessage).text)
    }

    // ========== Test: retry action resends last message ==========

    @Test
    fun `retry action resends last user message`() {
        val action = CardAction(label = "重试", action = "retry", style = ButtonStyle.Primary)
        val messages = listOf(
            ChatMessage(role = "user", content = "今天天气如何？"),
            ChatMessage(role = "assistant", content = "天气卡片")
        )

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.ResendLast)
    }

    @Test
    fun `resend action also resends last user message`() {
        val action = CardAction(label = "重新发送", action = "resend", style = ButtonStyle.Secondary)
        val messages = listOf(
            ChatMessage(role = "user", content = "再试一次"),
            ChatMessage(role = "assistant", content = "响应失败")
        )

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.ResendLast)
    }

    @Test
    fun `retry action with no user messages returns NoOp`() {
        val action = CardAction(label = "重试", action = "retry", style = ButtonStyle.Primary)
        val messages = listOf(ChatMessage(role = "assistant", content = "你好"))

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.NoOp)
    }

    @Test
    fun `resend action with no user messages returns NoOp`() {
        val action = CardAction(label = "重发", action = "resend", style = ButtonStyle.Secondary)
        val messages = listOf(ChatMessage(role = "assistant", content = "你好"))

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.NoOp)
    }

    // ========== Test: unknown action falls back to label text ==========

    @Test
    fun `unknown action falls back to label text`() {
        val action = CardAction(label = "查看详情", action = "view_details", style = ButtonStyle.Secondary)
        val messages = listOf(ChatMessage(role = "assistant", content = "卡片"))

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("查看详情", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `unknown action with empty label returns NoOp`() {
        val action = CardAction(label = "", action = "unknown_action", style = ButtonStyle.Secondary)
        val messages = listOf(ChatMessage(role = "assistant", content = "卡片"))

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.NoOp)
    }

    // ========== Test: confirm and cancel actions ==========

    @Test
    fun `cancel action generates cancel message`() {
        val action = CardAction(label = "取消操作", action = "cancel", style = ButtonStyle.Secondary)
        val messages = emptyList<ChatMessage>()

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("取消", (result as CardActionResult.SendMessage).text)
    }

    @Test
    fun `confirm action generates confirm message`() {
        val action = CardAction(label = "确认发送", action = "confirm", style = ButtonStyle.Primary)
        val messages = emptyList<ChatMessage>()

        val result = mapCardActionToMessage(action, messages)

        assertTrue(result is CardActionResult.SendMessage)
        assertEquals("确认", (result as CardActionResult.SendMessage).text)
    }

    // ========== Test: risk level mapping ==========

    @Test
    fun `risk level enum has three values`() {
        val levels = RiskLevel.values()
        assertEquals(3, levels.size)
        assertEquals(RiskLevel.Low, levels[0])
        assertEquals(RiskLevel.Medium, levels[1])
        assertEquals(RiskLevel.High, levels[2])
    }

    @Test
    fun `risk level ordinal order is correct`() {
        assertTrue(RiskLevel.Low.ordinal < RiskLevel.Medium.ordinal)
        assertTrue(RiskLevel.Medium.ordinal < RiskLevel.High.ordinal)
    }
}
