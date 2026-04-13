package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

                // 构建纯文本摘要（供 LLM 理解）
                val textSummary = buildTextSummary(query, resultsArray)

                // 构建 A2UI 卡片（供 UI 渲染）
                val a2ui = buildA2UICard(query, resultsArray)

                return SkillResult(true, "$textSummary\n\n$a2ui")
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

        private fun buildA2UICard(query: String, results: List<JsonElement>): String {
            val dataMap = mutableMapOf<String, JsonPrimitive>(
                "query" to JsonPrimitive(query)
            )
            results.forEachIndexed { i, elem ->
                val obj = elem.jsonObject
                val index = i + 1
                dataMap["result$index"] = JsonPrimitive(obj["title"]?.jsonPrimitive?.content ?: "")
                dataMap["snippet$index"] = JsonPrimitive(obj["snippet"]?.jsonPrimitive?.content ?: "")
                dataMap["url$index"] = JsonPrimitive(obj["url"]?.jsonPrimitive?.content ?: "")
            }
            val data = JsonObject(dataMap)
            return "[A2UI]\n{\"type\":\"search\",\"data\":$data}\n[/A2UI]"
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
