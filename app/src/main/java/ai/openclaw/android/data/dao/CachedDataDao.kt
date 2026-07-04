package ai.openclaw.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.openclaw.android.data.model.CachedDataEntity

@Dao
interface CachedDataDao {
    @Query(
        "SELECT * FROM cached_data WHERE type = :type AND query_key = :queryKey " +
                "AND expires_at > :now ORDER BY fetched_at DESC LIMIT 1"
    )
    suspend fun getValidCachedData(
        type: String,
        queryKey: String,
        now: Long = System.currentTimeMillis()
    ): CachedDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedData(entity: CachedDataEntity)

    @Query("DELETE FROM cached_data WHERE expires_at < :now")
    suspend fun deleteExpiredData(now: Long = System.currentTimeMillis()): Int

    @Query(
        "SELECT * FROM cached_data WHERE expires_at < :threshold AND expires_at > :now " +
                "ORDER BY fetched_at ASC"
    )
    suspend fun getStaleDataBefore(
        threshold: Long,
        now: Long = System.currentTimeMillis()
    ): List<CachedDataEntity>

    @Query("SELECT * FROM cached_data WHERE type = :type ORDER BY hit_count DESC LIMIT :limit")
    suspend fun getTopCachedByType(type: String, limit: Int = 20): List<CachedDataEntity>
}
