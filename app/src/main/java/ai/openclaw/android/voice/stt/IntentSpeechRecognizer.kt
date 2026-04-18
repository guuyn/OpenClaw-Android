package ai.openclaw.android.voice.stt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Intent-based speech recognition result.
 */
sealed class SpeechResult {
    data class Success(val text: String) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
    object Cancelled : SpeechResult()
}

/**
 * ActivityResultContract for launching speech recognition via system Intent.
 * Works on devices without Google Voice Search (Huawei, etc.) by using
 * any installed speech recognition app.
 *
 * Usage:
 * ```kotlin
 * val speechLauncher = rememberLauncherForActivityResult(IntentSpeechContract()) { result ->
 *     when (result) {
 *         is SpeechResult.Success -> sendMessage(result.text)
 *         is SpeechResult.Error -> showError(result.message)
 *         SpeechResult.Cancelled -> {} // user cancelled
 *     }
 * }
 * speechLauncher.launch(context)
 * ```
 */
class IntentSpeechContract : ActivityResultContract<Unit, SpeechResult>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): SpeechResult {
        if (resultCode != Activity.RESULT_OK) {
            return SpeechResult.Cancelled
        }
        val matches = intent?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = matches?.firstOrNull()?.trim()
        return if (!text.isNullOrBlank()) {
            SpeechResult.Success(text)
        } else {
            SpeechResult.Error("未识别到语音")
        }
    }
}
