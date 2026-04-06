package ai.openclaw.android.data.local

import androidx.room.*
import ai.openclaw.android.data.model.SummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {
    
    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId")
    suspend fun getSummaryBySessionId(sessionId: String): SummaryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: SummaryEntity)
    
    @Update
    suspend fun updateSummary(summary: SummaryEntity)
    
    @Delete
    suspend fun deleteSummary(summary: SummaryEntity)
    
    @Query("DELETE FROM summaries WHERE sessionId = :sessionId")
    suspend fun deleteSummaryBySessionId(sessionId: String)
    
    @Query("SELECT * FROM summaries ORDER BY compressedAt DESC")
    fun getAllSummaries(): Flow<List<SummaryEntity>>
}