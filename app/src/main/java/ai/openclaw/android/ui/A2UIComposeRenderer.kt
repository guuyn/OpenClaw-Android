package ai.openclaw.android.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a2ui.compose.data.Component
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.ComponentRegistry
import org.a2ui.compose.rendering.SurfaceContext

/**
 * 在聊天气泡内渲染标准 A2UI 协议消息。
 *
 * 接收 [A2UI]...[/A2UI] 包裹的 JSON 内容，自动识别协议版本：
 * - 标准协议 (v0.8/v0.9/v0.10)：直接渲染
 * - 旧格式 ({"type":"weather","data":{...}})：自动转为标准协议后渲染
 *
 * 失败时自动回退显示原始 JSON 内容，不再空白。
 */
@Composable
fun A2UIComposeRenderer(
    content: String,
    renderer: A2UIRenderer,
    modifier: Modifier = Modifier
) {
    val surfaceId = remember(content) { "chat_${System.currentTimeMillis()}" }
    val registry = remember { ComponentRegistry(renderer) }
    var ready by remember { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    val rawJson = remember(content) { extractA2UIJsons(content).firstOrNull() }

    LaunchedEffect(content, surfaceId) {
        ready = false
        renderError = null
        val jsonSegments = extractA2UIJsons(content)
        if (jsonSegments.isEmpty()) {
            renderError = "No A2UI content found"
            ready = true
            return@LaunchedEffect
        }

        var anySuccess = false
        for (jsonStr in jsonSegments) {
            val protocolJson = if (isStandardProtocol(jsonStr)) {
                jsonStr
            } else {
                convertLegacyCardToProtocol(jsonStr, surfaceId)
            }
            if (protocolJson != null) {
                runCatching {
                    renderer.processMessage(protocolJson)
                    anySuccess = true
                }.onFailure { e ->
                    Log.e("A2UIComposeRenderer", "processMessage failed: ${e.message}")
                    renderError = e.message ?: "Unknown rendering error"
                }
            } else {
                renderError = "Failed to parse A2UI JSON"
            }
        }

        if (!anySuccess && renderError == null) {
            renderError = "A2UI rendering returned no content"
        }
        ready = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (renderError != null) {
            // Fallback: display raw JSON so user can at least see what was returned
            A2UIFallbackCard(
                error = renderError!!,
                rawJson = rawJson ?: extractA2UIJsons(content).firstOrNull() ?: "",
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        if (ready) {
            val context = renderer.getSurfaceContext(surfaceId)
            val rootComponent = renderer.getComponent(surfaceId, "root")
            if (context != null && rootComponent != null) {
                renderComponentRecursive(rootComponent, context, registry)
            } else {
                A2UIFallbackCard(
                    error = "No renderable components found",
                    rawJson = rawJson ?: "",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Fallback card shown when A2UI rendering fails.
 * Displays error type + truncated raw JSON so users aren't looking at blank bubbles.
 */
@Composable
private fun A2UIFallbackCard(
    error: String,
    rawJson: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x15FF6B00))
            .padding(12.dp)
    ) {
        Text(
            text = "⚠️ A2UI 渲染失败: $error",
            fontSize = 12.sp,
            color = Color(0xFFFF8C00)
        )
        if (rawJson.isNotEmpty()) {
            Text(
                text = if (rawJson.length > 200) rawJson.take(200) + "…" else rawJson,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 递归渲染组件树 */
@Composable
private fun renderComponentRecursive(
    component: Component,
    context: SurfaceContext,
    registry: ComponentRegistry
) {
    registry.render(component, context)
}

// ==================== 解析工具 ====================

/** 提取所有 [A2UI]...[/A2UI] 内的 JSON 字符串 */
private fun extractA2UIJsons(content: String): List<String> {
    val results = mutableListOf<String>()
    val startTag = "[A2UI]"
    val endTag = "[/A2UI]"
    var cursor = 0

    while (cursor < content.length) {
        val startIdx = content.indexOf(startTag, cursor)
        if (startIdx == -1) break
        val jsonStart = startIdx + startTag.length
        val endIdx = content.indexOf(endTag, jsonStart)
        if (endIdx == -1) break
        results.add(content.substring(jsonStart, endIdx).trim())
        cursor = endIdx + endTag.length
    }
    return results
}

// ==================== 协议检测 ====================

/** 判断 JSON 是否已经是标准 A2UI 协议格式 */
private fun isStandardProtocol(json: String): Boolean {
    return json.contains("\"createSurface\"") ||
           json.contains("\"updateComponents\"") ||
           json.contains("\"updateDataModel\"") ||
           json.contains("\"deleteSurface\"") ||
           json.contains("\"surfaceUpdate\"") ||
           json.contains("\"beginRendering\"")
}

// ==================== 旧格式 → 标准协议转换 ====================

// Use kotlinx.serialization JSON parser instead of regex for robustness
private val legacyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 将旧格式 {"type":"X","data":{...},"actions":[...]} 转为 v0.10 标准协议
 * Uses kotlinx.serialization JSON parser (robust against whitespace/escaping issues)
 */
private fun convertLegacyCardToProtocol(jsonStr: String, surfaceId: String): String? {
    return try {
        val root = legacyJson.parseToJsonElement(jsonStr)
        val rootObj = root.jsonObject
        val type = rootObj["type"]?.jsonPrimitive?.content ?: return null
        val dataObj = rootObj["data"]?.jsonObject
        val data: Map<String, Any?> = dataObj?.mapValues { (_, v) -> jsonElementToAny(v) } ?: emptyMap()
        val actionsArr = rootObj["actions"]?.jsonArray
        val actions: List<Any> = actionsArr?.mapNotNull { jsonElementToAny(it) } ?: emptyList()

        buildProtocolMessage(type, data, actions, surfaceId)
    } catch (e: Exception) {
        Log.w("A2UIComposeRenderer", "Failed to convert legacy card: ${e.message}")
        null
    }
}

/** Convert JsonElement to plain Kotlin types (String, Number, Boolean, Map, List) */
private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull!!
        element.doubleOrNull != null -> element.doubleOrNull!!
        else -> element.toString()
    }
    is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
    is JsonArray -> element.map { jsonElementToAny(it) ?: "" }
    else -> element.toString()
}

// ==================== 构建标准协议消息 ====================

/** 构建 v0.10 标准协议 JSON (JSONL 格式) */
private fun buildProtocolMessage(
    type: String,
    data: Map<*, *>,
    actions: List<*>,
    surfaceId: String
): String {
    val components = mutableListOf<String>()

    // Root Column
    val childIds = mutableListOf("header")
    if (data.isNotEmpty()) childIds.add("body")
    if (actions.isNotEmpty()) childIds.add("footer")

    components.add(
        """{"id":"root","component":"Column","children":{"array":${jsonArr(childIds)}}}"""
    )

    // Header
    val title = data["title"]?.toString() ?: typeTitle(type)
    components.add(
        """{"id":"header","component":"Text","text":"${esc(title)}","variant":"h4"}"""
    )

    // Body: key-value rows
    if (data.isNotEmpty()) {
        val bodyChildren = mutableListOf<String>()
        data.forEach { (key, value) ->
            if (key == "title") return@forEach
            val rowId = "row_${key}"
            val labelId = "label_${key}"
            val valueId = "val_${key}"
            bodyChildren.add(rowId)
            components.add(
                """{"id":"$rowId","component":"Row","children":{"array":["$labelId","$valueId"]},"justify":"spaceBetween"}"""
            )
            components.add(
                """{"id":"$labelId","component":"Text","text":"${esc(key.toString())}:","variant":"caption"}"""
            )
            val displayValue = when (value) {
                is List<*> -> value.joinToString(", ") { it?.toString() ?: "" }
                null -> ""
                else -> value.toString()
            }
            components.add(
                """{"id":"$valueId","component":"Text","text":"${esc(displayValue)}","variant":"body"}"""
            )
        }
        components.add(
            """{"id":"body","component":"Column","children":{"array":${jsonArr(bodyChildren)}}}"""
        )
    }

    // Footer: action buttons
    if (actions.isNotEmpty()) {
        val actionIds = actions.mapIndexed { idx, _ -> "btn_$idx" }
        components.add(
            """{"id":"footer","component":"Column","children":{"array":${jsonArr(actionIds)}}}"""
        )
        actions.forEachIndexed { idx, action ->
            val a = action as? Map<*, *> ?: return@forEachIndexed
            val label = a["label"]?.toString() ?: "Action"
            val style = if (a["style"]?.toString() == "Secondary") "borderless" else "primary"
            val actionName = a["action"]?.toString() ?: "unknown"
            components.add(
                """{"id":"btn_$idx","component":"Button","text":"${esc(label)}","variant":"$style","action":{"event":{"name":"$actionName"}}}"""
            )
        }
    }

    val componentsJson = components.joinToString(",")
    return """
{"version":"v0.10","createSurface":{"surfaceId":"$surfaceId","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json"}}
{"version":"v0.10","updateComponents":{"surfaceId":"$surfaceId","components":[$componentsJson]}}
""".trimIndent()
}

private fun jsonArr(items: List<String>) = items.joinToString(",", "[", "]") { "\"$it\"" }
private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private fun typeTitle(type: String) = when (type) {
    "weather" -> "🌤️ 天气"
    "search_result" -> "🔍 搜索结果"
    "translation" -> "🌐 翻译"
    "reminder" -> "⏰ 提醒"
    "calendar" -> "📅 日程"
    "location" -> "📍 位置"
    "error" -> "⚠️ 错误"
    "info" -> "ℹ️ 信息"
    "summary" -> "📋 摘要"
    "contact" -> "👤 联系人"
    "sms" -> "💬 短信"
    "app" -> "📱 应用"
    "settings" -> "⚙️ 设置"
    else -> type.replaceFirstChar { it.uppercase() }
}
