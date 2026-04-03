package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MultiSearchSkill : Skill {
    override val id = "search"
    override val name = "多引擎搜索"
    override val description = "使用 SearXNG 搜索互联网信息"
    override val version = "1.0.0"
    
    override val instructions = """
# Multi-Search Skill

使用 SearXNG 进行搜索，无需 API Key。

## 用法
- 用户要求搜索信息时，调用 search 工具
- 返回搜索结果摘要
"""
    
    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        SearchTool()
    )
    
    private inner class SearchTool : SkillTool {
        override val name = "search"
        override val description = "搜索互联网信息"
        override val parameters = mapOf(
            "query" to SkillParam(
                type = "string",
                description = "搜索关键词",
                required = true
            )
        )
        
        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val query = params["query"] as? String
            if (query == null || query.isBlank()) {
                return SkillResult(false, "", "缺少 query 参数")
            }
            
            val client = httpClient ?: return SkillResult(false, "", "HTTP client not initialized")
            
            try {
                // 使用公共 SearXNG 实例进行搜索
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://searx.work/search?q=$encodedQuery&format=json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OpenClaw-Android/1.0")
                    .build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    // 尝试备用 SearXNG 实例
                    val backupUrl = "https://searxng.no-logs.com/search?q=$encodedQuery&format=json"
                    val backupRequest = Request.Builder()
                        .url(backupUrl)
                        .header("User-Agent", "OpenClaw-Android/1.0")
                        .build()
                    val backupResponse = client.newCall(backupRequest).execute()
                    
                    if (!backupResponse.isSuccessful) {
                        return SkillResult(false, "", "HTTP error: ${response.code} and backup instance failed: ${backupResponse.code}")
                    }
                    
                    val backupBody = backupResponse.body?.string() ?: ""
                    return parseSearXNGResponse(backupBody, query)
                }
                
                val body = response.body?.string() ?: ""
                return parseSearXNGResponse(body, query)
            } catch (e: IOException) {
                return SkillResult(false, "", "网络错误: ${e.message}")
            } catch (e: Exception) {
                return SkillResult(false, "", "错误: ${e.message}")
            }
        }
        
        private fun parseSearXNGResponse(responseBody: String, query: String): SkillResult {
            try {
                // 简单的JSON解析，提取结果
                val resultsStart = responseBody.indexOf("\"results\":")
                if (resultsStart == -1) {
                    return SkillResult(false, "", "未能解析搜索结果")
                }
                
                // 查找结果数组的开始和结束
                val arrayStart = responseBody.indexOf("[", resultsStart)
                if (arrayStart == -1) {
                    return SkillResult(false, "", "未能找到搜索结果数组")
                }
                
                var braceCount = 0
                var arrayEnd = -1
                var i = arrayStart
                
                while (i < responseBody.length) {
                    when (responseBody[i]) {
                        '{' -> braceCount++
                        '}' -> braceCount--
                        ']' -> {
                            if (braceCount == 0) {
                                arrayEnd = i + 1
                                break
                            }
                        }
                    }
                    i++
                }
                
                if (arrayEnd == -1) {
                    return SkillResult(false, "", "未能找到搜索结果数组结束")
                }
                
                val resultsJson = responseBody.substring(arrayStart, arrayEnd)
                
                // 提取每个结果的信息
                val searchResults = mutableListOf<SearchResult>()
                var objStart = resultsJson.indexOf("{")
                
                while (objStart != -1) {
                    var braceCount = 1
                    var objEnd = objStart + 1
                    
                    while (objEnd < resultsJson.length && braceCount > 0) {
                        when (resultsJson[objEnd]) {
                            '{' -> braceCount++
                            '}' -> braceCount--
                        }
                        objEnd++
                    }
                    
                    if (braceCount == 0) {
                        val objJson = resultsJson.substring(objStart, objEnd)
                        val title = extractJsonValue(objJson, "title") ?: ""
                        val content = extractJsonValue(objJson, "content") ?: ""
                        val url = extractJsonValue(objJson, "url") ?: ""
                        
                        if (title.isNotEmpty()) {
                            searchResults.add(SearchResult(title, content, url))
                        }
                    }
                    
                    objStart = resultsJson.indexOf("{", objEnd)
                }
                
                if (searchResults.isEmpty()) {
                    return SkillResult(true, "关于 \"$query\" 未找到相关结果")
                }
                
                // 返回前5个结果
                val resultText = StringBuilder()
                resultText.append("搜索 \"$query\" 的结果:\n\n")
                
                searchResults.take(5).forEachIndexed { index, result ->
                    resultText.append("${index + 1}. **${result.title}**\n")
                    if (result.content.isNotEmpty()) {
                        resultText.append("   ${result.content}\n")
                    }
                    resultText.append("   来源: ${shortenUrl(result.url)}\n\n")
                }
                
                return SkillResult(true, resultText.toString())
            } catch (e: Exception) {
                return SkillResult(false, "", "解析搜索结果失败: ${e.message}")
            }
        }
        
        private fun extractJsonValue(json: String, key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
            val regex = Regex(pattern)
            val match = regex.find(json)
            return match?.groupValues?.get(1)?.replace("\\\"", "\"")
        }
        
        private fun shortenUrl(url: String): String {
            return try {
                val parsedUrl = java.net.URL(url)
                "${parsedUrl.host}${if (parsedUrl.path.isNotEmpty()) parsedUrl.path else "/"}"
            } catch (e: Exception) {
                url.take(50) + if (url.length > 50) "..." else ""
            }
        }
        
        fun setHttpClient(client: OkHttpClient) {
            httpClient = client
        }
    }
    
    data class SearchResult(
        val title: String,
        val content: String,
        val url: String
    )
    
    override fun initialize(context: SkillContext) {
        (tools[0] as SearchTool).setHttpClient(context.httpClient)
    }
    
    override fun cleanup() {
        // No cleanup needed
    }
}