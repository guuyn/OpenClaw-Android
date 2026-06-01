package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

class MultiSearchSkill : Skill {
    override val id = "search"
    override val name = "多引擎搜索"
    override val description = "使用 SearXNG 搜索互联网信息"
    override val version = "2.0.0"

    override val instructions = """
# Multi-Search Skill

使用 SearXNG 进行搜索，无需 API Key。

## 用法
- 用户要求搜索信息时，调用 search 工具
- 返回搜索结果摘要与卡片展示

## A2UI 卡片输出格式（参考）
[A2UI]{"type":"search_result","data":{"title":"搜索结果","query":"搜索词","items":[{"title":"标题","url":"链接","snippet":"摘要","source":"来源"}],"total":5},"actions":[{"label":"🔗 在浏览器中打开","action":"open_search","style":"Secondary"}]}[/A2UI]
"""

    private var orchestrator: ScriptOrchestrator? = null
    private var scriptContent: String? = null

    override val tools: List<SkillTool> = listOf(SearchTool())

    private inner class SearchTool : SkillTool {
        override val name = "search"
        override val description = "搜索互联网信息"
        override val parameters = mapOf(
            "query" to SkillParam("string", "搜索关键词", true)
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val query = params["query"] as? String
            if (query.isNullOrBlank()) {
                return SkillResult(false, "", "缺少 query 参数")
            }

            val orch = orchestrator
                ?: return SkillResult(false, "", "ScriptOrchestrator 未初始化")
            val script = scriptContent
                ?: return SkillResult(false, "", "search.js 未加载")

            try {
                val fullScript = "var QUERY = ${Json.encodeToString(query)};\n$script"
                val result = orch.execute(fullScript, listOf("http"))

                if (!result.success) {
                    return SkillResult(false, "", result.error ?: "脚本执行失败")
                }

                // 用 kotlinx-serialization 解析 JS 返回的 JSON
                val json = Json.parseToJsonElement(result.output).jsonObject
                val success = json["success"]?.jsonPrimitive?.boolean ?: false

                if (!success) {
                    val error = json["error"]?.jsonPrimitive?.content ?: "搜索失败"
                    return SkillResult(false, "", error)
                }

                val resultsArray = json["results"]?.jsonArray ?: emptyList()
                if (resultsArray.isEmpty()) {
                    return SkillResult(true, "关于 \"$query\" 未找到相关结果")
                }

                // 构建 v2 A2UI 卡片
                val cardJson = buildSearchResultCardV2(query, resultsArray)
                return SkillResult(true, "[A2UI]$cardJson[/A2UI]")
            } catch (e: Exception) {
                return SkillResult(false, "", "搜索错误: ${e.message}")
            }
        }

        private fun buildTextSummary(query: String, results: List<JsonElement>): String {
            val sb = StringBuilder("搜索 \"$query\" 的结果:\n\n")
            results.forEachIndexed { i, elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val snippet = obj["snippet"]?.jsonPrimitive?.content ?: ""
                sb.append("${i + 1}. $title\n")
                if (snippet.isNotEmpty()) {
                    sb.append("   $snippet\n")
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun buildSearchResultCardV2(query: String, results: List<JsonElement>): String {
            val items = results.take(5).map { elem ->
                val obj = elem.jsonObject
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive(obj["title"]?.jsonPrimitive?.content ?: ""),
                        "url" to JsonPrimitive(obj["url"]?.jsonPrimitive?.content ?: ""),
                        "snippet" to JsonPrimitive(obj["snippet"]?.jsonPrimitive?.content ?: ""),
                        "source" to JsonPrimitive(obj["source"]?.jsonPrimitive?.content ?: obj["engine"]?.jsonPrimitive?.content ?: "")
                    )
                )
            }

            val card = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("search_result"),
                    "data" to JsonObject(
                        mapOf(
                            "title" to JsonPrimitive("搜索结果"),
                            "query" to JsonPrimitive(query),
                            "items" to JsonArray(items),
                            "total" to JsonPrimitive(results.size)
                        )
                    ),
                    "actions" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "label" to JsonPrimitive("🔗 在浏览器中打开"),
                                    "action" to JsonPrimitive("open_search"),
                                    "style" to JsonPrimitive("Secondary")
                                )
                            )
                        )
                    )
                )
            )
            return Json.encodeToString(JsonObject.serializer(), card)
        }
    }

    override fun initialize(context: SkillContext) {
        orchestrator = ScriptOrchestrator(context.applicationContext)
        scriptContent = context.applicationContext.assets
            .open("scripts/search.js")
            .bufferedReader()
            .use { it.readText() }
    }

    override fun cleanup() {
        orchestrator = null
        scriptContent = null
    }
}
