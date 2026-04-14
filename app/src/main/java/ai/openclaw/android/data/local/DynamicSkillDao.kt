package ai.openclaw.android.data.local

import androidx.room.*
import ai.openclaw.android.data.model.DynamicSkillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DynamicSkillDao {
    @Query("SELECT * FROM dynamic_skills WHERE enabled = 1 ORDER BY createdAt")
    fun getAllEnabled(): Flow<List<DynamicSkillEntity>>

    @Query("SELECT * FROM dynamic_skills WHERE enabled = 1 ORDER BY createdAt")
    suspend fun getAllEnabledList(): List<DynamicSkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: DynamicSkillEntity): Long

    @Delete
    suspend fun delete(skill: DynamicSkillEntity)

    @Query("UPDATE dynamic_skills SET enabled = 0 WHERE id = :id")
    suspend fun disable(id: String)

    @Query("SELECT * FROM dynamic_skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DynamicSkillEntity?
}
