package ai.openclaw.android.ui.components

import androidx.compose.foundation.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

class HapticHelper(private val haptic: HapticFeedback) {
    fun sendConfirm() = haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    fun onAiReply() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun onLongPress() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    fun onError() = haptic.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberHapticHelper(): HapticHelper {
    val haptic = LocalHapticFeedback.current
    return remember { HapticHelper(haptic) }
}
