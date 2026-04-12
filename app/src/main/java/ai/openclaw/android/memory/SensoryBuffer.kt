package ai.openclaw.android.memory

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Sensory memory buffer — ring buffer holding the most recent interactions in memory.
 *
 * Lifecycle: single session, cleared when session ends.
 * Capacity: last [maxLength] interactions or [maxAgeMinutes] minutes, whichever comes first.
 */
class SensoryBuffer(
    private val maxLength: Int = 50,
    private val maxAgeMinutes: Long = 5
) {
    private val buffer = ArrayDeque<InteractionEntry>(maxLength)
    private val lock = ReentrantLock()

    data class InteractionEntry(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val text: String? = null,
        val screenFeatures: ScreenFeatures? = null,
        val ocrSummary: String? = null,
        val intent: String? = null
    )

    data class ScreenFeatures(
        val sceneVector: FloatArray,
        val dominantColors: IntArray,
        val uiElementCount: Int
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Add an interaction entry.
     * Automatically evicts entries that exceed [maxLength] or [maxAgeMinutes].
     */
    fun add(entry: InteractionEntry) {
        lock.withLock {
            evictExpired()

            while (buffer.size >= maxLength) {
                buffer.removeFirst()
            }

            buffer.addLast(entry)
        }
    }

    /**
     * Return the most recent [n] entries (newest last).
     */
    fun getRecent(n: Int = 10): List<InteractionEntry> {
        lock.withLock {
            return buffer.takeLast(n)
        }
    }

    /**
     * Clear the buffer. Called when the session ends.
     */
    fun clear() {
        lock.withLock {
            buffer.clear()
        }
    }

    val size: Int
        get() = lock.withLock { buffer.size }

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - maxAgeMinutes * 60_000
        while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) {
            buffer.removeFirst()
        }
    }
}
