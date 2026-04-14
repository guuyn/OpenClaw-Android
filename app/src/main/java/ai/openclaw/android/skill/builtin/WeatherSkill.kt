package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import org.json.JSONObject

class WeatherSkill : Skill {
    override val id = "weather"
    override val name = "天气查询"
    override val description = "查询当前天气和天气预报"
    override val version = "2.0.0"
    
    override val instructions = """
# Weather Skill

查询天气信息，支持多数据源（wttr.in + Open-Meteo）。

## 用法
- 用户询问天气时，调用 get_weather 工具
- 无需 API Key，直接可用
- 支持中文城市名（如 '西安'、'北京'）
- 自动回退：wttr.in 失败时尝试 Open-Meteo
"""
    
    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        WeatherTool()
    )
    
    // 常见城市坐标映射（中文 → 经纬度）
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
    
    // WMO 天气代码转中文描述
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
    
    private inner class WeatherTool : SkillTool {
        override val name = "get_weather"
        override val description = "获取指定位置的天气信息。支持中文城市名（如'北京'、'西安'）和英文城市名。"
        override val parameters = mapOf(
            "location" to SkillParam(
                type = "string",
                description = "位置名称，如 '北京'、'西安' 或 'Beijing'",
                required = true
            )
        )
        
        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val location = params["location"] as? String
            android.util.Log.d("WeatherSkill", "execute called, location=$location, httpClient=$httpClient")
            if (location == null || location.isBlank()) {
                return SkillResult(false, "", "缺少 location 参数")
            }
            
            val client = httpClient ?: return SkillResult(false, "", "HTTP client not initialized")
            
            // 先尝试 wttr.in
            val wttrResult = tryWttrIn(client, location)
            if (wttrResult.success) {
                return wttrResult
            }
            
            // wttr.in 失败，回退到 Open-Meteo
            android.util.Log.d("WeatherSkill", "wttr.in failed, falling back to Open-Meteo")
            return tryOpenMeteo(client, location)
        }
        
        /**
         * 尝试使用 wttr.in 获取天气
         */
        private fun tryWttrIn(client: OkHttpClient, location: String): SkillResult {
            return try {
                val url = "https://wttr.in/${location}?format=3&lang=zh"
                android.util.Log.d("WeatherSkill", "Requesting wttr.in: $url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                android.util.Log.d("WeatherSkill", "wttr.in response code: ${response.code}")
                if (!response.isSuccessful) {
                    return SkillResult(false, "", "wttr.in HTTP error: ${response.code}")
                }
                
                val body = response.body?.string()?.trim() ?: ""
                android.util.Log.d("WeatherSkill", "wttr.in response body: $body")
                
                if (body.isEmpty() || body.contains("Unknown location", ignoreCase = true)) {
                    return SkillResult(false, "", "wttr.in: 未找到位置 '$location'")
                }
                
                // 构建 v2 A2UI 卡片 JSON
                val cardJson = buildWeatherCardV2(location, wttrText = body)
                SkillResult(true, "[A2UI]$cardJson[/A2UI]")
            } catch (e: IOException) {
                android.util.Log.w("WeatherSkill", "wttr.in IOException: ${e.message}")
                SkillResult(false, "", "wttr.in network error: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.w("WeatherSkill", "wttr.in Exception: ${e.message}")
                SkillResult(false, "", "wttr.in error: ${e.message}")
            }
        }
        
        /**
         * 回退到 Open-Meteo API（无需 API Key，支持全球）
         */
        private fun tryOpenMeteo(client: OkHttpClient, location: String): SkillResult {
            return try {
                val (lat, lon) = getCoordinates(location)
                    ?: return SkillResult(false, "", "无法确定 '$location' 的坐标，请提供更具体的城市名")
                
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&timezone=auto"
                android.util.Log.d("WeatherSkill", "Requesting Open-Meteo: $url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                android.util.Log.d("WeatherSkill", "Open-Meteo response code: ${response.code}")
                if (!response.isSuccessful) {
                    return SkillResult(false, "", "Open-Meteo HTTP error: ${response.code}")
                }
                
                val body = response.body?.string() ?: ""
                android.util.Log.d("WeatherSkill", "Open-Meteo response: $body")
                
                val json = JSONObject(body)
                val currentWeather = json.getJSONObject("current_weather")
                val temp = currentWeather.getDouble("temperature")
                val windSpeed = currentWeather.getDouble("windspeed")
                val windDir = currentWeather.getInt("winddirection")
                val weatherCode = currentWeather.getInt("weathercode")
                val isDay = currentWeather.getInt("is_day")
                
                val weatherDesc = weatherCodeToChinese[weatherCode] ?: "未知天气"
                val dayNight = if (isDay == 1) "白天" else "夜间"
                
                // 格式化风向
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
                
                // 构建 v2 A2UI 卡片 JSON
                val cardJson = buildWeatherCardV2OpenMeteo(
                    location, weatherDesc, temp, windDirDesc, windSpeed
                )
                android.util.Log.d("WeatherSkill", "Formatted weather v2 card: $cardJson")
                SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                
            } catch (e: Exception) {
                android.util.Log.e("WeatherSkill", "Open-Meteo error: ${e.message}", e)
                SkillResult(false, "", "天气查询失败：${e.message}")
            }
        }
        
        /**
         * 获取城市坐标，支持中文和英文城市名
         */
        private fun getCoordinates(location: String): Pair<Double, Double>? {
            // 先查中文城市名映射
            cityCoordinates[location]?.let { return it }
            
            // 尝试英文城市名映射
            val englishNames = mapOf(
                "beijing" to "北京", "shanghai" to "上海", "guangzhou" to "广州",
                "shenzhen" to "深圳", "xian" to "西安", "xi'an" to "西安",
                "chengdu" to "成都", "hangzhou" to "杭州", "nanjing" to "南京",
                "wuhan" to "武汉", "chongqing" to "重庆", "tianjin" to "天津"
            )
            englishNames[location.lowercase()]?.let { return cityCoordinates[it] }
            
            // 尝试使用 Open-Meteo 地理编码 API
            return tryGeocoding(location)
        }
        
        /**
         * 使用 Open-Meteo 地理编码 API 获取坐标
         */
        private fun tryGeocoding(location: String): Pair<Double, Double>? {
            val client = httpClient ?: return null
            return try {
                val url = "https://geocoding-api.open-meteo.com/v1/search?name=$location&count=1&language=zh"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val results = json.getJSONArray("results")
                    if (results.length() > 0) {
                        val first = results.getJSONObject(0)
                        Pair(first.getDouble("latitude"), first.getDouble("longitude"))
                    } else null
                } else null
            } catch (e: Exception) {
                android.util.Log.w("WeatherSkill", "Geocoding failed: ${e.message}")
                null
            }
        }
        
        fun setHttpClient(client: OkHttpClient) {
            httpClient = client
        }
    }

    // ==================== v2 A2UI Card JSON 构建 ====================

    /** 从 wttr.in 响应构建 v2 天气卡片 */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildWeatherCardV2(location: String, wttrText: String): String {
        val cityName = parseCityFromWttr(wttrText, location)
        val (condition, temperature) = parseWttrText(wttrText)
        val feelsLike = extractExtraFromWttr(wttrText, "FeelsLike")
        val humidity = extractExtraFromWttr(wttrText, "Humidity")
        val wind = extractExtraFromWttr(wttrText, "Wind")

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("weather"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("$cityName · 天气"),
                        "city" to JsonPrimitive(cityName),
                        "condition" to JsonPrimitive(condition),
                        "temperature" to JsonPrimitive(temperature),
                        "feelsLike" to (if (feelsLike != null) JsonPrimitive(feelsLike) else JsonPrimitive("N/A")),
                        "humidity" to (if (humidity != null) JsonPrimitive(humidity) else JsonPrimitive("N/A")),
                        "wind" to (if (wind != null) JsonPrimitive(wind) else JsonPrimitive("N/A")),
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
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📅 7天预报"),
                                "action" to JsonPrimitive("expand_forecast"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📤 分享"),
                                "action" to JsonPrimitive("share_weather"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    /** 从 Open-Meteo 响应构建 v2 天气卡片 */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildWeatherCardV2OpenMeteo(
        location: String,
        condition: String,
        temperature: Double,
        windDirDesc: String,
        windSpeed: Double
    ): String {
        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("weather"),
                "data" to JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("$location · 天气"),
                        "city" to JsonPrimitive(location),
                        "condition" to JsonPrimitive(condition),
                        "temperature" to JsonPrimitive("${temperature}°C"),
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
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📅 7天预报"),
                                "action" to JsonPrimitive("expand_forecast"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📤 分享"),
                                "action" to JsonPrimitive("share_weather"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    /** 从 wttr.in format=3 文本中提取城市名 */
    private fun parseCityFromWttr(text: String, fallback: String): String {
        // format=3: "西安：+20°C" 或 "New York: +20°C"
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        return if (colonIdx > 0) text.substring(0, colonIdx).trim() else fallback
    }

    /** 从 wttr.in format=3 文本中提取天气状况和温度 */
    private fun parseWttrText(text: String): Pair<String, String> {
        // format=3: "西安：+20°C" 或 "New York: +20°C"
        val colonIdx = text.indexOfAny(charArrayOf('：', ':'))
        val valuePart = if (colonIdx > 0) text.substring(colonIdx + 1).trim() else text

        // 尝试提取温度
        val tempRegex = Regex("([+-]?\\d+)°[CF]")
        val tempMatch = tempRegex.find(valuePart)
        val temperature = if (tempMatch != null) "${tempMatch.groupValues[1]}°C" else "N/A"

        // 移除温度后剩余部分作为天气状况
        val condition = valuePart.replace(tempRegex, "").replace("°", "").trim()

        return condition.ifEmpty { "晴" } to temperature
    }

    /** 尝试从 wttr.in 响应中提取额外信息（目前 format=3 不包含这些） */
    private fun extractExtraFromWttr(text: String, key: String): String? = null
    
    override fun initialize(context: SkillContext) {
        android.util.Log.d("WeatherSkill", "initialize called, httpClient from context: ${context.httpClient}")
        // Pass HTTP client to the tool
        (tools[0] as WeatherTool).setHttpClient(context.httpClient)
        android.util.Log.d("WeatherSkill", "httpClient set to: $httpClient")
    }
    
    override fun cleanup() {
        // No cleanup needed
    }
}