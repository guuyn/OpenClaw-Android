package ai.openclaw.android.trigger.dao

import androidx.room.*
import ai.openclaw.android.trigger.models.TriggerRule

@Dao
interface TriggerRuleDao {
    @Query("SELECT * FROM trigger_rules ORDER BY createdAt DESC")
    suspend fun getAll(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<TriggerRule>

    @Query("SELECT * FROM trigger_rules WHERE id = :id")
    suspend fun getById(id: String): TriggerRule?

    @Query("SELECT * FROM trigger_rules WHERE source = :source")
    suspend fun getBySource(source: String): List<TriggerRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: TriggerRule)

    @Delete
    suspend fun delete(rule: TriggerRule)

    @Query("DELETE FROM trigger_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE trigger_rules SET enabled = :enabled, updatedAt = strftime('%s', 'now') * 1000 WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
