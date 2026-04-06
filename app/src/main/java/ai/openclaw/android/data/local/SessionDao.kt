package ai.openclaw.android.data.local

import androidx.room.*
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)
    
    @Update
    suspend fun updateSession(session: SessionEntity)
    
    @Delete
    suspend fun deleteSession(session: SessionEntity)
    
    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>
    
    @Query("SELECT * FROM sessions WHERE status = :status ORDER BY lastActiveAt DESC")
    fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>>
}