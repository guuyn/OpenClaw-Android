package ai.openclaw.android.trigger.dao

import androidx.room.*
import ai.openclaw.android.trigger.models.TriggerLog

@Dao
interface TriggerLogDao {
    @Query("SELECT * FROM trigger_logs ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TriggerLog>

    @Query("SELECT * FROM trigger_logs WHERE ruleId = :ruleId ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getByRule(ruleId: String, limit: Int = 20): List<TriggerLog>

    @Insert
    suspend fun insert(log: TriggerLog)

    @Query("DELETE FROM trigger_logs WHERE executedAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
