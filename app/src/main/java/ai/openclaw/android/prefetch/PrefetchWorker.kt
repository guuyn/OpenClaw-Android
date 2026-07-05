package ai.openclaw.android.prefetch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import java.util.concurrent.TimeUnit

/**
 * WorkManager 定期预采集 Worker
 *
 * 每 30 分钟触发:
 * 1. 初始化 PrefetchService
 * 2. 读取预采集城市列表 (SharedPreferences "prefetch_cities", 默认 ["北京"])
 * 3. 调用 prefetchWeather() 刷新天气缓存
 * 4. 调用 cleanExpired() 清理过期数据
 */
class PrefetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PrefetchWorker"
        private const val PREFETCH_CITIES_KEY = "prefetch_cities"
        private val DEFAULT_CITIES = listOf("北京")
    }

    override suspend fun doWork(): Result {
        android.util.Log.i(TAG, "Starting prefetch work...")

        return try {
            // 1. 初始化 PrefetchService（如果未初始化）
            if (PrefetchService.instance == null) {
                val httpClient = Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                PrefetchService.init(applicationContext, httpClient)
            }

            // 2. 读取预采集城市列表
            val prefs = applicationContext.getSharedPreferences(PREFETCH_CITIES_KEY, Context.MODE_PRIVATE)
            val citiesJson = prefs.getString(PREFETCH_CITIES_KEY, null)
            val cities = if (citiesJson != null) {
                try {
                    val array = org.json.JSONArray(citiesJson)
                    (0 until array.length()).map { array.getString(it) }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to parse prefetch cities, using defaults", e)
                    DEFAULT_CITIES
                }
            } else {
                DEFAULT_CITIES
            }

            android.util.Log.i(TAG, "Prefetching weather for cities: $cities")

            // 3. 创建 HTTP client 用于后台刷新
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            // 4. 调用 prefetchWeather() 刷新
            PrefetchService.instance?.prefetchWeather(applicationContext, httpClient, cities)

            // 5. 清理过期数据 (prefetchWeather 内部也会调, 但再调一次确保)
            PrefetchService.instance?.cleanExpired()

            android.util.Log.i(TAG, "Prefetch work completed successfully")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Prefetch work failed: ${e.message}", e)
            Result.retry()
        }
    }
}
