package ai.openclaw.android.data.local

import androidx.room.*
import ai.openclaw.android.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySessionId(sessionId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesBySessionIdWithLimit(sessionId: String, limit: Int, offset: Int): List<MessageEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySessionId(sessionId: String)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id IN (:messageIds)")
    suspend fun deleteMessagesByIds(sessionId: String, messageIds: List<Long>)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND timestamp < :beforeTimestamp")
    suspend fun deleteMessagesBeforeTimestamp(sessionId: String, beforeTimestamp: Long)
    
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCountBySessionId(sessionId: String): Int
    
    @Query("SELECT SUM(tokenCount) FROM messages WHERE sessionId = :sessionId")
    suspend fun getTotalTokenCountBySessionId(sessionId: String): Int
}