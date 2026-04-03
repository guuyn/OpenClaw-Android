package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class WeatherSkill : Skill {
    override val id = "weather"
    override val name = "天气查询"
    override val description = "查询当前天气和天气预报"
    override val version = "1.0.0"
    
    override val instructions = """
# Weather Skill

查询天气信息，支持 wttr.in。

## 用法
- 用户询问天气时，调用 get_weather 工具
- 无需 API Key，直接可用
"""
    
    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        WeatherTool()
    )
    
    private inner class WeatherTool : SkillTool {
        override val name = "get_weather"
        override val description = "获取指定位置的天气信息"
        override val parameters = mapOf(
            "location" to SkillParam(
                type = "string",
                description = "位置名称，如 '北京' 或 'Beijing'",
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
            
            try {
                val url = "https://wttr.in/${location}?format=3"
                android.util.Log.d("WeatherSkill", "Requesting URL: $url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                android.util.Log.d("WeatherSkill", "Response code: ${response.code}")
                if (!response.isSuccessful) {
                    return SkillResult(false, "", "HTTP error: ${response.code}")
                }
                
                val body = response.body?.string()?.trim() ?: ""
                android.util.Log.d("WeatherSkill", "Response body: $body")
                return SkillResult(true, body)
            } catch (e: IOException) {
                android.util.Log.e("WeatherSkill", "IOException: ${e.message}", e)
                return SkillResult(false, "", "Network error: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WeatherSkill", "Exception: ${e.message}", e)
                return SkillResult(false, "", "Error: ${e.message}")
            }
        }
        
        fun setHttpClient(client: OkHttpClient) {
            httpClient = client
        }
    }
    
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