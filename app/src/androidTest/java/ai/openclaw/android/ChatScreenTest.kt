package ai.openclaw.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.openclaw.android.ui.theme.OpenClawTheme
import org.junit.Rule
import org.junit.Test

/**
 * ChatScreen UI 测试
 * 验证消息列表渲染、输入框交互和发送按钮功能
 *
 * 实际 UI 结构（基于 ChatScreen.kt）：
 * - 发送按钮: IconButton 包含 Icon(contentDescription="发送")
 * - 输入框: BasicTextField (testTag="message_input")
 * - Placeholder: Text("输入消息...") 仅在 inputText.isEmpty() 时显示
 */
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    // ==================== 消息列表渲染 ====================

    @Test
    fun testMessageListRendersCorrectly() {
        val mockMessages = listOf(
            ChatMessage(id = "1", role = "user", content = "Hello, world!"),
            ChatMessage(id = "2", role = "assistant", content = "Hi there! How can I help?")
        )

        setContent(mockMessages)

        composeTestRule.onNodeWithText("Hello, world!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hi there! How can I help?").assertIsDisplayed()
    }

    // ==================== 发送按钮 ====================

    @Test
    fun testSendButtonDisabledWhenInputEmpty() {
        setContent()

        composeTestRule.onNodeWithContentDescription("发送")
            .assertIsNotEnabled()
    }

    @Test
    fun testSendButtonEnabledWhenInputNotEmpty() {
        setContent()

        inputText("Test message")

        // placeholder 消失
        composeTestRule.onNodeWithText("输入消息...").assertDoesNotExist()

        // 发送按钮变为可用
        composeTestRule.onNodeWithContentDescription("发送").assertIsEnabled()
    }

    @Test
    fun testSendMessageCallbackCalled() {
        var messageSent = ""
        val sendMessage: (String) -> Unit = { messageSent = it }

        composeTestRule.setContent {
            OpenClawTheme {
                ChatScreen(
                    sendMessage = sendMessage,
                    messages = emptyList(),
                    isLoading = false
                )
            }
        }

        inputText("Test message")

        composeTestRule.onNodeWithContentDescription("发送").performClick()

        composeTestRule.waitForIdle()
        assert(messageSent == "Test message") { "Expected 'Test message' but got '$messageSent'" }
    }

    // ==================== 输入框 ====================

    @Test
    fun testInputFieldCanReceiveText() {
        setContent()

        inputText("Test message")

        composeTestRule.onNodeWithText("输入消息...").assertDoesNotExist()
        composeTestRule.onNodeWithText("Test message").assertExists()
    }

    // ==================== Helper ====================

    /**
     * 向输入框输入文字。
     * BasicTextField 已添加 focusable() 和 isEditable 语义，
     * 可直接使用 performTextInput。
     */
    private fun inputText(text: String) {
        composeTestRule.onNodeWithTag("message_input")
            .performTextInput(text)
    }

    private fun setContent(
        messages: List<ChatMessage> = emptyList(),
        isLoading: Boolean = false
    ) {
        composeTestRule.setContent {
            OpenClawTheme {
                ChatScreen(
                    sendMessage = { },
                    messages = messages,
                    isLoading = isLoading
                )
            }
        }
    }
}
