package ai.openclaw.android.data.local

import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long
    
    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?
    
    @Query("SELECT * FROM memories WHERE memoryType = :type ORDER BY priority DESC, createdAt DESC LIMIT :limit")
    suspend fun getByType(type: MemoryType, limit: Int): List<MemoryEntity>
    
    @Query("SELECT * FROM memories WHERE priority >= 4 ORDER BY priority DESC, lastAccessedAt DESC LIMIT :limit")
    suspend fun getHighPriority(limit: Int): List<MemoryEntity>
    
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>
    
    @Query("UPDATE memories SET lastAccessedAt = :timestamp, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun updateAccess(id: Long, timestamp: Long)
    
    @Query("SELECT * FROM memories WHERE lastAccessedAt < :threshold AND accessCount < :minAccessCount")
    suspend fun findStale(threshold: Long, minAccessCount: Int): List<MemoryEntity>
    
    @Delete
    suspend fun delete(memory: MemoryEntity)
}