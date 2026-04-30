package ai.openclaw.android.ui

import android.content.Context
import android.widget.Toast
import ai.openclaw.android.ChatMessage
import ai.openclaw.script.bridge.UiProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptEngine 的 UiProvider 实现，桥接 script module 与 app module UI 层。
 *
 * 线程模型:
 * - renderCard/showConfirm 在 ScriptEngine 后台线程调用 (runBlocking 包裹)
 * - renderCard 内部通过 suspend onAddMessage + withContext(Main) 安全更新 UI
 * - renderToast 通过 withContext(Main) 调用 Android Toast
 * - showConfirm 通过 Channel 与 ChatScreen 同步
 */
class ScriptUiManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAddMessage: suspend (ChatMessage) -> Unit,
    private val confirmRequestFlow: MutableStateFlow<ConfirmRequest?>,
    private val confirmResultChannel: Channel<Boolean?>,
) : UiProvider {

    private val cardIdCounter = AtomicInteger(0)

    /**
     * 渲染 A2UI 卡片 — 将卡片 JSON 包装为 [A2UI]...[/A2UI] 消息注入聊天流
     *
     * 流程:
     * 1. 生成唯一 cardId
     * 2. 将卡片 JSON 包装为 [A2UI]...[/A2UI] 格式
     * 3. 通过 onAddMessage 注入到 ChatViewModel 的消息列表
     * 4. ChatScreen 的 A2UICardParser → A2UICardRouter 自动渲染
     */
    override suspend fun renderCard(cardJson: String): String {
        val cardId = "msg_card_${cardIdCounter.incrementAndGet()}"
        val a2uiContent = "[A2UI]\n$cardJson\n[/A2UI]"

        onAddMessage(
            ChatMessage(
                id = cardId,
                role = "assistant",
                content = a2uiContent,
            )
        )

        return """{"cardId":"$cardId"}"""
    }

    /**
     * 显示 Toast 提示
     *
     * 切换到 Main 线程调用 Android Toast，避免线程异常。
     */
    override suspend fun renderToast(message: String): String {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return """{"status":"ok"}"""
    }

    /**
     * 显示确认弹窗并等待用户选择
     *
     * 使用 Channel 将异步 UI 交互转为同步 suspend 调用。
     * 调用 confirmRequestFlow.value = request → ChatScreen 显示 AlertDialog →
     * 用户点击 → confirmResultChannel.send(result) → 本函数返回
     * 8s 超时默认 cancel。
     */
    override suspend fun showConfirm(title: String, message: String): String {
        val request = ConfirmRequest(
            title = title,
            message = message,
        )
        confirmRequestFlow.value = request

        val result = withTimeoutOrNull(8000L) {
            confirmResultChannel.receive()
        }

        confirmRequestFlow.value = null // 清除请求

        return when (result) {
            true -> """{"result":"confirm"}"""
            else -> """{"result":"cancel"}"""
        }
    }
}

/** 确认弹窗请求 */
data class ConfirmRequest(
    val title: String,
    val message: String,
)
