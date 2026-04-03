package ai.openclaw.android.notification

import android.content.Context
import android.util.Log

/**
 * ML-based notification classifier placeholder.
 * Returns null to defer to rule-based classification in NotificationClassifier.
 */
class NotificationMLClassifier(private val context: Context) {

    companion object {
        private const val TAG = "NotificationMLClassifier"
    }

    fun classify(notification: SmartNotification): NotificationCategory? {
        return null
    }

    fun close() {}
}
