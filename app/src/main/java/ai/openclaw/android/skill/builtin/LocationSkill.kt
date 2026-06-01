package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.coroutines.resume

class LocationSkill(private val context: Context) : Skill {
    override val id = "location"
    override val name = "定位"
    override val description = "获取GPS位置和周边地点信息"
    override val version = "2.0.0"
    
    override val instructions = """
# Location Skill

获取当前GPS位置、地址解析、周边地点搜索。

## 用法
- get_location: 获取当前GPS坐标
- get_address: 将坐标转换为地址（逆地理编码）
- search_places: 搜索附近的地点（使用 OpenStreetMap）

## A2UI 卡片输出格式（参考）
[A2UI]{"type":"location","data":{"title":"当前位置","address":"地址文本","latitude":"39.9","longitude":"116.4"},"actions":[{"label":"🗺️ 打开地图","action":"open_map","style":"Secondary"}]}[/A2UI]
"""
    
    data class PlaceInfo(val name: String, val distance: String = "", val lat: String? = null, val lon: String? = null)

    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        // get_location tool
        object : SkillTool {
            override val name = "get_location"
            override val description = "获取当前GPS位置"
            override val parameters = emptyMap<String, SkillParam>()
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                if (!this@LocationSkill.hasLocationPermission()) {
                    return SkillResult(false, "", "需要位置权限")
                }
                
                return try {
                    val location = getCurrentLocation()
                    if (location != null) {
                        val cardJson = buildLocationCardV2(
                            title = "当前位置",
                            address = "纬度 ${location.latitude}, 经度 ${location.longitude}",
                            latitude = location.latitude.toString(),
                            longitude = location.longitude.toString()
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        SkillResult(false, "", "无法获取位置，请确保GPS已开启")
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "获取位置失败: ${e.message}")
                }
            }
            
            private suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { cont ->
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                
                val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                
                if (!hasGps && !hasNetwork) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        cont.resume(location)
                    }
                }
                
                val provider = if (hasGps) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    
                    val lastKnown = locationManager.getLastKnownLocation(provider)
                    if (lastKnown != null && cont.isActive) {
                        locationManager.removeUpdates(listener)
                        cont.resume(lastKnown)
                    }
                    
