package ai.openclaw.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ConfigManager
import ai.openclaw.android.LogManager
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOutline
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 设置界面
 *
 * 包含：Gateway 服务控制、模型配置、权限管理、运行日志
 */
@Composable
fun SettingsScreen(
    serviceRunning: Boolean,
    onServiceToggle: () -> Unit,
    modelApiKey: String,
    onModelApiKeyChange: (String) -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    modelProvider: String,
    onModelProviderChange: (String) -> Unit,
    modelBaseUrl: String,
    onModelBaseUrlChange: (String) -> Unit,
    configExpanded: Boolean,
    onConfigExpandedChange: (Boolean) -> Unit,
    logExpanded: Boolean,
    onLogExpandedChange: (Boolean) -> Unit,
    onSaveConfig: () -> Unit,
    permissionManager: PermissionManager,
    onRequestPermissions: (Array<String>) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    settingsPermRefreshKey: Int,
    onOpenModelManager: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 在函数顶部一次性获取，避免在深层 if (logExpanded) 子作用域里取不到
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Gateway 服务状态卡片
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
                        imageVector = if (serviceRunning) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (serviceRunning)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Gateway Service",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (serviceRunning) "Running" else "Stopped",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (serviceRunning)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onServiceToggle) {
                        Text(if (serviceRunning) "Stop" else "Start")
                    }
                }
            }
        }

        // 模型配置卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SciFiOutline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (configExpanded) 8.dp else 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { onConfigExpandedChange(!configExpanded) }) {
                        Icon(
                            imageVector = if (configExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (configExpanded) "Collapse" else "Expand"
                        )
                    }
                }

                if (configExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Model Configuration",
                        style = MaterialTheme.typography.labelLarge
                    )

                    // 提供商选择
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        FilterChip(
                            selected = modelProvider == "OPENAI",
                            onClick = { onModelProviderChange("OPENAI") },
                            label = { Text("OpenAI") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = modelProvider == "ANTHROPIC",
                            onClick = { onModelProviderChange("ANTHROPIC") },
                            label = { Text("Anthropic") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = modelProvider == "LOCAL",
                            onClick = { onModelProviderChange("LOCAL") },
                            label = { Text("本地模型") }
                        )
                    }

                    // API Key（云端模型需要）
                    if (modelProvider != "LOCAL") {
                        OutlinedTextField(
                            value = modelApiKey,
                            onValueChange = onModelApiKeyChange,
                            label = { Text("API Key (明文显示)") },
                            placeholder = { Text("sk-xxx") },
                            visualTransformation = VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = modelName,
                            onValueChange = onModelNameChange,
                            label = { Text("Model Name") },
                            placeholder = { Text(ConfigManager.getModelName()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = modelBaseUrl,
                            onValueChange = onModelBaseUrlChange,
                            label = { Text("Base URL (可选)") },
                            placeholder = { Text(
                                when (modelProvider) {
                                    "ANTHROPIC" -> "https://api.anthropic.com"
                                    else -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                }
                            ) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true
                        )
                    } else {
                        // 本地模型信息
                        val hasStorageAccess = remember(settingsPermRefreshKey) {
                            permissionManager.hasAllFilesAccess()
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (hasStorageAccess)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Gemma 端侧推理（LiteRT-LM）\n支持 E2B（2B）/ E4B（4B）\n文件路径: /sdcard/Download/gemma-4-*.litertlm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!hasStorageAccess) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "⚠ 需要文件存储权限才能加载本地模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = { onRequestAllFilesAccess() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("授权文件访问", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onSaveConfig,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Configuration")
                    }
                }
            }
        }

        // 权限管理卡片
        PermissionsCard(
            permissionManager = permissionManager,
            onRequestPermissions = onRequestPermissions,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            refreshKey = settingsPermRefreshKey
        )

        // 模型管理入口
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenModelManager),
            colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SciFiOutline)
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = SciFiPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "模型管理",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "下载和管理本地 STT/TTS 模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 日志卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SciFiOutline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLogExpandedChange(!logExpanded) }
                        .padding(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "运行日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))

                    // 复制按钮：仅在展开时显示，放在标题行右侧、与 chevron 并排。
                    // v5: 完全去掉 Icon，只用 Text。
                    // 原因：v3/v4 都用 Icons.Default.*（ContentCopy / Share），真机上
                    // Icons.Default.* 中 icons-core 36 个之外的图标渲染时被 Compose
                    // 静默吞错，导致 TextButton（含内部 Icon + Text）整块消失。
                    // 排除 Icon 变量后，TextButton 只剩纯 Text，必能渲染。
                    if (logExpanded) {
                        TextButton(
                            onClick = {
                                val text = LogManager.shared.getAllAsText()
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                val count = LogManager.shared.logs.value.size
                                Toast.makeText(
                                    context,
                                    "已复制 $count 条日志",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("复制")
                        }
                    }

                    Icon(
                        imageVector = if (logExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (logExpanded) "Collapse" else "Expand"
                    )
                }

                if (logExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    val logs by LogManager.shared.logs.collectAsStateWithLifecycle()

                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // 使用 Column + verticalScroll 替代 LazyColumn，避免懒加载机制
                        // 与 SelectionContainer 的文本选择手势冲突（长按单行无反应）。
                        // 整列外包一个 SelectionContainer，单行/跨行选中都可用。
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                logs.forEach { log ->
                                    Text(
                                        text = "[${log.timestamp}] ${log.level}/${log.tag}: ${log.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (log.level) {
                                            "ERROR" -> MaterialTheme.colorScheme.error
                                            "WARN" -> SciFiError
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }

                        // 注意："清空"按钮已移除——重启 App 即可清空日志，不需要在 UI 里留。
                        // "复制全部"按钮已上移到 Card 标题行右侧（与 chevron 并排），
                        // 那里只渲染一次，避免和别的子组件交互产生静默报错。
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

// ==================== 权限管理组件 ====================

/**
 * 权限管理卡片
 */
@Composable
fun PermissionsCard(
    permissionManager: PermissionManager,
    onRequestPermissions: (Array<String>) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val groups = remember(refreshKey) {
        permissionManager.getAllPermissionGroups()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "权限管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            groups.forEach { group ->
                PermissionRow(
                    displayName = group.displayName,
                    isGranted = group.isGranted,
                    onGrant = {
                        if (group.isSpecialPermission) {
                            onRequestAllFilesAccess()
                        } else {
                            onRequestPermissions(group.permissions)
                        }
                    },
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                if (group != groups.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

/**
 * 单行权限项
 */
@Composable
private fun PermissionRow(
    displayName: String,
    isGranted: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isGranted) SciFiPrimary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (isGranted) {
            Text(
                text = "已授权",
                style = MaterialTheme.typography.labelSmall,
                color = SciFiPrimary
            )
        } else {
            TextButton(onClick = onGrant) {
                Text("授权")
            }
        }
    }
}
