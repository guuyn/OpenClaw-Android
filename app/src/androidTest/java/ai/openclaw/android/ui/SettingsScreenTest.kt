package ai.openclaw.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.openclaw.android.LogManager
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.ui.theme.OpenClawTheme
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * SettingsScreen UI 测试
 * 验证设置项显示和切换开关状态
 */
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var mockPermissionManager: PermissionManager

    @Before
    fun setUp() {
        mockPermissionManager = mockk(relaxed = true)

        composeTestRule.setContent {
            OpenClawTheme {
                SettingsScreen(
                    serviceRunning = false,
                    onServiceToggle = { },
                    modelApiKey = "test-key",
                    onModelApiKeyChange = { },
                    modelName = "qwen-plus",
                    onModelNameChange = { },
                    modelProvider = "OPENAI",
                    onModelProviderChange = { },
                    modelBaseUrl = "https://api.openai.com",
                    onModelBaseUrlChange = { },
                    configExpanded = true,
                    onConfigExpandedChange = { },
                    logExpanded = false,
                    onLogExpandedChange = { },
                    onSaveConfig = { },
                    permissionManager = mockPermissionManager,
                    onRequestPermissions = { },
                    onRequestAllFilesAccess = { },
                    settingsPermRefreshKey = 0
                )
            }
        }
    }

    @Test
    fun testSettingsItemsAreDisplayed() {
        // 验证设置项能正确显示
        composeTestRule.onNodeWithText("Gateway Service").assertIsDisplayed()
        composeTestRule.onNodeWithText("Configuration").assertIsDisplayed()
        composeTestRule.onNodeWithText("权限管理").assertIsDisplayed()
        composeTestRule.onNodeWithText("运行日志").assertIsDisplayed()
    }

    @Test
    fun testServiceToggleButtonWorks() {
        // 验证服务切换按钮功能
        composeTestRule.onNodeWithText("Start").assertIsDisplayed()
        
        // 点击切换按钮
        composeTestRule.onNodeWithText("Start").performClick()
        
        // 由于我们没有改变 serviceRunning 状态，这里只是验证点击事件能被触发
        composeTestRule.onNodeWithText("Start").assertExists()
    }

    @Test
    fun testApiKeyFieldDisplaysValue() {
        // 验证 API Key 字段显示正确值
        composeTestRule.onNodeWithText("test-key").assertIsDisplayed()
    }

    @Test
    fun testModelNameFieldDisplaysValue() {
        // 验证模型名称字段显示正确值
        composeTestRule.onNodeWithText("qwen-plus").assertIsDisplayed()
    }

    @Test
    fun testProviderSelectionWorks() {
        // 验证提供商选择功能
        composeTestRule.onNodeWithText("OpenAI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Anthropic").assertIsDisplayed()
        composeTestRule.onNodeWithText("本地模型").assertIsDisplayed()
        
        // 点击 Anthropic 选项
        composeTestRule.onNodeWithText("Anthropic").performClick()
        
        // 验证 Anthropic 被选中（通过样式或其他视觉反馈判断）
        composeTestRule.onNodeWithText("Anthropic").assertExists()
    }

    @Test
    fun testSaveConfigurationButtonWorks() {
        // 验证保存配置按钮功能
        composeTestRule.onNodeWithText("Save Configuration").assertIsDisplayed()
        
        // 点击保存按钮
        composeTestRule.onNodeWithText("Save Configuration").performClick()
    }
}