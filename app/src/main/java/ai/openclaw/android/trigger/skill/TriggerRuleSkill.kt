package ai.openclaw.android.trigger.skill

import ai.openclaw.android.skill.*
import ai.openclaw.android.trigger.EventBus
import ai.openclaw.android.trigger.models.*
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import ai.openclaw.android.trigger.scheduler.CronScheduler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * TriggerRuleSkill — 触发规则管理 Skill
 *
 * 提供以下工具:
 * - create_trigger_rule — 创建新规则
 * - delete_trigger_rule — 删除规则
 * - list_trigger_rules — 列出所有规则
 * - enable_trigger_rule / disable_trigger_rule — 启用/禁用
 * - test_trigger_rule — 手动触发测试
 * - get_trigger_logs — 查看执行日志
 */
class TriggerRuleSkill(
    private val ruleDao: TriggerRuleDao,
    private val logDao: TriggerLogDao,
    private val cronScheduler: CronScheduler
) : Skill {
    override val id = "trigger_rule"
    override val name = "触发规则管理"
    override val description = "管理定时任务和事件触发规则：创建、删除、启用/禁用、测试"
    override val version = "1.0.0"
    override val instructions = """
# 触发规则管理

管理 Cron 定时任务和事件触发规则。

## 可用工具
- `create_trigger_rule`: 创建新规则
- `delete_trigger_rule`: 删除规则
- `list_trigger_rules`: 列出所有规则
- `enable_trigger_rule`: 启用规则
- `disable_trigger_rule`: 禁用规则
- `test_trigger_rule`: 手动触发测试
- `get_trigger_logs`: 查看执行日志

## Cron 表达式格式
- "*/30 * * * *" — 每 30 分钟
- "0 * * * *" — 每小时
- "0 0 * * *" — 每天午夜
- "0 9 * * *" — 每天 9 点

注意: WorkManager 最小间隔为 15 分钟。

## 过滤器类型
- PackageFilter: 按应用包名过滤
- KeywordFilter: 按关键词过滤 (mode: OR/AND/CONTAINS/EXACT)
- TimeFilter: 按时间段过滤 (startHour: 0-23, endHour: 0-23)
- CategoryFilter: 按分类过滤

## 动作类型
- SkillCall: 调用 Skill 工具
- AgentQuery: 向 AI 发送查询
- NotificationReply: 回复通知
- CustomScript: 执行自定义脚本
"""
    override val tools: List<SkillTool> = listOf(
        CreateTriggerRuleTool(ruleDao, cronScheduler),
        DeleteTriggerRuleTool(ruleDao, cronScheduler),
        ListTriggerRulesTool(ruleDao),
        EnableTriggerRuleTool(ruleDao, cronScheduler),
        DisableTriggerRuleTool(ruleDao, cronScheduler),
        TestTriggerRuleTool(),
        GetTriggerLogsTool(logDao)
    )

    override fun initialize(context: SkillContext) {}
    override fun cleanup() {}
}

// ==================== Tools ====================

class CreateTriggerRuleTool(
    private val ruleDao: TriggerRuleDao,
    private val cronScheduler: CronScheduler
) : SkillTool {
    override val name = "create_trigger_rule"
    override val description = "创建新的触发规则（Cron 定时任务或事件触发）"
    override val parameters = mapOf(
        "id" to SkillParam("string", "规则唯一标识（小写字母+下划线）", true),
        "name" to SkillParam("string", "规则显示名称", true),
        "source" to SkillParam("string", "事件源: CRON/NOTIFICATION/ACCESSIBILITY/SYSTEM_BROADCAST/USER_ACTION", true),
        "filters" to SkillParam("string", "过滤器 JSON 数组（可选）", false),
        "action" to SkillParam("string", "动作 JSON（必须）", true),
        "cron" to SkillParam("string", "Cron 表达式（仅 CRON 源需要）", false),
        "cooldownMs" to SkillParam("number", "防抖间隔毫秒（默认 300000）", false)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val name = params["name"] as? String ?: return SkillResult(false, "", "缺少参数: name")
        val sourceStr = params["source"] as? String ?: return SkillResult(false, "", "缺少参数: source")
        val actionJson = params["action"] as? String ?: return SkillResult(false, "", "缺少参数: action")

        val source = try {
            EventSource.valueOf(sourceStr)
        } catch (e: IllegalArgumentException) {
            return SkillResult(false, "", "无效的事件源: $sourceStr")
        }

        val filtersJson = params["filters"] as? String ?: "[]"
        val cooldownMs = (params["cooldownMs"] as? Number)?.toLong() ?: 300_000L
        val cron = params["cron"] as? String

        val rule = TriggerRule(
            id = id,
            name = name,
            source = source,
            filtersJson = filtersJson,
            actionJson = actionJson,
            cooldownMs = cooldownMs,
            scheduleCron = cron
        )

        try {
            ruleDao.insert(rule)
            if (source == EventSource.CRON && cron != null) {
                cronScheduler.scheduleCronTask(rule)
            }
            return SkillResult(true, "规则创建成功: $name ($id)", "")
        } catch (e: Exception) {
            return SkillResult(false, "", "创建失败: ${e.message}")
        }
    }
}

