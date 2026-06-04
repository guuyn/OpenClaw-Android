# Spec: T005 Pre-fetch Data Layer (预采集数据层)

## Goal
后台定时刷新天气/新闻等高频查询数据，80% 查询走缓存而非实时请求。

## 架构设计

### 1. 数据库层 (Room)
**新增 Entity**: `CachedDataEntity`
```kotlin
@Entity(tableName = "cached_data")
data class CachedDataEntity(
    @PrimaryKey val id: String,          // 缓存键: "weather:北京"
    val type: String,                     // 数据类型: "weather", "news"
    val queryKey: String,                 // 查询键: "北京"
    val dataJson: String,                 // JSON 序列化缓存数据
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    val source: String,                   // 数据来源: "wttr.in", "open-meteo"
    @ColumnInfo(name = "hit_count") val hitCount: Int = 0
)
```

### 2. DAO 层
**新增 DAO**: `CachedDataDao`
```kotlin
@Dao
interface CachedDataDao {
    @Query("SELECT * FROM cached_data WHERE type = :type AND query_key = :queryKey AND expires_at > :now ORDER BY fetched_at DESC LIMIT 1")
    suspend fun getValidCachedData(type: String, queryKey: String, now: Long = System.currentTimeMillis()): CachedDataEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedData(entity: CachedDataEntity)
    
    @Query("DELETE FROM cached_data WHERE expires_at < :now")
    suspend fun deleteExpiredData(now: Long = System.currentTimeMillis()): Int
    
    @Query("SELECT * FROM cached_data WHERE expires_at < :threshold AND expires_at > :now ORDER BY fetched_at ASC")
    suspend fun getStaleDataBefore(threshold: Long, now: Long = System.currentTimeMillis()): List<CachedDataEntity>
    
    @Query("SELECT * FROM cached_data WHERE type = :type ORDER BY hit_count DESC LIMIT :limit")
    suspend fun getTopCachedByType(type: String, limit: Int = 20): List<CachedDataEntity>
}
```

### 3. AppDatabase migration v7→v8
- 新增 cached_data 表
- version 7 → 8

### 4. Worker 层
**新增 Worker**: `PrefetchWorker` (CoroutineWorker)
- 每 30 分钟触发一次
- 读取用户预采集配置（从 SharedPreferences/ConfigManager）
- 调用 WeatherSkill 后台刷新天气缓存
- 清理过期数据（deleteExpiredData）
- 需要网络约束

### 5. WeatherSkill 适配
修改 `WeatherTool.execute()` 方法:
1. 先调用 `prefetchService.getCachedWeather(location)` 
2. 缓存命中 → 直接返回 SkillResult
3. 缓存过期/不存在 → 正常请求 API
4. API 成功后 → 写入缓存
5. 返回结果

### 6. PrefetchService 单例
业务逻辑层，封装:
- `getCachedWeather(location)`: 获取天气缓存（检查过期）
- `refreshWeather(location)`: 主动刷新天气
- `prefetchAll()`: 刷新所有预采集城市
- `cleanExpired()`: 清理过期数据
- TTL: 天气 30 分钟

### 7. 注册 PrefetchWorker
在 `GatewayService.onCreate()` 或 `OpenClawApplication.onCreate()` 中注册:
```kotlin
val workRequest = PeriodicWorkRequestBuilder<PrefetchWorker>(30, TimeUnit.MINUTES)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "prefetch_worker",
    ExistingPeriodicWorkPolicy.KEEP,
    workRequest
)
```

## 完成标准
- `./gradlew assembleDebug` BUILD SUCCESSFUL
- `./gradlew test` 全部通过
- 不破坏现有功能
