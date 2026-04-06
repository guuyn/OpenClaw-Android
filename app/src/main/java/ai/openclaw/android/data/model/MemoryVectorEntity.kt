package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_vectors")
data class MemoryVectorEntity(
    @PrimaryKey val memoryId: Long,
    val vector: FloatArray,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryVectorEntity
        return memoryId == other.memoryId && vector.contentEquals(other.vector)
    }
    override fun hashCode(): Int = 31 * memoryId.hashCode() + vector.contentHashCode()
}