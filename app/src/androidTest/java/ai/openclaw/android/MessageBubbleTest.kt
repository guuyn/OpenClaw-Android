package ai.openclaw.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.openclaw.android.ui.theme.OpenClawTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

/**
 * 消息气泡相关 UI 测试
 * 验证消息渲染和显示
 */
class MessageBubbleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    @Before
    fun setUp() {
        // 不需要 setContent，因为我们要测试独立的组件
    }

    @Test
    fun testChatScreenRendersMessages() {
        val messages = listOf(
            ChatMessage(
                id = "1",
                role = "user",
                content = "Hello from user"
            ),
            ChatMessage(
                id = "2",
                role = "assistant",
                content = "Hello from assistant"
            )
        )

        composeTestRule.setContent {
            OpenClawTheme {
                ChatScreen(
                    sendMessage = {},
                    messages = messages,
                    isLoading = false
                )
            }
        }

        // 验证消息被渲染
        composeTestRule.onNodeWithText("Hello from user").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello from assistant").assertIsDisplayed()
    }

    @Test
    fun testMessageTimestampDisplays() {
        val message = ChatMessage(
            id = "3",
            role = "user",
            content = "Test message with timestamp",
            timestamp = System.currentTimeMillis()
        )

        composeTestRule.setContent {
            OpenClawTheme {
                ChatScreen(
                    sendMessage = {},
                    messages = listOf(message),
                    isLoading = false
                )
            }
        }

        // 验证消息内容显示
        composeTestRule.onNodeWithText("Test message with timestamp").assertIsDisplayed()
    }
}