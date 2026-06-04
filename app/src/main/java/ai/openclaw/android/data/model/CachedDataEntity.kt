package ai.openclaw.android.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_data")
data class CachedDataEntity(
    @PrimaryKey val id: String, // 缓存键: "weather:北京"
    val type: String, // 数据类型: "weather", "news"
    @ColumnInfo(name = "query_key") val queryKey: String, // 查询键: "北京"
    @ColumnInfo(name = "data_json") val dataJson: String, // JSON 序列化缓存数据
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    val source: String, // 数据来源: "wttr.in", "open-meteo"
    @ColumnInfo(name = "hit_count") val hitCount: Int = 0
)
