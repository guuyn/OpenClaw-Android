package ai.openclaw.script.bridge

import ai.openclaw.script.CapabilityBridge
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.ObjectBindingScope

/**
 * UI 渲染 Bridge — JS 脚本与 A2UI 卡片系统的桥梁
 *
 * JS API:
 *   ui.renderCard(cardJson)  → {cardId: "msg_card_xxx"}
 *   ui.renderToast(message)  → {status: "ok"}
 *   ui.showConfirm(title, message) → {result: "confirm"|"cancel"}
 *
 * 主工程通过 UiProvider 回调注入实际渲染能力。
 * QuickJS 模式: function + runBlocking（与 Rhino 行为一致，JS 端同步调用）
 * Rhino 模式: JS prototype + __nativeCall 同步调用
 */
class UiBridge(private val provider: UiProvider) : CapabilityBridge {

    override val name: String = "ui"

    /**
     * QuickJS 绑定: 注册 JS 函数。
     * 使用 function (非 asyncFunction)，因为 bridge handle 方法内部用 runBlocking 调用 suspend 函数。
     * 这样 JS 端是同步调用，与 Rhino 行为一致。
     */
    override fun registerBindings(dsl: ObjectBindingScope) {
        dsl.apply {
            function("renderCard", FunctionBinding { args ->
                val cardJson = args.getOrNull(0) as? String
                    ?: return@FunctionBinding """{"error":"Missing cardJson"}"""
                handle("ui.renderCard", """{"cardJson":${jsonEscape(cardJson)}}""")
            })
            function("renderToast", FunctionBinding { args ->
                val message = args.getOrNull(0) as? String
                    ?: return@FunctionBinding """{"error":"Missing message"}"""
                handle("ui.renderToast", """{"message":${jsonEscape(message)}}""")
            })
            function("showConfirm", FunctionBinding { args ->
                val title = args.getOrNull(0) as? String ?: ""
                val message = args.getOrNull(1) as? String ?: ""
                handle("ui.showConfirm", """{"title":${jsonEscape(title)},"message":${jsonEscape(message)}}""")
            })
        }
    }

    override fun getJsPrototype(): String = """
        var ui = {
            renderCard: function(cardJson) { return JSON.parse(__nativeCall('ui.renderCard', cardJson)); },
            renderToast: function(message) { return JSON.parse(__nativeCall('ui.renderToast', JSON.stringify({message: message}))); },
            showConfirm: function(title, message) { return JSON.parse(__nativeCall('ui.showConfirm', JSON.stringify({title: title, message: message}))); }
        };
    """.trimIndent()

    /**
     * 处理 JS 方法调用（Rhino 模式和 QuickJS 内部复用）
     * 内部使用 runBlocking 调用 suspend 的 UiProvider 方法
     */
    fun handle(method: String, argsJson: String): String {
        return try {
            when (method) {
                "ui.renderCard" -> {
                    val cardJson = extractField(argsJson, "cardJson")
                    kotlinx.coroutines.runBlocking {
                        provider.renderCard(cardJson)
                    }
                }
                "ui.renderToast" -> {
                    val message = extractField(argsJson, "message")
                    kotlinx.coroutines.runBlocking {
                        provider.renderToast(message)
                    }
                }
                "ui.showConfirm" -> {
                    val title = extractField(argsJson, "title")
                    val message = extractField(argsJson, "message")
                    kotlinx.coroutines.runBlocking {
                        provider.showConfirm(title, message)
                    }
                }
                else -> """{"error":"Unknown method: $method"}"""
            }
        } catch (e: Exception) {
            """{"error":"${jsonEscape(e.message ?: "Unknown error")}"}"""
        }
    }
}

/**
 * 主工程需实现的回调接口，提供实际的 UI 渲染能力。
 *
 * 实现示例（在 app module 中）:
 * ```kotlin
 * val uiProvider = object : UiProvider {
 *     override suspend fun renderCard(cardJson: String): String {
 *         // 将卡片 JSON 传递给 ChatScreen 的 A2UI 渲染器
 *         val cardId = "msg_card_${System.currentTimeMillis()}"
 *         // ... 实际渲染逻辑
 *         return """{"cardId":"$cardId"}"""
 *     }
 *
 *     override suspend fun renderToast(message: String): String {
 *         // 显示 Toast
 *         return """{"status":"ok"}"""
 *     }
 *
 *     override suspend fun showConfirm(title: String, message: String): String {
 *         // 显示确认弹窗，等待用户选择
 *         return """{"result":"confirm"}""" // 或 "cancel"
 *     }
 * }
 * ```
 */
interface UiProvider {
    /**
     * 渲染 A2UI 卡片 — 返回包含 cardId 的 JSON
     * @param cardJson A2UI 卡片 JSON（type/data/actions 结构）
     * @return JSON 结果，如 {"cardId":"msg_card_xxx"}
     */
    suspend fun renderCard(cardJson: String): String

    /**
     * 显示 Toast 提示
     * @param message 提示文字
     * @return {"status":"ok"}
     */
    suspend fun renderToast(message: String): String

    /**
     * 显示确认弹窗
     * @param title 标题
     * @param message 内容
     * @return {"result":"confirm"} 或 {"result":"cancel"}
     */
    suspend fun showConfirm(title: String, message: String): String
}
