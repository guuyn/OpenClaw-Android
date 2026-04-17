package ai.openclaw.android.trigger

import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.trigger.models.*
import ai.openclaw.android.agent.AgentSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ActionExecutor — 执行 TriggerAction
 *
 * 支持四种动作类型：
 * - SkillCall: 调用 Skill 工具
 * - AgentQuery: 向 AI Agent 发送查询
 * - NotificationReply: 回复通知
 * - CustomScript: 执行自定义脚本
 */
class ActionExecutor(
    private val context: Context,
    private val skillManager: SkillManager,
    private val agentSessionFactory: suspend () -> AgentSession?
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    /**
     * 执行动作
     */
    suspend fun execute(action: TriggerAction, event: TriggerEvent): ActionResult {
        return when (action) {
            is TriggerAction.SkillCall -> executeSkillCall(action, event)
            is TriggerAction.AgentQuery -> executeAgentQuery(action, event)
            is TriggerAction.NotificationReply -> executeNotificationReply(action, event)
            is TriggerAction.CustomScript -> executeCustomScript(action, event)
        }
    }

    /**
     * 执行 SkillCall — 调用 Skill 工具
     */
    private suspend fun executeSkillCall(
        action: TriggerAction.SkillCall,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val toolFullName = "${action.skillId}_${action.toolName}"

            // 解析参数，替换事件变量
            val params = parseAndInterpolateParams(action.paramsJson, event)

            // 调用 SkillManager 执行工具
            val result = skillManager.executeTool(toolFullName, params)

            if (result.success) {
                ActionResult(
                    success = true,
                    result = "Skill executed: ${action.toolName} → ${result.output.take(200)}"
                )
            } else {
                ActionResult(
                    success = false,
                    error = "Skill failed: ${result.error}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SkillCall failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 执行 AgentQuery — 向 AI 发送查询
     */
    private suspend fun executeAgentQuery(
        action: TriggerAction.AgentQuery,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val prompt = interpolatePrompt(action.prompt, event)

            val session = agentSessionFactory()
            if (session == null) {
                return@withContext ActionResult(success = false, error = "AgentSession not available")
            }

            // 发送消息给 Agent
            val response = session.handleMessage(prompt)

            ActionResult(
                success = true,
                result = "Agent response: ${response.take(200)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AgentQuery failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 执行 NotificationReply — 回复通知（暂不实现，返回占位）
     */
    private suspend fun executeNotificationReply(
        action: TriggerAction.NotificationReply,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val packageName = event.payload["package"] as? String
            val text = interpolatePrompt(action.template, event)

            if (action.autoReply && packageName != null) {
                // TODO: 实现自动回复（需要 NotificationListenerService 的 reply 权限）
                Log.i(TAG, "Auto-reply to $packageName: $text")
                ActionResult(success = true, result = "Reply sent to $packageName")
            } else {
                ActionResult(success = true, result = "Reply template: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NotificationReply failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 执行 CustomScript — 执行自定义脚本（暂不实现，返回占位）
     */
    private suspend fun executeCustomScript(
        action: TriggerAction.CustomScript,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            // TODO: 集成 ScriptOrchestrator 执行 JS 脚本
            Log.i(TAG, "CustomScript executed (placeholder): ${action.script.take(50)}")
            ActionResult(success = true, result = "Script executed (placeholder)")
        } catch (e: Exception) {
            Log.e(TAG, "CustomScript failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    // ==================== Helpers ====================

    /**
     * 解析并插值参数
     * 支持变量替换: {notification.text}, {notification.title}, {event.source}
     */
    private fun parseAndInterpolateParams(paramsJson: String, event: TriggerEvent): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        // 简单实现：如果 paramsJson 是 JSON object，直接解析
        // TODO: 完整 JSON 解析 + 变量插值
        if (paramsJson.isNotBlank() && paramsJson != "{}") {
            try {
                val json = org.json.JSONObject(paramsJson)
                json.keys().forEach { key ->
                    map[key] = json.get(key)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse params JSON: $paramsJson")
            }
        }

        // 注入事件数据
        map["event_source"] = event.source.name
        map["event_id"] = event.id
        event.payload.forEach { (k, v) ->
            if (v != null) map["notification_$k"] = v.toString()
        }

        return map
    }

    /**
     * 插值 Prompt 字符串
     * 替换变量: {notification.text} → 事件 payload 中的 text
     */
    private fun interpolatePrompt(prompt: String, event: TriggerEvent): String {
        var result = prompt

        // 替换通知相关变量
        result = result.replace("{notification.text}", event.payload["text"]?.toString() ?: "")
        result = result.replace("{notification.title}", event.payload["title"]?.toString() ?: "")
        result = result.replace("{notification.package}", event.payload["package"]?.toString() ?: "")
        result = result.replace("{event.source}", event.source.name)
        result = result.replace("{event.timestamp}", event.timestamp.toString())

        return result
    }
}
