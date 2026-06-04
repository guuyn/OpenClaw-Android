package ai.openclaw.android.prefetch

import android.content.Context
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.model.CachedDataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 预采集服务单例
 *
 * 职责:
 * - 天气缓存读写
 * - 后台刷新天气数据
 * - 过期数据清理
 */
class PrefetchService private constructor() {

    companion object {
        // Weather cache TTL: 30 minutes
        const val WEATHER_TTL_MS = 30 * 60 * 1000L
        const val CACHE_TYPE_WEATHER = "weather"

        @Volatile
        var instance: PrefetchService? = null
            private set

        fun init(context: Context, httpClient: OkHttpClient) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val service = PrefetchService()
                        service._context = context
                        service._httpClient = httpClient
                        service._scope = CoroutineScope(Dispatchers.IO)
                        instance = service
                    }
                }
            }
        }
    }

    private lateinit var _context: Context
    private var _httpClient: OkHttpClient? = null
    private lateinit var _scope: CoroutineScope

    /** 常见城市坐标映射 (复用 WeatherSkill 的逻辑) */
    private val cityCoordinates = mapOf(
        "北京" to Pair(39.9, 116.4),
        "上海" to Pair(31.2, 121.5),
        "广州" to Pair(23.1, 113.3),
        "深圳" to Pair(22.5, 114.1),
        "西安" to Pair(34.3, 108.9),
        "成都" to Pair(30.6, 104.1),
        "杭州" to Pair(30.3, 120.2),
        "南京" to Pair(32.1, 118.8),
        "武汉" to Pair(30.6, 114.3),
        "重庆" to Pair(29.6, 106.5),
        "天津" to Pair(39.1, 117.2),
        "苏州" to Pair(31.3, 120.6),
        "长沙" to Pair(28.2, 112.9),
        "郑州" to Pair(34.8, 113.7),
        "青岛" to Pair(36.1, 120.4),
        "大连" to Pair(38.9, 121.6),
        "厦门" to Pair(24.5, 118.1),
        "昆明" to Pair(25.0, 102.7),
        "哈尔滨" to Pair(45.8, 126.6),
        "沈阳" to Pair(41.8, 123.4),
    )

    /** WMO 天气代码转中文描述 */
    private val weatherCodeToChinese = mapOf(
        0 to "晴", 1 to "大部晴朗", 2 to "多云", 3 to "阴天",
        45 to "雾", 48 to "雾凇",
        51 to "小毛毛雨", 53 to "中毛毛雨", 55 to "大毛毛雨",
        56 to "冻毛毛雨", 57 to "重冻毛毛雨",
        61 to "小雨", 63 to "中雨", 65 to "大雨",
        66 to "小冻雨", 67 to "大冻雨",
        71 to "小雪", 73 to "中雪", 75 to "大雪",
        77 to "雪粒",
        80 to "小阵雨", 81 to "中阵雨", 82 to "大阵雨",
        85 to "小阵雪", 86 to "大阵雪",
        95 to "雷暴", 96 to "雷暴冰雹", 99 to "强雷暴冰雹"
    )

    // ==================== 缓存读写 ====================

    /**
     * 获取缓存的天气数据 (检查是否过期)
     * @return A2UI 格式的天气卡片 JSON, 如果缓存不存在或过期则返回 null
     */
    suspend fun getCachedWeather(location: String): String? {
        val db = AppDatabase.getInstance(_context)
        val dao = db.cachedDataDao()
        val cached = dao.getValidCachedData(CACHE_TYPE_WEATHER, location)
        return cached?.dataJson
    }

    /**
     * 写入天气缓存
     */
    suspend fun cacheWeather(location: String, dataJson: String, source: String) {
        val now = System.currentTimeMillis()
        val entity = CachedDataEntity(
            id = "weather:$location",
            type = CACHE_TYPE_WEATHER,
            queryKey = location,
            dataJson = dataJson,
            fetchedAt = now,
            expiresAt = now + WEATHER_TTL_MS,
            source = source,
            hitCount = 0
        )
        val db = AppDatabase.getInstance(_context)
        db.cachedDataDao().upsertCachedData(entity)
    }

    // ==================== 后台刷新 ====================

    /**
     * 后台刷新多个城市的天气数据
     */
    fun prefetchWeather(
        context: Context,
        httpClient: OkHttpClient,
        locations: List<String>
    ) {
        _scope.launch {
            for (location in locations) {
                try {
                    fetchAndCacheWeather(httpClient, location)
                } catch (e: Exception) {
                    android.util.Log.w("PrefetchService", "Failed to prefetch weather for $location: ${e.message}")
                }
            }
            // 刷新完成后清理过期数据
            cleanExpired()
        }
    }

    /**
     * 获取单个城市天气并写入缓存
     */
    private suspend fun fetchAndCacheWeather(client: OkHttpClient, location: String) {
        // 先尝试 wttr.in
        val wttrResult = tryFetchWttrIn(client, location)
        if (wttrResult != null) {
            cacheWeather(location, wttrResult, "wttr.in")
            return
        }

        // wttr.in 失败，回退到 Open-Meteo
        val meteoResult = tryFetchOpenMeteo(client, location)
        if (meteoResult != null) {
            cacheWeather(location, meteoResult, "open-meteo")
        }
    }

    /**
     * 通过 wttr.in 获取天气并返回 A2UI 卡片 JSON
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun tryFetchWttrIn(client: OkHttpClient, location: String): String? {
        return try {
            val url = "https://wttr.in/${location}?format=3&lang=zh"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val body = response.body?.string()?.trim() ?: ""
            if (body.isEmpty() || body.contains("Unknown location", ignoreCase = true)) return null

            // 构建 A2UI 卡片
            val cityName = parseCityFromWttr(body, location)
            val (condition, temperature) = parseWttrText(body)

            val card = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("weather"),
                    "data" to JsonObject(
                        mapOf(
                            "title" to JsonPrimitive("$cityName · 天气"),
                            "city" to JsonPrimitive(cityName),
                            "condition" to JsonPrimitive(condition),
                            "temperature" to JsonPrimitive(temperature),
                            "feelsLike" to JsonPrimitive("N/A"),
                            "humidity" to JsonPrimitive("N/A"),
                            "wind" to JsonPrimitive("N/A"),
                            "forecast" to JsonArray(emptyList()),
                            "alert" to JsonPrimitive(null)
                        )
                    ),
                    "actions" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "label" to JsonPrimitive("⏰ 降雨提醒"),
                                    "action" to JsonPrimitive("set_rain_reminder"),
                                    "style" to JsonPrimitive("Secondary")
                                )
                            )
                        )
                    )
                )
            )
            Json.encodeToString(JsonObject.serializer(), card)
        } catch (e: Exception) {
            android.util.Log.w("PrefetchService", "wttr.in fetch failed: ${e.message}")
            null
        }
    }

    /**
     * 通过 Open-Meteo 获取天气并返回 A2UI 卡片 JSON
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun tryFetchOpenMeteo(client: OkHttpClient, location: String): String? {
        return try {
            val (lat, lon) = cityCoordinates[location] ?: return null

            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&timezone=auto"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val currentWeather = json.getJSONObject("current_weather")
            val temp = currentWeather.getDouble("temperature")
            val windSpeed = currentWeather.getDouble("windspeed")
            val windDir = currentWeather.getInt("winddirection")
            val weatherCode = currentWeather.getInt("weathercode")

            val weatherDesc = weatherCodeToChinese[weatherCode] ?: "未知天气"
            val windDirDesc = when {
                windDir < 22.5 || windDir >= 337.5 -> "北风"
                windDir < 67.5 -> "东北风"
                windDir < 112.5 -> "东风"
                windDir < 157.5 -> "东南风"
                windDir < 202.5 -> "南风"
                windDir < 247.5 -> "西南风"
                windDir < 292.5 -> "西风"
                else -> "西北风"
            }

            val card = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("weather"),
                    "data" to JsonObject(
                        mapOf(
                            "title" to JsonPrimitive("$location · 天气"),
                            "city" to JsonPrimitive(location),
                            "condition" to JsonPrimitive(weatherDesc),
                            "temperature" to JsonPrimitive("$temp°C"),
                            "feelsLike" to JsonPrimitive("N/A"),
                            "humidity" to JsonPrimitive("N/A"),
                            "wind" to JsonPrimitive("$windDirDesc ${windSpeed}km/h"),
                            "forecast" to JsonArray(emptyList()),
                            "alert" to JsonPrimitive(null)
                        )
                    ),
                    "actions" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "label" to JsonPrimitive("⏰ 降雨提醒"),
                                    "action" to JsonPrimitive("set_rain_reminder"),
                                    "style" to JsonPrimitive("Secondary")
                                )
                            )
                        )
                    )
                )
            )
            Json.encodeToString(JsonObject.serializer(), card)
        } catch (e: Exception) {
            android.util.Log.w("PrefetchService", "Open-Meteo fetch failed: ${e.message}")
            null
        }
    }

    // ==================== 数据清理 ====================

    /**
     * 清理所有过期缓存数据
     */
    suspend fun cleanExpired() {
        val db = AppDatabase.getInstance(_context)
        val deleted = db.cachedDataDao().deleteExpiredData()
        if (deleted > 0) {
            android.util.Log.i("PrefetchService", "Cleaned up $deleted expired cache entries")
        }
    }

    // ==================== wttr.in 文本解析 (复用 WeatherSkill 逻辑) ====================

    private fun parseCityFromWttr(text: String, fallback: String): String {
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        return if (colonIdx > 0) text.substring(0, colonIdx).trim() else fallback
    }

    private fun parseWttrText(text: String): Pair<String, String> {
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        val valuePart = if (colonIdx > 0) text.substring(colonIdx + 1).trim() else text

        val tempRegex = Regex("([+-]?\\d+)°[CF]")
        val tempMatch = tempRegex.find(valuePart)
        val temperature = if (tempMatch != null) "${tempMatch.groupValues[1]}°C" else "N/A"

        val condition = valuePart.replace(tempRegex, "").replace("°", "").trim()

        return condition.ifEmpty { "晴" } to temperature
    }
}
