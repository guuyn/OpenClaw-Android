package ai.openclaw.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.OpenClawTheme
import ai.openclaw.android.model.BailianClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.notification.SmartNotification
import ai.openclaw.android.notification.NotificationScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateListOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenClawTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }

    // Chat state
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isLoading by remember { mutableStateOf(false) }

    // Configuration state
    var modelApiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("qwen-plus") }

    // UI state
    var serviceRunning by remember { mutableStateOf(false) }
    var configExpanded by remember { mutableStateOf(true) }
    var logExpanded by remember { mutableStateOf(false) }

    // Model client state
    val modelClient = remember { BailianClient() }
    var agentSession by remember { mutableStateOf<AgentSession?>(null) }
    val skillManager = remember { SkillManager(context) }
    val permManager = remember { context.permissionManager() }

    // Permission request launcher for chat-triggered requests
    val chatPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val currentReq = permManager.currentRequest.value
        currentReq?.let { req ->
            permManager.resolveRequest(req.id, allGranted)
        }
    }

    // Permission request launcher for settings-initiated requests
    var settingsPermRefreshKey by remember { mutableStateOf(0) }
    val settingsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        settingsPermRefreshKey++ // trigger recomposition of permissions UI
    }

    // Observe chat-triggered permission requests
    val currentPermRequest by permManager.currentRequest.collectAsStateWithLifecycle()
    LaunchedEffect(currentPermRequest) {
        currentPermRequest?.let { request ->
            if (permManager.hasPermissions(request.permissions)) {
                permManager.resolveRequest(request.id, true)
            } else {
                chatPermissionLauncher.launch(request.permissions)
            }
        }
    }

    // Load saved configuration on startup
    LaunchedEffect(Unit) {
        ConfigManager.init(context)

        if (!ConfigManager.hasModelCredentials()) {
            ConfigManager.setModelApiKey("sk-sp-9593971f0d554ff89283cf13faf01f32")
            ConfigManager.setModelName("bailian/qwen3.5-plus")
            Log.d("MainScreen", "Default API key set for debugging")
        }

        modelApiKey = ConfigManager.getModelApiKey()
        modelName = ConfigManager.getModelName()
        serviceRunning = ConfigManager.isServiceEnabled()

        skillManager.loadBuiltinSkills(context)

        if (ConfigManager.hasModelCredentials()) {
            modelClient.configure(
                provider = ModelProvider.BAILIAN,
                apiKey = ConfigManager.getModelApiKey(),
                model = ConfigManager.getModelName()
            )

            agentSession = AgentSession(modelClient, skillManager, permManager)
            agentSession?.setToolsWithSkills(emptyList()) { "Accessibility not available" }

            Log.d("MainScreen", "Agent session initialized with ${skillManager.getSkillCount()} skills")
        }
    }

    val sendMessage: (String) -> Unit = { text ->
        Log.d("MainScreen", "=== sendMessage called ===")
        LogManager.shared.log("INFO", "Chat", "User: $text")

        messages.add(ChatMessage(role = "user", content = text))
        isLoading = true

        scope.launch {
            try {
                val session = agentSession
                if (session == null) {
                    messages.add(ChatMessage(role = "assistant", content = "请先在设置中配置 API Key"))
                    isLoading = false
                    return@launch
                }

                val responseId = java.util.UUID.randomUUID().toString()
                messages.add(ChatMessage(id = responseId, role = "assistant", content = ""))
                val responseIndex = messages.lastIndex

                session.handleMessageStream(text).collect { event ->
                    when (event) {
                        is SessionEvent.Token -> {
                            val current = messages[responseIndex]
                            messages[responseIndex] = current.copy(
                                content = current.content + event.text
                            )
                        }
                        is SessionEvent.ToolExecuting -> {
                            val current = messages[responseIndex]
                            messages[responseIndex] = current.copy(
                                content = current.content + "\n[调用工具: ${event.name}...]\n"
                            )
                        }
                        is SessionEvent.ToolResult -> { }
                        is SessionEvent.Complete -> {
                            val current = messages[responseIndex]
                            messages[responseIndex] = current.copy(content = event.fullText)
                            isLoading = false
                            LogManager.shared.log("INFO", "Chat", "Assistant: ${event.fullText.take(100)}")
                        }
                        is SessionEvent.Error -> {
                            val current = messages[responseIndex]
                            messages[responseIndex] = current.copy(
                                content = current.content.ifEmpty { "错误: ${event.message}" }
                            )
                            isLoading = false
                            LogManager.shared.log("ERROR", "Chat", "Error: ${event.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainScreen", "Chat error: ${e.message}", e)
                messages.add(ChatMessage(role = "assistant", content = "错误: ${e.message}"))
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (selectedTab) {
                        0 -> "聊天"
                        1 -> "通知"
                        else -> "设置"
                    })
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Chat, "聊天") },
                    label = { Text("聊天") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                val pendingCount = SmartNotificationListener.getPendingCount()
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, "通知")
                        }
                    },
                    label = { Text("通知") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, "设置") },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ChatScreen(
                sendMessage = sendMessage,
                messages = messages.toList(),
                isLoading = isLoading,
                modifier = Modifier.padding(padding)
            )
            1 -> {
                val notifications by SmartNotificationListener.notifications.collectAsStateWithLifecycle()
                val hasNotificationPermission = remember {
                    hasNotificationListenerPermission(context)
                }

                NotificationScreen(
                    notifications = notifications,
                    onMarkAsRead = { },
                    onMarkAllAsRead = { },
                    onDelete = { },
                    onClearAll = { },
                    hasPermission = hasNotificationPermission,
                    onRequestPermission = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            2 -> SettingsScreen(
                serviceRunning = serviceRunning,
                onServiceToggle = {
                    if (serviceRunning) {
                        GatewayService.stop(context)
                    } else {
                        GatewayService.start(context)
                    }
                    serviceRunning = !serviceRunning
                },
                modelApiKey = modelApiKey,
                onModelApiKeyChange = { modelApiKey = it },
                modelName = modelName,
                onModelNameChange = { modelName = it },
                configExpanded = configExpanded,
                onConfigExpandedChange = { configExpanded = it },
                logExpanded = logExpanded,
                onLogExpandedChange = { logExpanded = it },
                onSaveConfig = {
                    ConfigManager.setModelApiKey(modelApiKey)
                    ConfigManager.setModelName(modelName)

                    // Reconfigure model client with new settings
                    modelClient.configure(
                        provider = ModelProvider.BAILIAN,
                        apiKey = modelApiKey,
                        model = modelName
                    )
                    agentSession = AgentSession(modelClient, skillManager, permManager)
                    agentSession?.setToolsWithSkills(emptyList()) { "Accessibility not available" }

                    LogManager.shared.log("INFO", "MainActivity", "Configuration saved and session reinitialized")
                },
                permissionManager = permManager,
                onRequestPermissions = { permissions ->
                    settingsPermissionLauncher.launch(permissions)
                },
                settingsPermRefreshKey = settingsPermRefreshKey,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
fun SettingsScreen(
    serviceRunning: Boolean,
    onServiceToggle: () -> Unit,
    modelApiKey: String,
    onModelApiKeyChange: (String) -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    configExpanded: Boolean,
    onConfigExpandedChange: (Boolean) -> Unit,
    logExpanded: Boolean,
    onLogExpandedChange: (Boolean) -> Unit,
    onSaveConfig: () -> Unit,
    permissionManager: PermissionManager,
    onRequestPermissions: (Array<String>) -> Unit,
    settingsPermRefreshKey: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
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

        // Configuration Card
        Card(modifier = Modifier.fillMaxWidth()) {
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

                    OutlinedTextField(
                        value = modelApiKey,
                        onValueChange = onModelApiKeyChange,
                        label = { Text("API Key (明文显示)") },
                        placeholder = { Text("sk-xxx") },
                        // 明文显示 API Key 以便调试验证
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
                        placeholder = { Text("qwen-plus") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true
                    )

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

        // Permissions Card
        PermissionsCard(
            permissionManager = permissionManager,
            onRequestPermissions = onRequestPermissions,
            refreshKey = settingsPermRefreshKey
        )

        // Log Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLogExpandedChange(!logExpanded) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "运行日志",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            items(logs) { log ->
                                Text(
                                    text = "[${log.timestamp}] ${log.level}/${log.tag}: ${log.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (log.level) {
                                        "ERROR" -> MaterialTheme.colorScheme.error
                                        "WARN" -> Color(0xFFFFA500)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(onClick = { LogManager.shared.clear() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空")
                        }
                    }
                }
            }
        }
    }
}

fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    intent.data = Uri.parse("package:${context.packageName}")
    context.startActivity(intent)
}

fun hasNotificationListenerPermission(context: Context): Boolean {
    val packageName = context.packageName
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.contains(packageName)
}

// ==================== Permissions Card ====================

@Composable
fun PermissionsCard(
    permissionManager: PermissionManager,
    onRequestPermissions: (Array<String>) -> Unit,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val groups = remember(refreshKey) {
        permissionManager.getAllPermissionGroups()
    }

    Card(modifier = modifier.fillMaxWidth()) {
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
                    onGrant = { onRequestPermissions(group.permissions) },
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
            tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
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
                color = Color(0xFF4CAF50)
            )
        } else {
            TextButton(onClick = onGrant) {
                Text("授权")
            }
        }
    }
}