                    cont.invokeOnCancellation {
                        locationManager.removeUpdates(listener)
                    }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        },
        
        // get_address tool (reverse geocoding)
        object : SkillTool {
            override val name = "get_address"
            override val description = "将GPS坐标转换为地址"
            override val parameters = mapOf(
                "latitude" to SkillParam("number", "纬度", true),
                "longitude" to SkillParam("number", "经度", true)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val lat = (params["latitude"] as? Number)?.toDouble()
                if (lat == null) return SkillResult(false, "", "缺少 latitude 参数")
                
                val lon = (params["longitude"] as? Number)?.toDouble()
                if (lon == null) return SkillResult(false, "", "缺少 longitude 参数")
                
                val client = httpClient ?: return SkillResult(false, "", "HTTP client not initialized")
                
                return try {
                    val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "OpenClaw-Android/1.0")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        return SkillResult(false, "", "HTTP error: ${response.code}")
                    }
                    
                    val body = response.body?.string() ?: return SkillResult(false, "", "Empty response")
                    val address = parseAddressFromJson(body)
                    
                    if (address != null) {
                        val cardJson = buildLocationCardV2(
                            title = "位置信息",
                            address = address,
                            latitude = lat.toString(),
                            longitude = lon.toString()
                        )
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        SkillResult(false, "", "无法解析地址")
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "地址解析失败: ${e.message}")
                }
            }
            
            private fun parseAddressFromJson(json: String): String? {
                return try {
                    val displayNameKey = "\"display_name\":\""
                    val startIndex = json.indexOf(displayNameKey)
                    if (startIndex == -1) return null
                    
                    val valueStart = startIndex + displayNameKey.length
                    val valueEnd = json.indexOf("\"", valueStart)
                    if (valueEnd == -1) return null
                    
                    json.substring(valueStart, valueEnd)
                        .replace("\\n", ", ")
                        .replace("\\\"", "\"")
                } catch (e: Exception) {
                    null
                }
            }
        },
        
        // search_places tool
        object : SkillTool {
            override val name = "search_places"
            override val description = "搜索附近的地点"
            override val parameters = mapOf(
                "query" to SkillParam("string", "搜索关键词（如 '餐厅'、'加油站'）", true),
                "latitude" to SkillParam("number", "中心纬度（可选，默认当前位置）", false),
                "longitude" to SkillParam("number", "中心经度（可选，默认当前位置）", false),
                "radius" to SkillParam("number", "搜索半径（米，默认1000）", false, 1000)
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val query = params["query"] as? String
                if (query.isNullOrBlank()) return SkillResult(false, "", "缺少 query 参数")
                
                val client = httpClient ?: return SkillResult(false, "", "HTTP client not initialized")
                
                return try {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "OpenClaw-Android/1.0")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        return SkillResult(false, "", "HTTP error: ${response.code}")
                    }
                    
                    val body = response.body?.string() ?: return SkillResult(false, "", "Empty response")
                    val places = parsePlacesFromJson(body)
                    
                    if (places.isEmpty()) {
                        return SkillResult(true, "未找到相关地点: $query")
                    }

                    val nearbyItems = places.map { p ->
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(p.name),
                                "distance" to JsonPrimitive(p.distance)
                            )
                        )
                    }

                    val firstLat = places.firstOrNull()?.lat
                    val firstLon = places.firstOrNull()?.lon

                    val cardJson = buildLocationCardV2(
                        title = "搜索结果: $query",
                        address = "找到 ${places.size} 个地点",
                        latitude = firstLat,
                        longitude = firstLon,
                        nearby = JsonArray(nearbyItems)
                    )
                    SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                } catch (e: Exception) {
                    SkillResult(false, "", "搜索失败: ${e.message}")
                }
            }
            
            private fun parsePlacesFromJson(json: String): List<PlaceInfo> {
                val places = mutableListOf<PlaceInfo>()
                return try {
                    val nameRegex = Regex("\"display_name\":\"([^\"]+)\"")
                    val latRegex = Regex("\"lat\":\"([^\"]+)\"")
                    val lonRegex = Regex("\"lon\":\"([^\"]+)\"")
                    
                    val names = nameRegex.findAll(json).map { it.groupValues[1].replace("\\n", ", ") }.toList()
                    val lats = latRegex.findAll(json).map { it.groupValues[1] }.toList()
                    val lons = lonRegex.findAll(json).map { it.groupValues[1] }.toList()
                    
                    val count = minOf(names.size, 5)
                    for (i in 0 until count) {
                        places.add(PlaceInfo(
                            name = names.getOrNull(i) ?: "",
                            lat = lats.getOrNull(i),
                            lon = lons.getOrNull(i)
                        ))
                    }
                    places
                } catch (e: Exception) {
                    places
                }
            }
        }
    )
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun initialize(context: SkillContext) {
        httpClient = context.httpClient
    }
    
    override fun cleanup() {}

    // ==================== v2 A2UI Card JSON 构建 ====================

    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildLocationCardV2(
        title: String,
        address: String,
        latitude: String? = null,
        longitude: String? = null,
        nearby: JsonArray? = null
    ): String {
        val dataMap = mutableMapOf<String, JsonElement>(
            "title" to JsonPrimitive(title),
            "address" to JsonPrimitive(address)
        )
        if (latitude != null) dataMap["latitude"] = JsonPrimitive(latitude)
        if (longitude != null) dataMap["longitude"] = JsonPrimitive(longitude)
        if (nearby != null && nearby.size > 0) dataMap["nearby"] = nearby

        val actionsList = mutableListOf<JsonObject>()
        if (latitude != null && longitude != null) {
            actionsList.add(
                JsonObject(
                    mapOf(
                        "label" to JsonPrimitive("🗺️ 打开地图"),
                        "action" to JsonPrimitive("open_map"),
                        "style" to JsonPrimitive("Secondary")
                    )
                )
            )
        }
        actionsList.add(
            JsonObject(
                mapOf(
                    "label" to JsonPrimitive("📤 分享位置"),
                    "action" to JsonPrimitive("share_location"),
                    "style" to JsonPrimitive("Secondary")
                )
            )
        )

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("location"),
                "data" to JsonObject(dataMap),
                "actions" to JsonArray(actionsList)
            )
        )
        return Json.encodeToString(JsonObject.serializer(), card)
    }
}
