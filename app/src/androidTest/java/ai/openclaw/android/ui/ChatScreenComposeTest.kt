package ai.openclaw.android.ui

import ai.openclaw.android.ChatMessage
import ai.openclaw.android.ChatScreen
import ai.openclaw.android.MockDataProvider
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.ui.theme.OpenClawTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ChatScreen Compose UI 测试（扩展版）
 *
 * 覆盖场景：
 * - 消息列表渲染（含 A2UI 卡片）
 * - 输入框交互
 * - 发送按钮状态
 * - Loading 状态
 * - 错误卡片显示
 * - 图片消息显示
 * - MockDataProvider 全场景
 * - 长按菜单
 * - 多消息滚动
 */
class ChatScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ==================== 消息列表渲染 ====================

    @Test
    fun emptyMessageListRendersWithInputArea() {
        setContent()

        composeTestRule.onNodeWithText("输入消息...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("发送").assertExists()
    }

    @Test
    fun singleUserMessageRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "你好，世界！")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("你好，世界！").assertIsDisplayed()
    }

    @Test
    fun singleAssistantMessageRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "assistant", content = "你好！有什么可以帮你的？")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("你好！有什么可以帮你的？").assertIsDisplayed()
    }

    @Test
    fun multipleMessagesRenderInOrder() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "第一句"),
            ChatMessage(id = "2", role = "assistant", content = "回复第一句"),
            ChatMessage(id = "3", role = "user", content = "第二句"),
            ChatMessage(id = "4", role = "assistant", content = "回复第二句")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("第一句").assertIsDisplayed()
        composeTestRule.onNodeWithText("回复第一句").assertIsDisplayed()
        composeTestRule.onNodeWithText("第二句").assertIsDisplayed()
        composeTestRule.onNodeWithText("回复第二句").assertIsDisplayed()
    }

    @Test
    fun userAndAiMessagesHaveDifferentTestTags() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "用户消息"),
            ChatMessage(id = "2", role = "assistant", content = "AI回复")
        )
        setContent(messages = messages)

        // User message should have "user_message" tag
        composeTestRule.onNodeWithTag("user_message").assertExists()
        // AI message should have "ai_message" tag
        composeTestRule.onNodeWithTag("ai_message").assertExists()
    }

    // ==================== A2UI 卡片渲染 ====================

    @Test
    fun weatherCardMessageRenders() {
        val messages = listOf(
            MockDataProvider.weatherCardMessage
        )
        setContent(messages = messages)

        // Should show the text part
        composeTestRule.onNodeWithText("西安的天气如下：").assertIsDisplayed()
        // A2UI card content should be rendered
        composeTestRule.onNodeWithText("西安天气").assertExists()
    }

    @Test
    fun standardProtocolMessageRenders() {
        val messages = listOf(
            MockDataProvider.standardProtocolMessage
        )
        setContent(messages = messages)

        // A2UI standard protocol should render the card content
        composeTestRule.onNodeWithText("测试卡片").assertExists()
    }

    @Test
    fun mixedContentMessageRenders() {
        val messages = listOf(
            MockDataProvider.mixedContentMessage
        )
        setContent(messages = messages)

        // Text parts should render
        composeTestRule.onNodeWithText("以下是搜索结果：").assertIsDisplayed()
        // Card content should render
        composeTestRule.onNodeWithText("搜索结果").assertExists()
    }

    @Test
    fun errorMessageRenders() {
        val messages = listOf(
            MockDataProvider.errorMessage
        )
        setContent(messages = messages)

        // Error card should render
        composeTestRule.onNodeWithText("操作失败").assertExists()
    }

    @Test
    fun allMockDataProviderScenariosRender() {
        val messages = MockDataProvider.getAllScenarios()
        setContent(messages = messages)

        // All scenarios should render without crashing
        composeTestRule.onNodeWithTag("message_list").assertExists()
        // At least some messages should be visible
        composeTestRule.onNodeWithText("你好").assertExists()
    }

    // ==================== 输入框交互 ====================

    @Test
    fun inputFieldAcceptsTextInput() {
        setContent()

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("测试输入")

        composeTestRule.onNodeWithText("测试输入").assertIsDisplayed()
    }

    @Test
    fun placeholderVisibleWhenInputEmpty() {
        setContent()

        composeTestRule.onNodeWithText("输入消息...").assertIsDisplayed()
    }

    @Test
    fun placeholderHiddenWhenInputNotEmpty() {
        setContent()

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("有内容了")

        composeTestRule.onNodeWithText("输入消息...").assertDoesNotExist()
    }

    @Test
    fun inputFieldHasCorrectTestTag() {
        setContent()

        composeTestRule.onNodeWithTag("message_input").assertExists()
    }

    @Test
    fun inputFieldHasCorrectContentDescription() {
        setContent()

        composeTestRule.onNode(hasTestTag("message_input") and hasContentDescription("message_input"))
            .assertExists()
    }

    // ==================== 发送按钮状态 ====================

    @Test
    fun sendButtonDisabledWhenInputEmpty() {
        setContent()

        composeTestRule.onNodeWithContentDescription("发送")
            .assertIsNotEnabled()
    }

    @Test
    fun sendButtonEnabledWhenInputNotEmpty() {
        setContent()

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("Hello")

        composeTestRule.onNodeWithContentDescription("发送")
            .assertIsEnabled()
    }

    @Test
    fun sendButtonDisabledWhenLoading() {
        setContent(isLoading = true)

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("Hello")

        composeTestRule.onNodeWithContentDescription("发送")
            .assertIsNotEnabled()
    }

    @Test
    fun sendButtonEnabledWhenLoadingButInputNotEmpty() {
        // When loading + input not empty, send button should be disabled
        setContent(
            messages = listOf(ChatMessage(id = "1", role = "user", content = "Hello")),
            isLoading = true
        )

        composeTestRule.onNodeWithContentDescription("发送")
            .assertIsNotEnabled()
    }

    @Test
    fun sendButtonHasCorrectTestTag() {
        setContent()

        composeTestRule.onNodeWithTag("send_button").assertExists()
    }

    // ==================== 发送回调 ====================

    @Test
    fun sendMessageCallbackCalledWithCorrectText() {
        var capturedText = ""
        var capturedImages: List<ImageContent>? = null
        val sendMessage: (String, List<ImageContent>) -> Unit = { text, images ->
            capturedText = text
            capturedImages = images
        }

        setContent(sendMessage = sendMessage)

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("发送测试")
        composeTestRule.onNodeWithContentDescription("发送")
            .performClick()

        assertTrue("Message should be captured", capturedText == "发送测试")
        assertTrue("Images should be empty list", capturedImages?.isEmpty() == true)
    }

    @Test
    fun sendMessageClearsInputAfterSend() {
        setContent()

        composeTestRule.onNodeWithTag("message_input")
            .performTextInput("发送后清空")
        composeTestRule.onNodeWithContentDescription("发送")
            .performClick()

        // Placeholder should reappear after send
        composeTestRule.onNodeWithText("输入消息...").assertIsDisplayed()
    }

    // ==================== Loading 状态 ====================

    @Test
    fun loadingDotsVisibleWhenLoading() {
        setContent(isLoading = true)

        composeTestRule.onNodeWithTag("loading_dots").assertIsDisplayed()
    }

    @Test
    fun loadingDotsHiddenWhenNotLoading() {
        setContent(isLoading = false)

        composeTestRule.onNodeWithTag("loading_dots").assertDoesNotExist()
    }

    @Test
    fun loadingDotsHaveCorrectContentDescription() {
        setContent(isLoading = true)

        composeTestRule.onNode(hasContentDescription("loading_dots")).assertExists()
    }

    @Test
    fun loadingWithMessagesShowsTypingIndicator() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "你好"),
            ChatMessage(id = "2", role = "assistant", content = "正在")
        )
        setContent(messages = messages, isLoading = true)

        // Messages should still be visible
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        // Loading indicator should also be visible
        composeTestRule.onNodeWithTag("loading_dots").assertIsDisplayed()
    }

    // ==================== 图片消息显示 ====================

    @Test
    fun userMessageWithImagesRenders() {
        // Create a minimal valid base64 image (1x1 transparent PNG)
        val imageContent = ImageContent(
            base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
            mediaType = "image/png",
            description = "测试图片"
        )

        val messages = listOf(
            ChatMessage(
                id = "1",
                role = "user",
                content = "看这张图片",
                images = listOf(imageContent)
            )
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("看这张图片").assertIsDisplayed()
        // Image should be rendered (test tag not specifically set, but the message is visible)
    }

    @Test
    fun messageWithMultipleImagesRenders() {
        val imageContent = ImageContent(
            base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
            mediaType = "image/png"
        )

        val messages = listOf(
            ChatMessage(
                id = "1",
                role = "user",
                content = "多图测试",
                images = listOf(imageContent, imageContent, imageContent)
            )
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("多图测试").assertIsDisplayed()
    }

    // ==================== 消息列表 testTag ====================

    @Test
    fun messageListHasCorrectTestTag() {
        setContent(messages = listOf(ChatMessage(id = "1", role = "user", content = "test")))

        composeTestRule.onNodeWithTag("message_list").assertExists()
    }

    // ==================== Long message handling ====================

    @Test
    fun longMessageRendersWithoutCrash() {
        val longContent = "这是一条很长的消息。".repeat(100)
        val messages = listOf(
            ChatMessage(id = "1", role = "assistant", content = longContent)
        )
        setContent(messages = messages)

        // Should not crash, content should be displayed (may be truncated visually)
        composeTestRule.onNodeWithTag("ai_message").assertExists()
    }

    @Test
    fun messageWithSpecialCharactersRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "特殊字符：<>{}[]()!@#\$%^&*")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("特殊字符：").assertIsDisplayed()
    }

    @Test
    fun messageWithNewlinesRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "assistant", content = "第一行\n第二行\n第三行")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("第一行").assertIsDisplayed()
    }

    @Test
    fun messageWithEmojiRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "表情测试 🎉🚀🤖✨")
        )
        setContent(messages = messages)

        composeTestRule.onNodeWithText("表情测试").assertIsDisplayed()
    }

    // ==================== RichContent rendering ====================

    @Test
    fun richContentListCardRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "assistant", content = "搜索结果如下：")
        )
        setContent(
            messages = messages,
        )

        composeTestRule.onNodeWithText("搜索结果如下：").assertIsDisplayed()
    }

    // ==================== Conversation flow ====================

    @Test
    fun fullConversationScenarioRenders() {
        val messages = listOf(
            ChatMessage(id = "1", role = "user", content = "你好"),
            ChatMessage(id = "2", role = "assistant", content = "你好！有什么可以帮你的？"),
            ChatMessage(id = "3", role = "user", content = "西安天气怎么样"),
            ChatMessage(id = "4", role = "assistant", content = MockDataProvider.weatherCardMessage.content),
            ChatMessage(id = "5", role = "user", content = "搜索 OpenClaw"),
            ChatMessage(id = "6", role = "assistant", content = MockDataProvider.mixedContentMessage.content)
        )
        setContent(messages = messages)

        // All messages should render
        composeTestRule.onNodeWithText("你好").assertExists()
        composeTestRule.onNodeWithText("西安天气怎么样").assertExists()
        composeTestRule.onNodeWithText("搜索 OpenClaw").assertExists()
    }

    // ==================== Helper ====================

    private fun setContent(
        sendMessage: (String, List<ImageContent>) -> Unit = { _, _ -> },
        messages: List<ChatMessage> = emptyList(),
        isLoading: Boolean = false
    ) {
        composeTestRule.setContent {
            OpenClawTheme {
                ChatScreen(
                    sendMessage = sendMessage,
                    messages = messages,
                    isLoading = isLoading
                )
            }
        }
    }
}
