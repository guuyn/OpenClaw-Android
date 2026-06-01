package ai.openclaw.android.trigger.v2.dao

import androidx.room.*
import ai.openclaw.android.trigger.v2.models.TriggerEventEntity

/**
 * TriggerEventDao — v2 触发事件日志 DAO
 */
@Dao
interface TriggerEventDao {
    @Query("SELECT * FROM trigger_events_v2 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TriggerEventEntity>

    @Query("SELECT * FROM trigger_events_v2 WHERE triggerId = :triggerId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByTriggerId(triggerId: String, limit: Int = 20): List<TriggerEventEntity>

    @Query("SELECT * FROM trigger_events_v2 WHERE decision = :decision ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByDecision(decision: String, limit: Int = 20): List<TriggerEventEntity>

    @Query("SELECT * FROM trigger_events_v2 WHERE timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startMs: Long, endMs: Long): List<TriggerEventEntity>

    @Query("SELECT COUNT(*) FROM trigger_events_v2 WHERE triggerId = :triggerId")
    suspend fun countByTriggerId(triggerId: String): Int

    @Query("SELECT COUNT(*) FROM trigger_events_v2 WHERE decision = :decision")
    suspend fun countByDecision(decision: String): Int

    @Insert
    suspend fun insert(event: TriggerEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<TriggerEventEntity>)

    @Query("DELETE FROM trigger_events_v2 WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM trigger_events_v2 WHERE triggerId = :triggerId")
    suspend fun deleteByTriggerId(triggerId: String)

    @Query("UPDATE trigger_events_v2 SET userFeedback = :feedback WHERE id = :eventId")
    suspend fun updateUserFeedback(eventId: String, feedback: String)
}
