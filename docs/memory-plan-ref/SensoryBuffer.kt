package ai.openclaw.android.memory

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ScreenFeatures(
    val sceneVector: FloatArray,
    val dominantColors: IntArray,
    val uiElementCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenFeatures) return false
        return sceneVector.contentEquals(other.sceneVector) &&
                dominantColors.contentEquals(other.dominantColors) &&
                uiElementCount == other.uiElementCount
    }

    override fun hashCode(): Int {
        var result = sceneVector.contentHashCode()
        result = 31 * result + dominantColors.contentHashCode()
        result = 31 * result + uiElementCount
        return result
    }
}

data class InteractionEntry(
    val id: String,
    val timestamp: Long,
    val text: String,
    val screenFeatures: ScreenFeatures?,
    val ocrSummary: String?,
    val intent: String?
)

class SensoryBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<InteractionEntry>(capacity)

    fun add(entry: InteractionEntry) {
        lock.withLock {
            val now = System.currentTimeMillis()
            // 移除过期条目
            buffer.removeAll { now - it.timestamp > ttlMs }
            // 超出容量时移除最旧的
            while (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    fun getRecent(n: Int): List<InteractionEntry> {
        lock.withLock {
            val now = System.currentTimeMillis()
            // 清理过期条目
            buffer.removeAll { now - it.timestamp > ttlMs }
            return buffer.takeLast(n.coerceAtMost(buffer.size))
        }
    }

    fun clear() {
        lock.withLock {
            buffer.clear()
        }
    }

    val size: Int
        get() = lock.withLock { buffer.size }

    companion object {
        private const val DEFAULT_CAPACITY = 50
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
