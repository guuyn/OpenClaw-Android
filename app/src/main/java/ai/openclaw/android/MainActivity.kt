package ai.openclaw.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.OpenClawTheme
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.domain.Deliverable

import ai.openclaw.android.domain.RichContent
import ai.openclaw.android.ui.components.SessionListDrawer
import ai.openclaw.android.voice.VoiceInteractionManager
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.notification.NotificationScreen
import ai.openclaw.android.personalcenter.PersonalCenterScreen
import ai.openclaw.android.personalcenter.PersonalCenterViewModel
import ai.openclaw.android.personalcenter.PersonalCenterViewModelFactory
import ai.openclaw.android.viewmodel.ChatViewModel
import ai.openclaw.android.viewmodel.ChatViewModelFactory
import ai.openclaw.android.viewmodel.TriggerViewModel
import ai.openclaw.android.ui.trigger.TriggerScreen
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.trigger.scheduler.CronScheduler
import ai.openclaw.android.trigger.EventBus
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch


/** Unwrap ContextWrapper to find the Activity */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val VOLUME_LONG_PRESS_MS = 300L // 长按阈值
    }

    private lateinit var chatViewModel: ChatViewModel

    private var gatewayContract: GatewayContract? = null
    private var serviceBound = false

    // 音量键语音输入
    private var volumeLongPressRunnable: Runnable? = null
    private var volumeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 语音输入运行状态（Compose 侧维护） */
    var isVolumeKeyListening = false

    /** 当前识别文字（Compose 侧实时更新） */
    var volumeKeyTranscript: String = ""

    /** 信号：告诉 Compose 音量键已释放，该弹确认框了 */
    private val _volumeKeyReleased = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyReleased = _volumeKeyReleased.asSharedFlow()

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Compose 回调钩子
    var onStartVolumeVoice: (() -> Unit)? = null
    var onStopVolumeVoice: (() -> Unit)? = null
    var onSendVolumeVoice: ((String) -> Unit)? = null
    var hasRecordAudioPerm: () -> Boolean = { false }
    var requestRecordAudioPerm: () -> Unit = {}

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as GatewayService.LocalBinder
            gatewayContract = localBinder.getGatewayContract()
            serviceBound = true
            // 注入 RealGateway（非测试模式时生效）
            chatViewModel.updateGatewayContract(gatewayContract)
            Log.d("MainActivity", "GatewayService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayContract = null
            serviceBound = false
            Log.d("MainActivity", "GatewayService unbound")
        }
    }

    /**
     * 获取当前 GatewayContract 的提供者，用于 ViewModel 切换回 RealGateway
     */
    private fun gatewayContractProvider(): GatewayContract? = gatewayContract

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel
        chatViewModel = ViewModelProvider(this, ChatViewModelFactory(application))[ChatViewModel::class.java]

        // Inject GatewayContract provider so ViewModel can access sessions/memory via GatewayManager
        chatViewModel.setGatewayContractProvider { gatewayContract }

        // Initialize ViewModel internals (model client, memory, session, etc.)
        chatViewModel.initialize(this)

        // Register broadcast receiver for test injection (Activity level)
        val testReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    when (intent?.action) {
                        "ai.openclaw.android.DEBUG_SEND_MESSAGE" -> {
                            val text = intent.getStringExtra("message") ?: return
                            Log.d("MainActivity", "[DEBUG BROADCAST] Received: $text")
                            chatViewModel.sendMessage(text)
                        }
                        "ai.openclaw.android.TEST_A2UI_JSON" -> {
                            val a2uiJson = intent.getStringExtra("a2ui_json") ?: return
                            Log.d("MainActivity", "[TEST A2UI] Received JSON: $a2uiJson")
                            chatViewModel.testInjectA2UI(a2uiJson)
                        }
                        "ai.openclaw.android.INJECT_A2UI_TEST" -> {
                            try {
                                var a2uiJson = intent.getStringExtra("a2ui_json")
                                if (a2uiJson == null || a2uiJson.isBlank()) {
                                    val filePath = intent.getStringExtra("a2ui_file")
                                        ?: "${this@MainActivity.filesDir.absolutePath}/test_input.json"
                                    val file = java.io.File(filePath)
                                    if (!file.exists()) {
                                        Log.e("MainActivity", "[INJECT A2UI TEST] File does not exist: $filePath")
                                        return
                                    }
                                    a2uiJson = file.readText()
                                    Log.d("MainActivity", "[INJECT A2UI TEST] Read from file: $filePath")
                                }
                                chatViewModel.testInjectA2UI(a2uiJson)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "[INJECT A2UI TEST] Error: ${e.message}", e)
                                chatViewModel.testInjectA2UI("[A2UI注入错误] ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "[BROADCAST ERROR] ${e.message}", e)
                }
            }
        }
        val testFilter = IntentFilter().apply {
            addAction("ai.openclaw.android.DEBUG_SEND_MESSAGE")
            addAction("ai.openclaw.android.TEST_A2UI_JSON")
            addAction("ai.openclaw.android.INJECT_A2UI_TEST")
        }
        registerReceiver(testReceiver, testFilter, Context.RECEIVER_EXPORTED)

        // Ensure GatewayService is running (model + memory + skills)
        GatewayService.start(this)

        // Bind to get GatewayContract interface
        Intent(this, GatewayService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            OpenClawTheme {
                MainScreen(
                    chatViewModel = chatViewModel,
                    gatewayContractProvider = { gatewayContract },
                    initialTab = intent?.getIntExtra("tab_index", 0) ?: 0
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tabIndex = intent.getIntExtra("tab_index", -1)
        if (tabIndex >= 0) {
            // Use a workaround: finish and restart with new tab
            val newIntent = Intent(this, MainActivity::class.java)
            newIntent.putExtra("tab_index", tabIndex)
            newIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(newIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    /**
     * 拦截音量下键：按住说话，松开停止 + 弹确认框。短按正常调音量。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 已在录音中 → 忽略重复按下（按住不放不会重复触发）
                if (isVolumeKeyListening) return true
                // 延迟触发：300ms 后启动语音
                volumeLongPressRunnable = Runnable { startVoiceInput() }
                volumeHandler.postDelayed(volumeLongPressRunnable!!, VOLUME_LONG_PRESS_MS)
                return true // 消费事件
            }
            KeyEvent.ACTION_UP -> {
                volumeLongPressRunnable?.let { volumeHandler.removeCallbacks(it) }
                if (isVolumeKeyListening) {
                    isVolumeKeyListening = false
                    onStopVolumeVoice?.invoke()
                    // Signal Compose to show dialog after async transcript update
                    activityScope.launch {
                        kotlinx.coroutines.delay(400)
                        _volumeKeyReleased.tryEmit(Unit)
                    }
                    return true
                }
                // 短按 → 正常调音量
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startVoiceInput() {
        if (!hasRecordAudioPerm()) {
            requestRecordAudioPerm()
            return
        }
        onStartVolumeVoice?.invoke()
        isVolumeKeyListening = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    chatViewModel: ChatViewModel,
    gatewayContractProvider: () -> GatewayContract?,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var showModelManager by remember { mutableStateOf(false) }

    // === Session 管理状态 ===
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val sessions by chatViewModel.allSessions.collectAsStateWithLifecycle()
    val currentSessionId by chatViewModel.currentSessionId.collectAsStateWithLifecycle()
    val currentSessionName = remember(sessions, currentSessionId) {
        sessions.find { it.sessionId == currentSessionId }?.name?.takeIf { it.isNotBlank() } ?: "新对话"
    }

    // Chat state — consumed from ViewModel via StateFlow
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val isLoading by chatViewModel.isLoading.collectAsStateWithLifecycle()
    val lastDeliverable by chatViewModel.lastDeliverable.collectAsStateWithLifecycle()
    val lastRichContent by chatViewModel.lastRichContent.collectAsStateWithLifecycle()
    val isTestMode by chatViewModel.isTestMode.collectAsStateWithLifecycle()
    val confirmRequest by chatViewModel.confirmRequest.collectAsStateWithLifecycle()

    // Configuration state
    var modelApiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var modelProvider by remember { mutableStateOf(
        try { ConfigManager.getModelProvider() } catch (_: Exception) { "OPENAI" }
    ) }
    var modelBaseUrl by remember { mutableStateOf("") }

    // UI state
    var serviceRunning by remember { mutableStateOf(false) }
    var configExpanded by remember { mutableStateOf(true) }
    var logExpanded by remember { mutableStateOf(false) }

    // Check if GatewayService is actually running (via gatewayContract availability)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000) // Wait for service binding
        val contract = gatewayContractProvider()
        serviceRunning = contract?.isReady() == true || ConfigManager.isServiceEnabled()
    }

    val permManager = remember { context.permissionManager() }

    // Voice manager for TTS (single instance — hoisted to MainScreen)
    val voiceManager = remember { VoiceInteractionManager(context) }
    val voiceState by voiceManager.sessionState.collectAsStateWithLifecycle()
    val voiceTranscript by voiceManager.transcript.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val sttPath = "/storage/emulated/0/Android/data/ai.openclaw.android/files/models/stt"
        val ttsPath = "/storage/emulated/0/Android/data/ai.openclaw.android/files/models/tts"
        voiceManager.initialize(sttModelPath = sttPath, ttsModelPath = ttsPath)
    }
    DisposableEffect(Unit) {
        onDispose { voiceManager.destroy() }
    }

    // Voice collect flow — MUST be collected to actually start STT engine
    var voiceCollectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val startVoiceCollect: (VoiceInteractionManager, kotlinx.coroutines.CoroutineScope) -> Unit = { vm, sc ->
        voiceCollectJob?.cancel()
        voiceCollectJob = sc.launch {
            vm.startListening().collect { result ->
                // VoiceInteractionManager already updates internal transcript/state
            }
        }
    }

    // Audio permission launcher for voice (mic button in ChatScreen)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceCollect(voiceManager, scope)
        }
    }

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
        settingsPermRefreshKey++
    }

    // Launcher for All Files Access (MANAGE_EXTERNAL_STORAGE) settings page
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        settingsPermRefreshKey++
    }

    // MediaProjection (screen capture) launcher
    var screenCaptureReady by remember { mutableStateOf(false) }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val contract = gatewayContractProvider()
            contract?.initScreenCapture(result.resultCode, result.data!!)
            screenCaptureReady = true
        }
    }

    // Observe chat-triggered permission requests
    val currentPermRequest by permManager.currentRequest.collectAsStateWithLifecycle()
    LaunchedEffect(currentPermRequest) {
        currentPermRequest?.let { request ->
            if (permManager.hasPermissions(request.permissions)) {
                permManager.resolveRequest(request.id, true)
            } else if (permManager.hasSpecialPermission(request.permissions)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                allFilesAccessLauncher.launch(intent)
            } else {
                chatPermissionLauncher.launch(request.permissions)
            }
        }
    }

    // Re-check special permission status when returning from settings
    LaunchedEffect(settingsPermRefreshKey) {
        val req = permManager.currentRequest.value
        if (req != null && permManager.hasSpecialPermission(req.permissions)) {
            val granted = permManager.hasPermissions(req.permissions)
            permManager.resolveRequest(req.id, granted)
        }
    }

    // Load saved configuration on startup
    LaunchedEffect(Unit) {
        ConfigManager.init(context)

        if (!ConfigManager.hasModelCredentials()) {
            // Debug: set default credentials for automated testing
            // ⚠️ Do NOT hardcode API keys in production
            ConfigManager.setModelProvider("OPENAI")
            ConfigManager.setModelBaseUrl("https://coding.dashscope.aliyuncs.com/v1")
            ConfigManager.setModelApiKey("sk-sp-20300993405641aab0fb73aedac15d33")
            Log.d("MainScreen", "Default API key set for debugging")
        }

        modelApiKey = ConfigManager.getModelApiKey()
        modelName = ConfigManager.getModelName()
        modelProvider = try { ConfigManager.getModelProvider() } catch (_: Exception) { "OPENAI" }
        modelBaseUrl = ConfigManager.getModelBaseUrl()
        serviceRunning = ConfigManager.isServiceEnabled()
    }

    // Send message — delegates to ViewModel
    val sendMessage: (String, List<ai.openclaw.android.model.ImageContent>) -> Unit = { text, images ->
        chatViewModel.sendMessage(text, images)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionListDrawer(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onCreateSession = {
                    scope.launch { chatViewModel.createNewSession() }
                    scope.launch { drawerState.close() }
                },
                onSwitchSession = { sessionId ->
                    scope.launch {
                        chatViewModel.switchToSession(sessionId)
                        drawerState.close()
                    }
                },
                onRenameSession = { sessionId, newName ->
                    scope.launch { chatViewModel.renameSession(sessionId, newName) }
                },
                onDeleteSession = { sessionId ->
                    scope.launch { chatViewModel.deleteSession(sessionId) }
                },
                getMessageCount = { sessionId ->
                    chatViewModel.getMessageCount(sessionId)
                }
            )
        },
        gesturesEnabled = true
    ) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (selectedTab) {
                        0 -> "聊天"
                        1 -> "通知"
                        2 -> "设置"
                        3 -> "触发器"
                        else -> ""
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
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.AutoFixHigh, "触发器") },
                    label = { Text("触发器") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> {
                val activity = context.findActivity() as? MainActivity

                // Wire up volume key callbacks
                LaunchedEffect(activity) {
                    activity?.onStartVolumeVoice = {
                        activity.volumeKeyTranscript = ""
                        startVoiceCollect(voiceManager, scope)
                    }
                    activity?.onStopVolumeVoice = {
                        voiceManager.stopListening()
                        voiceCollectJob?.cancel()
                        voiceCollectJob = null
                    }
                    activity?.onSendVolumeVoice = { text -> sendMessage(text, emptyList()) }
                    activity?.hasRecordAudioPerm = { voiceManager.hasRecordAudioPermission() }
                    activity?.requestRecordAudioPerm = {
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }

                // Volume key confirm dialog state (Compose-side)
                var showVolumeConfirm by remember { mutableStateOf(false) }
                var volumePendingText by remember { mutableStateOf("") }

                // Listen for volume key release signal from Activity
                LaunchedEffect(activity) {
                    activity?.volumeKeyReleased?.collect {
                        // Read transcript AFTER the async stopListening has completed
                        val text = voiceManager.transcript.value.trim()
                        if (text.isNotBlank()) {
                            volumePendingText = text
                            showVolumeConfirm = true
                        }
                    }
                }

                ChatScreen(
                    sendMessage = sendMessage,
                    messages = messages.toList(),
                    isLoading = isLoading,
                    modifier = Modifier.padding(padding),
                    scaffoldPadding = padding,
                    lastDeliverable = lastDeliverable,
                    lastRichContent = lastRichContent,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    sessionName = currentSessionName,
                    allSessions = sessions,
                    currentSessionId = currentSessionId,
                    onCreateSession = {
                        scope.launch { chatViewModel.createNewSession() }
                    },
                    onSwitchSession = { sessionId ->
                        scope.launch { chatViewModel.switchToSession(sessionId) }
                    },
                    onRenameSession = { sessionId, newName ->
                        scope.launch { chatViewModel.renameSession(sessionId, newName) }
                    },
                    onDeleteSession = { sessionId ->
                        scope.launch { chatViewModel.deleteSession(sessionId) }
                    },
                    getMessageCount = { sessionId ->
                        chatViewModel.getMessageCount(sessionId)
                    },
                    onSpeakText = { text ->
                        scope.launch { voiceManager.speak(text) }
                    },
                    voiceState = voiceState,
                    voiceTranscript = voiceTranscript,
                    onStartListening = { startVoiceCollect(voiceManager, scope) },
                    onStopListening = {
                        voiceManager.stopListening()
                        voiceCollectJob?.cancel()
                        voiceCollectJob = null
                    },
                    hasRecordAudioPermission = { voiceManager.hasRecordAudioPermission() },
                    onRequestAudioPermission = {
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    isVolumeKeyListening = { activity?.isVolumeKeyListening == true },
                    showVolumeKeyConfirm = { showVolumeConfirm },
                    volumeKeyPendingTextProvider = { volumePendingText },
                    onDismissVolumeKeyConfirm = {
                        showVolumeConfirm = false
                        volumePendingText = ""
                    },
                    onSendVolumeKeyVoice = { text ->
                        sendMessage(text, emptyList())
                        showVolumeConfirm = false
                        volumePendingText = ""
                    },
                    confirmRequest = confirmRequest,
                    onConfirmResult = { result ->
                        chatViewModel.submitConfirmResult(result)
                    },
                )
            }
            1 -> {
                val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<PersonalCenterViewModel>(
                    factory = PersonalCenterViewModelFactory(
                        context.applicationContext as android.app.Application,
                        permManager
                    )
                )
                // 通话记录权限请求
                val callLogPermissionLauncher = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.onCallLogPermissionResult(granted)
                }
                LaunchedEffect(Unit) {
                    // 首次进入时立即请求权限
                    viewModel.requestCallLogPermissionIfNeeded()
                    // 持续监听后续的权限请求
                    while (true) {
                        viewModel.triggerCallLogPermissionRequest.receive()
                        callLogPermissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
                    }
                }
                // 注入 LLM 评估能力（个人中心需要 GatewayContract 来调用 LLM 做语义过滤）
                LaunchedEffect(Unit) {
                    viewModel.setGatewayContract(gatewayContractProvider())
                }
                PersonalCenterScreen(
                    viewModel = viewModel,
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
                modelProvider = modelProvider,
                onModelProviderChange = { modelProvider = it },
                modelBaseUrl = modelBaseUrl,
                onModelBaseUrlChange = { modelBaseUrl = it },
                configExpanded = configExpanded,
                onConfigExpandedChange = { configExpanded = it },
                logExpanded = logExpanded,
                onLogExpandedChange = { logExpanded = it },
                testModeEnabled = isTestMode,
                onTestModeToggle = {
                    chatViewModel.setTestMode(
                        enabled = !isTestMode,
                        contractProvider = gatewayContractProvider
                    )
                },
                onSaveConfig = {
                    scope.launch {
                        // 持久化配置到 SharedPreferences, 避免重启后丢失配置
                        try {
                            ConfigManager.setModelApiKey(modelApiKey)
                            ConfigManager.setModelName(modelName)
                            ConfigManager.setModelBaseUrl(modelBaseUrl)
                            ConfigManager.setModelProvider(modelProvider)
                            LogManager.shared.log("INFO", "MainActivity", "Persisted config to SharedPreferences: apiKey len=${modelApiKey.length} model=$modelName baseUrl=$modelBaseUrl provider=$modelProvider")
                        } catch (e: Exception) {
                            LogManager.shared.log("ERROR", "MainActivity", "Failed to persist config: ${e.message}")
                        }
                        val contract = gatewayContractProvider()
                        val success = contract?.reconfigureModel(
                            ModelConfig(
                                provider = try {
                                    ModelProvider.valueOf(modelProvider)
                                } catch (_: Exception) {
                                    ModelProvider.OPENAI
                                },
                                apiKey = modelApiKey,
                                modelName = modelName,
                                baseUrl = modelBaseUrl
                            )
                        )
                        if (success == true) {
                            LogManager.shared.log("INFO", "MainActivity", "Configuration saved and model reconfigured (provider: $modelProvider)")
                        } else {
                            LogManager.shared.log("ERROR", "MainActivity", "Failed to reconfigure model")
                        }
                    }
                },
                permissionManager = permManager,
                onRequestPermissions = { permissions ->
                    settingsPermissionLauncher.launch(permissions)
                },
                onRequestAllFilesAccess = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    allFilesAccessLauncher.launch(intent)
                },
                onRequestScreenCapture = {
                    val contract = gatewayContractProvider()
                    val intent = contract?.getScreenCaptureIntent()
                    if (intent != null) {
                        screenCaptureLauncher.launch(intent)
                    }
                },
                isScreenCaptureReady = screenCaptureReady,
                settingsPermRefreshKey = settingsPermRefreshKey,
                onOpenModelManager = { showModelManager = true },
                modifier = Modifier.padding(padding)
            )
            3 -> {
                TriggerScreen(
                    viewModel = remember { TriggerViewModel(
                        database = AppDatabase.getInstance(context),
                        agentSessionFactory = { null },
                        cronScheduler = CronScheduler(context, EventBus.instance!!)
                    ) },
                    onNavigateBack = { selectedTab = 0 },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // 模型管理全屏覆盖层
    if (showModelManager) {
        ai.openclaw.android.ui.ModelDownloadScreen(
            modelManager = ai.openclaw.android.voice.ModelDownloadManager,
            onNavigateBack = { showModelManager = false }
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
    modelProvider: String,
    onModelProviderChange: (String) -> Unit,
    modelBaseUrl: String,
    onModelBaseUrlChange: (String) -> Unit,
    configExpanded: Boolean,
    onConfigExpandedChange: (Boolean) -> Unit,
    logExpanded: Boolean,
    onLogExpandedChange: (Boolean) -> Unit,
    testModeEnabled: Boolean,
    onTestModeToggle: () -> Unit,
    onSaveConfig: () -> Unit,
    permissionManager: PermissionManager,
    onRequestPermissions: (Array<String>) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    isScreenCaptureReady: Boolean,
    settingsPermRefreshKey: Int,
    onOpenModelManager: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
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

        // Test Mode Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "测试模式",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (testModeEnabled) "启用 - 使用 Mock 数据" else "禁用 - 使用真实数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (testModeEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = testModeEnabled,
                        onCheckedChange = { onTestModeToggle() }
                    )
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

                    // Provider selector
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

                    // API Key (only needed for cloud providers)
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
                        // Local model info
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
                                    text = "Gemma 4 E4B 端侧推理\n模型路径: /sdcard/Download/gemma-4-E4B-it.litertlm",
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

        // Permissions Card
        PermissionsCard(
            permissionManager = permissionManager,
            onRequestPermissions = onRequestPermissions,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            refreshKey = settingsPermRefreshKey
        )

        // 模型管理入口
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenModelManager),
            shape = RoundedCornerShape(12.dp)
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
                        tint = MaterialTheme.colorScheme.primary
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

        // Screen Capture Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isScreenCaptureReady) Icons.Default.CheckCircle else Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = if (isScreenCaptureReady)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "屏幕截图权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isScreenCaptureReady) "已授权 — screenshot 工具可用" else "未授权 — 需要授权才能截图",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isScreenCaptureReady)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = onRequestScreenCapture
                    ) {
                        Text(if (isScreenCaptureReady) "重新授权" else "授权")
                    }
                }
            }
        }

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

                    // 复制全部按钮：仅在展开时显示
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
    onRequestAllFilesAccess: () -> Unit,
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
