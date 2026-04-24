package ai.openclaw.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.openclaw.android.ui.theme.OpenClawTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * EnergyBar 组件 UI 测试
 * 验证能量条组件的渲染和状态变化
 */
class EnergyBarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // 不需要全局 setContent，因为我们在每个测试中设置
    }

    @Test
    fun testEnergyBarRendersWithoutCrash() {
        composeTestRule.setContent {
            OpenClawTheme {
                EnergyBar(isFocused = false)
            }
        }

        // 验证 EnergyBar 组件能够渲染而不崩溃
        composeTestRule.onRoot().printToString() // Just ensure composition works
    }

    @Test
    fun testEnergyBarAnimationStateChange() {
        composeTestRule.setContent {
            OpenClawTheme {
                androidx.compose.runtime.CompositionLocalProvider {
                    val isFocusedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    
                    // 更新状态以测试动画
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        isFocusedState.value = true
                    }
                    
                    EnergyBar(isFocused = isFocusedState.value)
                }
            }
        }

        // 等待状态更新
        composeTestRule.waitForIdle()
        
        // 验证组件在状态变化后仍然正常工作
        composeTestRule.onRoot().printToString()
    }
}