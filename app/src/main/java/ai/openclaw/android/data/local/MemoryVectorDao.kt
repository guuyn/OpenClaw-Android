package ai.openclaw.android.data.local

import ai.openclaw.android.data.model.MemoryVectorEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: MemoryVectorEntity)
    
    @Query("SELECT * FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun getByMemoryId(memoryId: Long): MemoryVectorEntity?
    
    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun deleteByMemoryId(memoryId: Long)
    
    @Query("SELECT * FROM memory_vectors")
    suspend fun getAll(): List<MemoryVectorEntity>
}