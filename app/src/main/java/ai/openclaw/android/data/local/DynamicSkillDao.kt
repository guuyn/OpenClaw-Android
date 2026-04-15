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

    /** 获取 30 天未使用的已启用技能 */
    @Query("SELECT * FROM dynamic_skills WHERE enabled = 1 AND lastUsedAt < :threshold ORDER BY lastUsedAt")
    suspend fun getEnabledSkillsLastUsedBefore(threshold: Long): List<DynamicSkillEntity>

    /** 获取 90 天已停用的技能 */
    @Query("SELECT * FROM dynamic_skills WHERE enabled = 0 AND lastUsedAt < :threshold ORDER BY lastUsedAt")
    suspend fun getDisabledSkillsDisabledBefore(threshold: Long): List<DynamicSkillEntity>

    /** 更新技能最后使用时间 */
    @Query("UPDATE dynamic_skills SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)

    /** 启用已停用的技能 */
    @Query("UPDATE dynamic_skills SET enabled = 1 WHERE id = :id")
    suspend fun enable(id: String)

    /** 按 ID 删除技能 */
    @Query("DELETE FROM dynamic_skills WHERE id = :id")
    suspend fun deleteById(id: String)
}