class DeleteTriggerRuleTool(
    private val ruleDao: TriggerRuleDao,
    private val cronScheduler: CronScheduler
) : SkillTool {
    override val name = "delete_trigger_rule"
    override val description = "删除触发规则"
    override val parameters = mapOf(
        "id" to SkillParam("string", "规则 ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        try {
            cronScheduler.cancelCronTask(id)
            ruleDao.deleteById(id)
            return SkillResult(true, "规则已删除: $id", "")
        } catch (e: Exception) {
            return SkillResult(false, "", "删除失败: ${e.message}")
        }
    }
}

class ListTriggerRulesTool(private val ruleDao: TriggerRuleDao) : SkillTool {
    override val name = "list_trigger_rules"
    override val description = "列出所有触发规则"
    override val parameters = emptyMap<String, SkillParam>()
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val rules = ruleDao.getAll()
        if (rules.isEmpty()) {
            return SkillResult(true, "没有配置的规则", "")
        }
        val output = rules.joinToString("\n") { rule ->
            "• ${rule.id} (${rule.name}) - ${rule.source} | enabled=${rule.enabled} | cron=${rule.scheduleCron ?: "N/A"}"
        }
        return SkillResult(true, output, "")
    }
}

class EnableTriggerRuleTool(
    private val ruleDao: TriggerRuleDao,
    private val cronScheduler: CronScheduler
) : SkillTool {
    override val name = "enable_trigger_rule"
    override val description = "启用触发规则"
    override val parameters = mapOf(
        "id" to SkillParam("string", "规则 ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val rule = ruleDao.getById(id) ?: return SkillResult(false, "", "规则不存在: $id")
        ruleDao.setEnabled(id, true)
        if (rule.source == EventSource.CRON && rule.scheduleCron != null) {
            cronScheduler.scheduleCronTask(rule)
        }
        return SkillResult(true, "规则已启用: ${rule.name}", "")
    }
}

class DisableTriggerRuleTool(
    private val ruleDao: TriggerRuleDao,
    private val cronScheduler: CronScheduler
) : SkillTool {
    override val name = "disable_trigger_rule"
    override val description = "禁用触发规则"
    override val parameters = mapOf(
        "id" to SkillParam("string", "规则 ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val rule = ruleDao.getById(id) ?: return SkillResult(false, "", "规则不存在: $id")
        ruleDao.setEnabled(id, false)
        if (rule.source == EventSource.CRON) {
            cronScheduler.cancelCronTask(id)
        }
        return SkillResult(true, "规则已禁用: ${rule.name}", "")
    }
}

class TestTriggerRuleTool : SkillTool {
    override val name = "test_trigger_rule"
    override val description = "手动触发规则进行测试"
    override val parameters = mapOf(
        "id" to SkillParam("string", "规则 ID", true)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val id = params["id"] as? String ?: return SkillResult(false, "", "缺少参数: id")
        val eventBus = EventBus.instance ?: return SkillResult(false, "", "EventBus 未初始化")
        try {
            val log = eventBus.triggerRuleManually(id)
            return SkillResult(true, "测试执行成功: ${log.result ?: "无返回结果"}", log.error ?: "")
        } catch (e: Exception) {
            return SkillResult(false, "", "测试失败: ${e.message}")
        }
    }
}

class GetTriggerLogsTool(private val logDao: TriggerLogDao) : SkillTool {
    override val name = "get_trigger_logs"
    override val description = "查看触发规则执行日志"
    override val parameters = mapOf(
        "ruleId" to SkillParam("string", "规则 ID（可选，不填则显示所有）", false),
        "limit" to SkillParam("number", "显示条数（默认 20）", false)
    )
    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val ruleId = params["ruleId"] as? String
        val limit = (params["limit"] as? Number)?.toInt() ?: 20
        val logs = if (ruleId != null) {
            logDao.getByRule(ruleId, limit)
        } else {
            logDao.getRecent(limit)
        }
        if (logs.isEmpty()) {
            return SkillResult(true, "没有执行日志", "")
        }
        val output = logs.joinToString("\n") { log ->
            "• [${if (log.success) "✓" else "✗"}] ${log.ruleId} at ${log.executedAt} - ${log.actionType}" +
            if (log.error != null) " | ERROR: ${log.error}" else ""
        }
        return SkillResult(true, output, "")
    }
}
