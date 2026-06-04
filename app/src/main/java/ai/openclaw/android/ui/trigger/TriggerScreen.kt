package ai.openclaw.android.ui.trigger

import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.TriggerAction
import ai.openclaw.android.trigger.models.Filter
import ai.openclaw.android.trigger.models.MatchMode
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOutline
import ai.openclaw.android.ui.theme.SciFiOnSurfaceVariant
import androidx.compose.material3.ExperimentalMaterial3Api
import ai.openclaw.android.viewmodel.TriggerViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

/**
 * TriggerScreen — Trigger v2 管理界面
 *
 * 科幻主题风格，支持：
 * - Trigger 列表（启用/禁用开关、编辑、删除）
 * - 新增 Trigger 对话框
 * - 预设模板快速添加
 * - 日志查看
 * - AI 决策统计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerScreen(
    viewModel: TriggerViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val engineRunning by viewModel.engineRunning.collectAsStateWithLifecycle()
    val showAddDialog by viewModel.showAddDialog.collectAsStateWithLifecycle()
    val selectedTriggerId by viewModel.selectedTriggerId.collectAsStateWithLifecycle()
    val decisionStats by viewModel.decisionStats.collectAsStateWithLifecycle()

    // Toast 消息
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            // Toast 会通过 Snackbar 或其他方式展示
        }
    }

    Scaffold(
        topBar = {
            TriggerTopBar(
                onNavigateBack = onNavigateBack,
                engineRunning = engineRunning
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.setShowAddDialog(true) },
                containerColor = SciFiPrimary,
                contentColor = Color(0xFF0A0E1A)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Trigger")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 统计概览卡片
            item {
                TriggerStatsCard(
                    totalRules = rules.size,
                    enabledRules = rules.count { it.enabled },
                    totalLogs = recentLogs.size,
                    aiStats = decisionStats
                )
            }

            // 预设模板
            item {
                PresetTemplateCard(
                    templates = viewModel.getPresetTemplates(),
                    onTemplateSelected = { template ->
                        viewModel.addPresetTemplate(template)
                    }
                )
            }

            // Trigger 列表标题
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "触发器列表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SciFiPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${rules.size} 个规则",
                        style = MaterialTheme.typography.labelMedium,
                        color = SciFiOnSurfaceVariant
                    )
                }
            }

            // Trigger 列表
            items(rules, key = { it.id }) { rule ->
                TriggerRuleCard(
                    rule = rule,
                    onToggle = { enabled -> viewModel.toggleRule(rule.id, enabled) },
                    onEdit = { viewModel.setSelectedTriggerId(rule.id) },
                    onDelete = { viewModel.deleteRule(rule.id) }
                )
            }

            // 最近日志
            item {
                RecentLogsCard(logs = recentLogs)
            }

            // 底部间距
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // 新增 Trigger 对话框
    if (showAddDialog) {
        AddTriggerDialog(
            onDismiss = { viewModel.setShowAddDialog(false) },
            onAdd = { rule ->
                viewModel.addRule(rule)
                viewModel.setShowAddDialog(false)
            }
        )
    }
}

// ==================== 顶部栏 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerTopBar(
    onNavigateBack: () -> Unit,
    engineRunning: Boolean
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Trigger",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SciFiPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "v2",
                    style = MaterialTheme.typography.labelSmall,
                    color = SciFiSecondary,
                    modifier = Modifier
                        .background(
                            color = SciFiSecondary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            // 引擎状态指示器
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (engineRunning) SciFiPrimary else SciFiError,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

// ==================== 统计卡片 ====================

@Composable
private fun TriggerStatsCard(
    totalRules: Int,
    enabledRules: Int,
    totalLogs: Int,
    aiStats: ai.openclaw.android.trigger.v2.DecisionStats
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SciFiOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 系统概览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SciFiPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("规则总数", totalRules.toString(), SciFiPrimary)
                StatItem("已启用", enabledRules.toString(), SciFiSecondary)
                StatItem("最近日志", totalLogs.toString(), Color(0xFFF59E0B))
            }

            if (aiStats.totalDecisions > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = SciFiOutline)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        "AI 决策",
                        aiStats.llmCalls.toString(),
                        Color(0xFF8B5CF6)
                    )
                    StatItem(
                        "缓存命中",
                        "${(aiStats.cacheHitRate * 100).toInt()}%",
                        Color(0xFF10B981)
                    )
                    StatItem(
                        "降级决策",
                        aiStats.fallbackCalls.toString(),
                        Color(0xFFF97316)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SciFiOnSurfaceVariant
        )
    }
}

// ==================== 预设模板卡片 ====================

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun PresetTemplateCard(
    templates: List<ai.openclaw.android.trigger.v2.TriggerConfig>,
    onTemplateSelected: (ai.openclaw.android.trigger.v2.TriggerConfig) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SciFiOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoFixHigh,
                    contentDescription = null,
                    tint = SciFiSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "预设模板",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 模板快捷按钮
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { template ->
                    AssistChip(
                        onClick = { onTemplateSelected(template) },
                        label = { Text(template.name) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = SciFiPrimary.copy(alpha = 0.1f),
                            labelColor = SciFiPrimary
                        ),
                        border = BorderStroke(1.dp, SciFiPrimary.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

// ==================== Trigger 规则卡片 ====================

@Composable
private fun TriggerRuleCard(
    rule: TriggerRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) SciFiSurfaceVariant else SciFiSurfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (rule.enabled) SciFiOutline else SciFiOutline.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 源图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(SciFiPrimary, SciFiSecondary)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (rule.source) {
                            EventSource.CRON -> Icons.Outlined.Schedule
                            EventSource.NOTIFICATION -> Icons.Outlined.Notifications
                            EventSource.SYSTEM_BROADCAST -> Icons.Outlined.Devices
                            EventSource.ACCESSIBILITY -> Icons.Outlined.Visibility
                            EventSource.USER_ACTION -> Icons.Outlined.TouchApp
                        },
                        contentDescription = null,
                        tint = Color(0xFF0A0E1A),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 规则名称和描述
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (rule.enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            SciFiOnSurfaceVariant
                    )
                    Text(
                        text = rule.source.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = SciFiSecondary
                    )
                }

                // 开关
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SciFiPrimary,
                        checkedTrackColor = SciFiPrimary.copy(alpha = 0.3f),
                        uncheckedThumbColor = SciFiOnSurfaceVariant,
                        uncheckedTrackColor = SciFiOutline
                    )
                )
            }

            // 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(
                    onClick = { showDeleteConfirm = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = SciFiError
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = SciFiError)
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除触发器") },
            text = { Text("确定要删除 \"${rule.name}\" 吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SciFiError)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
            containerColor = SciFiSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 最近日志卡片 ====================

@Composable
private fun RecentLogsCard(
    logs: List<ai.openclaw.android.trigger.models.TriggerLog>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SciFiOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "最近日志",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = SciFiOnSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                logs.take(10).forEach { log ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (log.success) SciFiPrimary else SciFiError,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = log.actionType,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatTimestamp(log.executedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = SciFiOnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ==================== 新增 Trigger 对话框 ====================

@Composable
private fun AddTriggerDialog(
    onDismiss: () -> Unit,
    onAdd: (TriggerRule) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sourceIndex by remember { mutableStateOf(0) }
    var cronExpression by remember { mutableStateOf("0 * * * *") }
    var keywordsText by remember { mutableStateOf("") }
    var actionText by remember { mutableStateOf("") }

    val sources = EventSource.entries.toList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "新建触发器",
                color = SciFiPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如：重要通知提醒") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SciFiPrimary,
                        unfocusedBorderColor = SciFiOutline
                    )
                )

                // 触发源
                Text("触发源", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEachIndexed { index, s ->
                        FilterChip(
                            selected = sourceIndex == index,
                            onClick = { sourceIndex = index },
                            label = { Text(s.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SciFiPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = SciFiPrimary
                            )
                        )
                    }
                }

                // Cron 表达式（CRON 源）
                if (sources[sourceIndex] == EventSource.CRON) {
                    OutlinedTextField(
                        value = cronExpression,
                        onValueChange = { cronExpression = it },
                        label = { Text("Cron 表达式") },
                        placeholder = { Text("0 * * * *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // 关键词（NOTIFICATION 源）
                if (sources[sourceIndex] == EventSource.NOTIFICATION) {
                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = { keywordsText = it },
                        label = { Text("关键词（逗号分隔）") },
                        placeholder = { Text("紧急, important, ASAP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // 动作描述
                OutlinedTextField(
                    value = actionText,
                    onValueChange = { actionText = it },
                    label = { Text("动作描述（AI 查询 Prompt）") },
                    placeholder = { Text("当收到通知时，请分析并给出建议...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val source = sources[sourceIndex]
                        val filters = mutableListOf<Filter>()
                        if (keywordsText.isNotBlank()) {
                            filters.add(
                                Filter.KeywordFilter(
                                    keywords = keywordsText.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                    mode = MatchMode.OR
                                )
                            )
                        }

                        val rule = TriggerRule(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            enabled = true,
                            source = source,
                            filtersJson = TriggerRule.serializeFilters(filters),
                            actionJson = TriggerRule.serializeAction(
                                TriggerAction.AgentQuery(prompt = actionText.takeIf { it.isNotBlank() } ?: "处理此事件")
                            ),
                            scheduleCron = if (source == EventSource.CRON) cronExpression else null
                        )
                        onAdd(rule)
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SciFiPrimary,
                    contentColor = Color(0xFF0A0E1A),
                    disabledContainerColor = SciFiOutline
                )
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        containerColor = SciFiSurfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ==================== 辅助函数 ====================

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        else -> "${diff / 86400_000} 天前"
    }
}
