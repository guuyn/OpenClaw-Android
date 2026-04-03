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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.coroutines.resume

class LocationSkill(private val context: Context) : Skill {
    override val id = "location"
    override val name = "定位"
    override val description = "获取GPS位置和周边地点信息"
    override val version = "1.0.0"
    
    override val instructions = """
# Location Skill

获取当前GPS位置、地址解析、周边地点搜索。

## 用法
- get_location: 获取当前GPS坐标
- get_address: 将坐标转换为地址（逆地理编码）
- search_places: 搜索附近的地点（使用 OpenStreetMap）
"""
    
    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        // get_location tool
        object : SkillTool {
            override val name = "get_location"
            override val description = "获取当前GPS位置"
            override val parameters = emptyMap<String, SkillParam>()
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                if (!hasLocationPermission()) {
                    return SkillResult(false, "", "需要位置权限")
                }
                
                return try {
                    val location = getCurrentLocation()
                    if (location != null) {
                        SkillResult(true, "当前位置: ${location.latitude}, ${location.longitude}")
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
                
                // Try GPS first, fallback to Network
                val provider = if (hasGps) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    
                    // Also try to get last known location as fallback
                    val lastKnown = locationManager.getLastKnownLocation(provider)
                    if (lastKnown != null && cont.isActive) {
                        locationManager.removeUpdates(listener)
                        cont.resume(lastKnown)
                    }
                    
                    // Timeout after 10 seconds
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
                    // Use Nominatim for reverse geocoding (free, no key)
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
                    
                    SkillResult(true, address ?: "无法解析地址")
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
                    // Use Overpass API (OpenStreetMap) for POI search
                    val radius = (params["radius"] as? Number)?.toInt() ?: 1000
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    
                    // Overpass QL query
                    val overpassQuery = """
                        [out:json][timeout:25];
                        (
          node["name"~"$query",i](around:$radius,39.9,116.4);
          way["name"~"$query",i](around:$radius,39.9,116.4);
        );
        out center;
    """.trimIndent()
                    
                    // For simplicity, use Nominatim search instead
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
                        SkillResult(true, "未找到相关地点: $query")
                    } else {
                        val list = places.joinToString("\n") { p ->
                            "- ${p.name}"
                        }
                        SkillResult(true, "找到 ${places.size} 个地点:\n$list")
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "搜索失败: ${e.message}")
                }
            }
            
            private fun parsePlacesFromJson(json: String): List<PlaceInfo> {
                val places = mutableListOf<PlaceInfo>()
                return try {
                    // Simple parsing - find all "display_name" values
                    val regex = Regex("\"display_name\":\"([^\"]+)\"")
                    regex.findAll(json).forEach { match ->
                        places.add(PlaceInfo(match.groupValues[1].replace("\\n", ", ")))
                    }
                    places.take(5)
                } catch (e: Exception) {
                    places
                }
            }
        }
    )
    
    data class PlaceInfo(val name: String)
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun initialize(context: SkillContext) {
        httpClient = context.httpClient
    }
    
    override fun cleanup() {}
}