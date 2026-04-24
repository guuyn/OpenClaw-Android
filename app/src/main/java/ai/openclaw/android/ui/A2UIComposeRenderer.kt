package ai.openclaw.android.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(content, surfaceId) {
        ready = false
        val jsonSegments = extractA2UIJsons(content)
        if (jsonSegments.isEmpty()) return@LaunchedEffect

        for (jsonStr in jsonSegments) {
            val protocolJson = if (isStandardProtocol(jsonStr)) {
                jsonStr
            } else {
                convertLegacyCardToProtocol(jsonStr, surfaceId)
            }
            if (protocolJson != null) {
                runCatching {
                    renderer.processMessage(protocolJson)
                }.onFailure { e ->
                    Log.e("A2UIComposeRenderer", "processMessage failed", e)
                }
            }
        }
        ready = true
    }

    if (ready) {
        Column(modifier = modifier.fillMaxWidth()) {
            val context = renderer.getSurfaceContext(surfaceId)
            val rootComponent = renderer.getComponent(surfaceId, "root")
            if (context != null && rootComponent != null) {
                renderComponentRecursive(rootComponent, context, registry)
            }
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

/**
 * 将旧格式 {"type":"X","data":{...},"actions":[...]} 转为 v0.10 标准协议
 */
private fun convertLegacyCardToProtocol(jsonStr: String, surfaceId: String): String? {
    return try {
        val parsed = parseSimpleJson(jsonStr)
        val type = parsed["type"]?.toString() ?: return null
        val data = parsed["data"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val actions = parsed["actions"] as? List<*> ?: emptyList<Any>()

        buildProtocolMessage(type, data, actions, surfaceId)
    } catch (e: Exception) {
        Log.w("A2UIComposeRenderer", "Failed to convert legacy card: $jsonStr", e)
        null
    }
}

/** 简易 JSON 解析 */
private fun parseSimpleJson(json: String): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    val trimmed = json.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result

    val typeMatch = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
    typeMatch?.let { result["type"] = it.groupValues[1] }

    val dataStart = trimmed.indexOf("\"data\"")
    if (dataStart >= 0) {
        val braceStart = trimmed.indexOf("{", dataStart)
        if (braceStart >= 0) {
            val dataStr = extractBraceBlock(trimmed, braceStart)
            result["data"] = parseSimpleKeyValueMap(dataStr)
        }
    }

    val actionsStart = trimmed.indexOf("\"actions\"")
    if (actionsStart >= 0) {
        val bracketStart = trimmed.indexOf("[", actionsStart)
        if (bracketStart >= 0) {
            val actionsStr = extractBracketBlock(trimmed, bracketStart)
            result["actions"] = parseActionsArray(actionsStr)
        }
    }

    return result
}

private fun parseSimpleKeyValueMap(json: String): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    for (match in regex.findAll(json)) {
        result[match.groupValues[1]] = match.groupValues[2]
    }
    val arrayRegex = Regex("\"([^\"]+)\"\\s*:\\s*(\\[.*?\\])")
    for (match in arrayRegex.findAll(json)) {
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

private fun parseActionsArray(json: String): List<Map<String, String>> {
    val result = mutableListOf<Map<String, String>>()
    val itemRegex = Regex("\\{([^}]+)\\}")
    for (match in itemRegex.findAll(json)) {
        val item = mutableMapOf<String, String>()
        val kvRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        for (kv in kvRegex.findAll(match.value)) {
            item[kv.groupValues[1]] = kv.groupValues[2]
        }
        if (item.isNotEmpty()) result.add(item)
    }
    return result
}

private fun extractBraceBlock(s: String, start: Int): String {
    var depth = 0
    for (i in start until s.length) {
        when (s[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
        }
    }
    return s.substring(start)
}

private fun extractBracketBlock(s: String, start: Int): String {
    var depth = 0
    for (i in start until s.length) {
        when (s[i]) {
            '[' -> depth++
            ']' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
        }
    }
    return s.substring(start)
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
