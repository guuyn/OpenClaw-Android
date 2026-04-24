package ai.openclaw.android

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog

/**
 * 测试工具函数
 */

/**
 * 用于调试语义树
 */
fun ComposeTestRule.dumpSemanticNodes(tag: String = "OpenClawTest") {
    this.onRoot().printToLog(tag = tag)
}