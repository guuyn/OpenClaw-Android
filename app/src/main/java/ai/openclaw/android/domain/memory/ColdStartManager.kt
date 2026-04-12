package ai.openclaw.android.domain.memory

import android.content.Context
import android.content.SharedPreferences
import ai.openclaw.android.LogManager

/**
 * Controls memory system behavior during cold start (first 72 hours).
 *
 * Modes:
 * - SENSORY_ONLY: First 72h. Only PREFERENCE/FACT types stored, no expensive vector ops.
 * - FULL: After 72h. Full memory system active (all types, vector search, BM25).
 */
class ColdStartManager(context: Context) {

    enum class MemoryMode {
        /** First 72h — lightweight: only essential memory types, no vector search. */
        SENSORY_ONLY,
        /** Full memory system active. */
        FULL
    }

    companion object {
        private const val TAG = "ColdStartManager"
        private const val PREFS_NAME = "openclaw_cold_start"
        private const val KEY_FIRST_LAUNCH_TS = "first_launch_ts"
        /** Hours before full memory mode is activated. */
        private const val COLD_START_HOURS = 72L
        private const val HOUR_MS = 3_600_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record the first launch timestamp. Call once from Application.onCreate(). */
    fun markFirstLaunchIfNeeded() {
        if (!prefs.contains(KEY_FIRST_LAUNCH_TS)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH_TS, System.currentTimeMillis()).apply()
            LogManager.shared.log("INFO", TAG, "First launch recorded — cold start mode begins")
        }
    }

    /** Current memory mode based on time since first launch. */
    val mode: MemoryMode
        get() {
            val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_TS, 0L)
            if (firstLaunch == 0L) return MemoryMode.FULL // no record → treat as mature
            val elapsedHours = (System.currentTimeMillis() - firstLaunch) / HOUR_MS
            return if (elapsedHours >= COLD_START_HOURS) MemoryMode.FULL else MemoryMode.SENSORY_ONLY
        }

    /** Hours remaining until full mode. 0 if already in full mode. */
    val hoursRemaining: Long
        get() {
            if (mode == MemoryMode.FULL) return 0L
            val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_TS, 0L)
            val elapsedHours = (System.currentTimeMillis() - firstLaunch) / HOUR_MS
            return (COLD_START_HOURS - elapsedHours).coerceAtLeast(0)
        }
}
