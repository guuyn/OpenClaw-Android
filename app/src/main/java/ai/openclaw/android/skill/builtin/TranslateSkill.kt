package ai.openclaw.android.skill.builtin

import ai.openclaw.android.skill.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

class TranslateSkill : Skill {
    override val id = "translate"
    override val name = "翻译"
    override val description = "多语言翻译服务（MyMemory API，免费无需密钥）"
    override val version = "2.0.0"
    
    override val instructions = """
# Translate Skill

使用 MyMemory 翻译 API 进行多语言翻译。

## 用法
- 用户要求翻译时，调用 translate 工具
- 支持 auto 自动检测源语言
- 常见语言代码：zh（中文）、en（英文）、ja（日语）、ko（韩语）、fr（法语）、de（德语）

## 示例
- "把这段话翻译成英语" → target_lang=en
- "翻译成中文" → target_lang=zh
"""
    
    private var httpClient: OkHttpClient? = null
    
    override val tools: List<SkillTool> = listOf(
        object : SkillTool {
            override val name = "translate"
            override val description = "翻译文本到目标语言"
            override val parameters = mapOf(
                "text" to SkillParam("string", "要翻译的文本", true),
                "target_lang" to SkillParam("string", "目标语言代码（如 zh, en, ja, ko）", true),
                "source_lang" to SkillParam("string", "源语言代码（可选，默认自动检测）", false, "auto")
            )
            
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                val text = params["text"] as? String
                if (text.isNullOrBlank()) return SkillResult(false, "", "缺少 text 参数")
                
                val targetLang = (params["target_lang"] as? String) 
                    ?: (params["target"] as? String)
                if (targetLang.isNullOrBlank()) return SkillResult(false, "", "缺少 target_lang 参数")
                
                val sourceLang = (params["source_lang"] as? String) 
                    ?: (params["source"] as? String)
                    ?: "auto"
                
                val client = httpClient ?: return SkillResult(false, "", "HTTP client not initialized")
                
                try {
                    val encodedText = URLEncoder.encode(text, "UTF-8")
                    val langPair = "$sourceLang|$targetLang"
                    val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair"
                    
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    
                    if (!response.isSuccessful) {
                        return SkillResult(false, "", "HTTP error: ${response.code}")
                    }
                    
                    val body = response.body?.string() ?: return SkillResult(false, "", "Empty response")
                    
                    val translatedText = parseTranslationResponse(body)
                    
                    return if (translatedText != null) {
                        val cardJson = buildTranslationCardV2(text, sourceLang, translatedText, targetLang)
                        SkillResult(true, "[A2UI]$cardJson[/A2UI]")
                    } else {
                        SkillResult(false, "", "Failed to parse translation response")
                    }
                } catch (e: Exception) {
                    return SkillResult(false, "", "Translation error: ${e.message}")
                }
            }
            
            private fun parseTranslationResponse(json: String): String? {
                return try {
                    val translatedTextKey = "\"translatedText\":\""
                    val startIndex = json.indexOf(translatedTextKey)
                    if (startIndex == -1) return null
                    
                    val valueStart = startIndex + translatedTextKey.length
                    val valueEnd = json.indexOf("\"", valueStart)
                    if (valueEnd == -1) return null
                    
                    json.substring(valueStart, valueEnd)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } catch (e: Exception) {
                    null
                }
            }
        }
    )
    
    override fun initialize(context: SkillContext) {
        httpClient = context.httpClient
    }
    
    override fun cleanup() {}

    // ==================== v2 A2UI Card JSON 构建 ====================

    @OptIn(ExperimentalSerializationApi::class)
    internal fun buildTranslationCardV2(
        sourceText: String,
        sourceLang: String,
        targetText: String,
        targetLang: String,
        pronunciation: String? = null
    ): String {
        val dataMap = mutableMapOf<String, JsonElement>(
            "sourceText" to JsonPrimitive(sourceText),
            "sourceLang" to JsonPrimitive(sourceLang),
            "targetText" to JsonPrimitive(targetText),
            "targetLang" to JsonPrimitive(targetLang)
        )
        if (pronunciation != null) {
            dataMap["pronunciation"] = JsonPrimitive(pronunciation)
        }

        val card = JsonObject(
            mapOf(
                "type" to JsonPrimitive("translation"),
                "data" to JsonObject(dataMap),
                "actions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("🔊 朗读"),
                                "action" to JsonPrimitive("speak_target"),
                                "style" to JsonPrimitive("Secondary")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive("📋 复制"),
                                "action" to JsonPrimitive("copy_translation"),
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
