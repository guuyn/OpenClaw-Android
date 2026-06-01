package ai.openclaw.android.plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiBackground
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiOnSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary
import ai.openclaw.android.ui.theme.SciFiSurface
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

/**
 * 插件管理 Compose 屏幕
 *
 * 功能：
 * - 已安装插件列表（启用/禁用开关、卸载按钮、更新提示）
 * - 安装新插件按钮（从文件选择器）
 * - 科幻主题风格
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    pluginManagerExt: PluginManagerExt,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val plugins by pluginManagerExt.installedPlugins.collectAsState(initial = emptyList())
    val operationState by pluginManagerExt.operationState.collectAsState(initial = PluginOperationState.Idle)

    var showInstallDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf<String?>(null) }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val file = android.net.Uri.parse(uri.toString())
            // 将 URI 内容复制到临时文件
            try {
                val contentResolver = context.contentResolver
                val tempFile = java.io.File(context.cacheDir, "plugin_temp_${System.currentTimeMillis()}.${contentResolver.getType(uri)?.substringAfter('/') ?: "zip"}")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                scope.launch {
                    pluginManagerExt.installPlugin(tempFile)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                // 安装失败会通过 operationState 通知 UI
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "⚡ 插件管理",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showInstallDialog = true }) {
                        Icon(Icons.Default.Add, "安装插件")
                    }
                    IconButton(onClick = { pluginManagerExt.refreshPlugins() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SciFiSurface,
                    titleContentColor = SciFiPrimary
                )
            )
        },
        containerColor = SciFiBackground,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 操作状态提示
            AnimatedVisibility(
                visible = operationState !is PluginOperationState.Idle,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OperationStateBanner(state = operationState)
            }

            // 插件列表
            if (plugins.isEmpty()) {
                EmptyPluginList(
                    onInstallClick = { showInstallDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(plugins, key = { it.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onToggle = { enabled ->
                                pluginManagerExt.togglePlugin(plugin.id, enabled)
                            },
                            onUninstall = {
                                showUninstallDialog = plugin.id
                            },
                            onUpdateClick = {
                                filePickerLauncher.launch("*/*")
                            }
                        )
                    }
                }
            }
        }
    }

    // 安装对话框
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text("安装插件") },
            text = { Text("选择 APK 或 ZIP 插件文件进行安装") },
            confirmButton = {
                Button(onClick = {
                    showInstallDialog = false
                    filePickerLauncher.launch("*/*")
                }) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) {
                    Text("取消")
                }
            },
            containerColor = SciFiSurfaceVariant,
            tonalElevation = 4.dp
        )
    }

    // 卸载确认对话框
    showUninstallDialog?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { showUninstallDialog = null },
            title = { Text("确认卸载") },
            text = { Text("确定要卸载插件 \"$pluginId\" 吗？\n卸载后数据将被删除，但会保留备份以便回滚。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            pluginManagerExt.uninstallPlugin(pluginId)
                            showUninstallDialog = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SciFiError)
                ) {
                    Text("卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = null }) {
                    Text("取消")
                }
            },
            containerColor = SciFiSurfaceVariant
        )
    }
}

/**
 * 操作状态横幅提示
 */
@Composable
private fun OperationStateBanner(
    state: PluginOperationState,
    modifier: Modifier = Modifier
) {
    val (icon, text, bgColor) = when (state) {
        is PluginOperationState.Idle -> return
        is PluginOperationState.InProgress -> Triple(
            Icons.Default.Sync, state.message, SciFiSecondary.copy(alpha = 0.15f)
        )
        is PluginOperationState.Completed -> Triple(
            Icons.Default.CheckCircle, state.message, SciFiPrimary.copy(alpha = 0.15f)
        )
        is PluginOperationState.Error -> Triple(
            Icons.Default.Error, state.message, SciFiError.copy(alpha = 0.15f)
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when (state) {
                    is PluginOperationState.InProgress -> SciFiSecondary
                    is PluginOperationState.Completed -> SciFiPrimary
                    is PluginOperationState.Error -> SciFiError
                    else -> SciFiOnSurfaceVariant
                }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    is PluginOperationState.Error -> SciFiError
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (state is PluginOperationState.InProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = SciFiSecondary
                )
            }
        }
    }
}

/**
 * 空状态占位
 */
@Composable
private fun EmptyPluginList(
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = SciFiOnSurfaceVariant.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "暂无已安装插件",
            style = MaterialTheme.typography.titleMedium,
            color = SciFiOnSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点击 + 安装新插件",
            style = MaterialTheme.typography.bodySmall,
            color = SciFiOnSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onInstallClick,
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("安装插件")
        }
    }
}

/**
 * 单个插件卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginCard(
    plugin: PluginInfo,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SciFiSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (plugin.enabled) SciFiPrimary.copy(alpha = 0.2f) else Color.Transparent
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 头部：图标 + 名称 + 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 插件图标
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = SciFiSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(SciFiPrimary, SciFiSecondary)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = plugin.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = SciFiBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // 名称和版本
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (plugin.enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            SciFiOnSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "v${plugin.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SciFiPrimary
                        )
                        if (plugin.engineType != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = SciFiOnSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = plugin.engineType,
                                style = MaterialTheme.typography.bodySmall,
                                color = SciFiOnSurfaceVariant
                            )
                        }
                        if (plugin.author.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = SciFiOnSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = plugin.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = SciFiOnSurfaceVariant
                            )
                        }
                    }
                }

                // 启用/禁用开关
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SciFiPrimary,
                        checkedTrackColor = SciFiPrimary.copy(alpha = 0.3f)
                    )
                )
            }

            // 描述
            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SciFiOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 操作按钮行
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 更新按钮
                TextButton(
                    onClick = onUpdateClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = SciFiSecondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("更新", color = SciFiSecondary)
                }

                // 卸载按钮
                TextButton(
                    onClick = onUninstall,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = SciFiError.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("卸载", color = SciFiError.copy(alpha = 0.7f))
                }
            }
        }
    }
}
