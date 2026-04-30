# 多会话管理 UI 设计文档

> 创建时间: 2026-04-30  
> 状态: 设计阶段  
> 关联文件: `ChatScreen.kt`, `MainActivity.kt`, `ChatViewModel.kt`, `HybridSessionManager.kt`

---

## 1. 架构概览

### 1.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                          │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Scaffold                                         │  │
│  │  ┌─────────────────────────────────────────────┐  │  │
│  │  │  ModalNavigationDrawer (侧边栏)             │  │  │
│  │  │  ┌───────────────────────────────────────┐  │  │  │
│  │  │  │  SessionListDrawer (新增)             │  │  │  │
│  │  │  │  ├─ 会话列表 (Flow<List<SessionEntity>>)│  │  │  │
│  │  │  │  ├─ 当前会话高亮                       │  │  │  │
│  │  │  │  ├─ 时间戳格式化                       │  │  │  │
│  │  │  │  ├─ 消息数显示                         │  │  │  │
│  │  │  │  └─ 长按菜单 (重命名/删除)             │  │  │  │
│  │  │  └───────────────────────────────────────┘  │  │  │
│  │  │                                              │  │  │
│  │  │  ┌───────────────────────────────────────┐  │  │  │
│  │  │  │  ChatScreen (已有，微调)              │  │  │  │
│  │  │  │  ├─ TopBar: 添加菜单按钮(☰)           │  │  │  │
│  │  │  │  ├─ 消息列表                           │  │  │  │
│  │  │  │  └─ 输入区                             │  │  │  │
│  │  │  └───────────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────┘  │
│                                                          │
│  ChatViewModel (复用，不新建)                            │
│  ├─ sessionManager: HybridSessionManager (已有)         │
│  ├─ messages: StateFlow<List<ChatMessage>> (已有)       │
│  └─ [新增] session 管理方法:                             │
│      ├─ currentSessionId: StateFlow<String?>            │
│      ├─ allSessions: StateFlow<List<SessionEntity>>     │
│      ├─ createNewSession()                               │
│      ├─ switchSession(sessionId)                         │
│      ├─ renameSession(sessionId, name)                   │
│      └─ deleteSession(sessionId)                         │
└─────────────────────────────────────────────────────────┘
```

### 1.2 数据流架构

```
UI Layer (Compose)
    │
    ├── SessionListDrawer ← ChatViewModel.allSessions (StateFlow)
    │       │ 点击切换
    │       ▼
    ├── ChatViewModel.switchSession(sessionId)
    │       │ 委托
    │       ▼
    │   HybridSessionManager.switchToSession(sessionId)
    │       │ 更新 currentSession
    │       ▼
    │   SessionDao.getSessionById(sessionId)
    │       │ 返回
    │       ▼
    │   ChatViewModel → 重建 AgentSession 状态
    │       │ 通知
    │       ▼
    │   ChatScreen.messages 清空 + 加载历史消息
    │
    └── ChatViewModel.createSession()
            ▼
        HybridSessionManager.createNewSession()
            ▼
        SessionDao.insertSession()
            ▼
        allSessions Flow 自动更新 → UI 刷新
```

---

## 2. UI 组件树

```
ModalNavigationDrawer (drawerState)
├── drawerContent
│   └── SessionListDrawer (新增)
│       ├── DrawerHeader
│       │   ├── Text("会话列表")
│       │   └── IconButton(+) → createNewSession()
│       ├── HorizontalDivider
│       └── LazyColumn
│           └── items(sessionList)
│               └── SessionListItem (新增)
│                   ├── CombinedClickable (click → switch / longClick → menu)
│                   ├── Icon (📌 / 🗂️ 根据状态)
│                   ├── Column
│                   │   ├── Text(session.displayName) — "新对话" 默认名
│                   │   └── Row
│                   │       ├── Text(timeAgo) — "刚刚" / "3小时前"
│                   │       └── Text("$msgCount 条消息")
│                   └── ActiveIndicator (当前会话高亮条)
│
└── content
    └── Scaffold (已有)
        ├── topBar
        │   └── TopAppBar
        │       ├── IconButton(☰) → drawerState.open()  ← 新增
        │       ├── Text("OpenClaw · qwen-plus")
        │       └── Spacer(weight=1)
        ├── bottomBar (已有，不变)
        └── ChatScreen (已有，微调)
            ├── TopBar 区域 (集成到 Scaffold topBar)
            ├── LazyColumn (消息列表，不变)
            └── InputArea (不变)
```

---

## 3. 交互流程

### 3.1 打开侧边栏
```
用户点击 TopBar 左侧 ☰ 按钮
    → drawerState.open()
    → ModalNavigationDrawer 从左侧滑入
    → SessionListDrawer 显示所有会话列表
```

### 3.2 切换会话
```
用户点击列表中的会话项
    → ChatViewModel.switchSession(sessionId)
    → HybridSessionManager.switchToSession(sessionId)
    → ChatViewModel.clearHistory() (清空当前消息列表)
    → [可选] 从 DB 加载该会话的历史消息到 messages
    → drawerState.close()
    → ChatScreen 显示新会话的空消息列表
```

### 3.3 新建会话
```
用户点击侧边栏顶部 "+" 按钮
    → ChatViewModel.createNewSession()
    → HybridSessionManager.createNewSession(name = null)
    → SessionDao.insertSession(newEntity)
    → allSessions Flow 自动发射新列表
    → UI 自动刷新，新会话出现在列表顶部
    → 自动切换到新会话 (同上)
```

### 3.4 重命名会话
```
用户长按会话项
    → 弹出 AlertDialog (TextField 输入框)
    → 用户输入新名称 → 点击确认
    → ChatViewModel.renameSession(sessionId, newName)
    → HybridSessionManager → SessionDao.updateSession()
    → Flow 自动更新 → UI 刷新
```

### 3.5 删除会话
```
用户长按会话项 → 选择"删除"
    → 弹出确认对话框
    → 用户确认
    → ChatViewModel.deleteSession(sessionId)
    → SessionDao.deleteSessionById(sessionId)
    → 如果删除的是当前会话 → 自动切换到第一个可用会话
    → Flow 自动更新 → UI 刷新
```

---

## 4. ChatViewModel 扩展设计

ChatViewModel 不需要新建，只需在现有类中增加以下公开接口：

```kotlin
// === 新增：Session 管理 StateFlow ===

/** 当前会话 ID */
private val _currentSessionId = MutableStateFlow<String?>(null)
val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

/** 所有会话列表（按 lastActiveAt 降序）*/
private val _allSessions = MutableStateFlow<List<SessionEntity>>(emptyList())
val allSessions: StateFlow<List<SessionEntity>> = _allSessions.asStateFlow()

/** 获取会话的消息数 */
suspend fun getMessageCount(sessionId: String): Int {
    return database.messageDao().getMessageCountBySessionId(sessionId)
}

// === 新增：Session 操作方法 ===

fun createNewSession() {
    viewModelScope.launch {
        sessionManager?.createNamedSession(null)?.let { session ->
            _currentSessionId.value = session.sessionId
            clearHistory()
        }
    }
}

suspend fun switchToSession(sessionId: String) {
    sessionManager?.switchToSession(sessionId)?.getOrNull()?.let {
        _currentSessionId.value = it.sessionId
        clearHistory()
    }
}

fun renameSession(sessionId: String, newName: String) {
    viewModelScope.launch {
        val session = sessionManager?.let {
            val s = database.sessionDao().getSessionById(sessionId)
            s?.copy(name = newName)?.also { database.sessionDao().updateSession(it) }
        }
        if (session != null) {
            // 如果重命名的是当前会话，触发 UI 刷新
        }
    }
}

fun deleteSession(sessionId: String) {
    viewModelScope.launch {
        database.sessionDao().deleteSessionById(sessionId)
        // 如果删除的是当前会话，自动切换到第一个可用会话
        if (_currentSessionId.value == sessionId) {
            val remaining = database.sessionDao().getAllSessions().firstOrNull()
            remaining?.firstOrNull()?.let {
                switchToSession(it.sessionId)
            }
        }
    }
}
```

### 4.1 初始化时收集 Session 列表

在 `initialize()` 方法中增加：

```kotlin
// 在 setupMemorySubsystem 之后
viewModelScope.launch {
    database.sessionDao().getAllSessions().collect { sessions ->
        _allSessions.value = sessions
        // 自动设置当前会话 ID
        if (_currentSessionId.value == null) {
            sessionManager?.getCurrentSessionId()?.let {
                _currentSessionId.value = it
            }
        }
    }
}
```

---

## 5. 具体 Compose 代码

### 5.1 工具函数：时间格式化

```kotlin
package ai.openclaw.android.ui.components

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * 相对时间格式化
 * 刚刚 / 5分钟前 / 3小时前 / 昨天 / 3天前 / 2026-04-25
 */
fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 172_800_000 -> "昨天"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> {
            val format = SimpleDateFormat("MM-dd", Locale.getDefault())
            format.format(Date(timestampMs))
        }
    }
}
```

### 5.2 新增组件：SessionListDrawer

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.ui.theme.*

/**
 * 会话列表侧边栏内容
 *
 * @param sessions 会话列表（从 ChatViewModel.allSessions 获取）
 * @param currentSessionId 当前激活的会话 ID
 * @param onCreateSession 创建新会话回调
 * @param onSwitchSession 切换会话回调
 * @param onRenameSession 重命名会话回调
 * @param onDeleteSession 删除会话回调
 * @param getMessageCount 获取会话消息数的 suspend 函数
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionListDrawer(
    sessions: List<SessionEntity>,
    currentSessionId: String?,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    getMessageCount: suspend (String) -> Int,
    modifier: Modifier = Modifier
) {
    // 重命名对话框状态
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // 删除确认对话框状态
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetSession by remember { mutableStateOf<SessionEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(SciFiBackground)
    ) {
        // === 头部 ===
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "会话列表",
                color = SciFiOnBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            // 新建会话按钮
            IconButton(onClick = onCreateSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建会话",
                    tint = SciFiPrimary
                )
            }
        }

        HorizontalDivider(color = SciFiOutline)

        // === 会话列表 ===
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = sessions,
                key = { it.sessionId }
            ) { session ->
                SessionListItem(
                    session = session,
                    isActive = session.sessionId == currentSessionId,
                    onClick = { onSwitchSession(session.sessionId) },
                    onLongClick = { /* handled by combinedClickable inside */ },
                    onRename = {
                        renameTargetSession = session
                        renameInput = session.name ?: ""
                        showRenameDialog = true
                    },
                    onDelete = {
                        deleteTargetSession = session
                        showDeleteDialog = true
                    },
                    getMessageCount = getMessageCount
                )
            }

            // 空状态
            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📭", fontSize = 32.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无会话",
                                color = SciFiOnSurfaceVariant,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "点击上方 + 创建新会话",
                                color = SciFiOnSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // === 重命名对话框 ===
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名会话", color = SciFiOnBackground) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    placeholder = { Text("输入会话名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SciFiPrimary,
                        unfocusedBorderColor = SciFiOutline
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameInput.isNotBlank() && renameTargetSession != null) {
                            onRenameSession(renameTargetSession!!.sessionId, renameInput.trim())
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("确认", color = SciFiPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消", color = SciFiOnSurfaceVariant)
                }
            },
            containerColor = SciFiSurfaceVariant,
            titleContentColor = SciFiOnBackground,
            textContentColor = SciFiOnSurfaceVariant
        )
    }

    // === 删除确认对话框 ===
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除会话", color = SciFiError) },
            text = {
                Text(
                    "确定要删除「${deleteTargetSession?.name ?: "新对话"}」吗？\n此操作不可撤销。",
                    color = SciFiOnSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTargetSession?.let { onDeleteSession(it.sessionId) }
                        showDeleteDialog = false
                    }
                ) {
                    Text("删除", color = SciFiError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = SciFiOnSurfaceVariant)
                }
            },
            containerColor = SciFiSurfaceVariant,
            titleContentColor = SciFiError,
            textContentColor = SciFiOnSurfaceVariant
        )
    }
}
```

### 5.3 新增组件：SessionListItem

```kotlin
package ai.openclaw.android.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.ui.theme.*

/**
 * 单个会话列表项
 *
 * 使用 Card + Row 布局，包含：
 * - 状态图标 (📌 活跃 / 🗂️ 归档)
 * - 会话名称（无名称时显示"新对话"）
 * - 相对时间 ("刚刚" / "3小时前")
 * - 消息数
 * - 当前会话高亮指示器
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionListItem(
    session: SessionEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    getMessageCount: suspend (String) -> Int,
    modifier: Modifier = Modifier
) {
    // 异步获取消息数
    var messageCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(session.sessionId) {
        messageCount = getMessageCount(session.sessionId)
    }

    // 显示名称：无名称时显示"新对话"
    val displayName = if (session.name.isNullOrBlank()) "新对话" else session.name

    // 相对时间
    val timeAgo = formatRelativeTime(session.lastActiveAt)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // 长按弹出操作菜单 — 这里用简化方案，
                    // 实际可以用 DropdownMenu 或 BottomSheet
                    // 为了保持与现有风格一致，使用 SessionActionMenu
                    // 这里简单处理：通过 onRename / onDelete 回调
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                SciFiSurfaceVariant.copy(alpha = 0.8f)
            else
                Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box {
            // 当前会话高亮指示器（左侧竖条）
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(SciFiPrimary, SciFiSecondary)
                            )
                        )
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态图标
                Icon(
                    imageVector = when (session.status) {
                        SessionStatus.ACTIVE -> Icons.Default.Chat
                        SessionStatus.COMPRESSED -> Icons.Default.History
                        SessionStatus.ARCHIVED -> Icons.Default.Folder
                    },
                    contentDescription = null,
                    tint = if (isActive) SciFiPrimary else SciFiOnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 会话信息
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = if (isActive) SciFiOnBackground else SciFiOnSurfaceVariant,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeAgo,
                            color = SciFiOnSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                        if (messageCount != null) {
                            Text(
                                text = " · ",
                                color = SciFiOnSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${messageCount} 条",
                                color = SciFiOnSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // 当前会话标记
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "当前会话",
                        tint = SciFiPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
```

### 5.4 长按操作菜单（DropdownMenu）

```kotlin
/**
 * 会话项长按操作菜单 — 弹出式 DropdownMenu
 */
@Composable
fun SessionActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = SciFiSurfaceVariant
    ) {
        DropdownMenuItem(
            text = { Text("重命名", color = SciFiOnSurfaceVariant) },
            leadingIcon = {
                Icon(Icons.Default.Edit, null, tint = SciFiPrimary, modifier = Modifier.size(18.dp))
            },
            onClick = {
                onRename()
                onDismiss()
            }
        )
        HorizontalDivider(color = SciFiOutline)
        DropdownMenuItem(
            text = { Text("删除", color = SciFiError) },
            leadingIcon = {
                Icon(Icons.Default.Delete, null, tint = SciFiError, modifier = Modifier.size(18.dp))
            },
            onClick = {
                onDelete()
                onDismiss()
            }
        )
    }
}
```

### 5.5 MainActivity 集成 — 修改后的 MainScreen

```kotlin
// 在 MainScreen 中增加 Session 管理

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    chatViewModel: ChatViewModel,
    gatewayContractProvider: () -> GatewayContract?,
    initialTab: Int = 0
) {
    val scope = rememberCoroutineScope()

    // === 新增：Session 管理状态 ===
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sessions by chatViewModel.allSessions.collectAsStateWithLifecycle()
    val currentSessionId by chatViewModel.currentSessionId.collectAsStateWithLifecycle()
    var sessionActionTarget by remember { mutableStateOf<SessionEntity?>(null) }

    val scope = rememberCoroutineScope()

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
        gesturesEnabled = true // 支持手势滑动打开
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // 显示当前会话名称（如果有）
                        val currentSession = sessions.find { it.sessionId == currentSessionId }
                        val sessionName = currentSession?.name?.takeIf { it.isNotBlank() } ?: "新对话"
                        Column {
                            Text(
                                text = sessionName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "OpenClaw · qwen-plus",
                                fontSize = 11.sp,
                                color = SciFiOutlineVariant
                            )
                        }
                    },
                    navigationIcon = {
                        // 新增：打开侧边栏按钮
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "打开会话列表",
                                tint = SciFiOnBackground
                            )
                        }
                    },
                    actions = {
                        // 原有 actions 保留
                        StatusIndicator(state = ConnectionState.ONLINE)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SciFiBackground.copy(alpha = 0.95f),
                        titleContentColor = SciFiOnBackground
                    )
                )
            },
            bottomBar = { /* 原有 NavigationBar */ }
        ) { padding ->
            ChatScreen(
                sendMessage = sendMessage,
                messages = messages.toList(),
                isLoading = isLoading,
                modifier = Modifier.padding(padding),
                // ... 其他原有参数不变
            )
        }
    }
}
```

---

## 6. 切换会话时的 ViewModel 状态重建

### 6.1 问题分析

ChatViewModel 内部持有：
- `agentSession: AgentSession?` — 管理当前对话的消息历史
- `sessionManager: HybridSessionManager?` — 管理持久化会话
- `_messages: MutableStateFlow<List<ChatMessage>>` — UI 显示的消息列表

当切换会话时，需要：
1. **清空 UI 消息列表** — `_messages.value = emptyList()`
2. **切换 HybridSessionManager 的 currentSession** — `sessionManager.switchToSession(newId)`
3. **[可选] 加载历史消息** — 从 MessageDao 读取旧消息填充 UI

### 6.2 切换流程

```
用户点击切换会话 (sessionId: "abc123")
    │
    ├─ 1. ChatViewModel.clearHistory()
    │     → _messages.value = emptyList()
    │     → agentSession?.clearHistory()  (清空内存中的对话历史)
    │
    ├─ 2. sessionManager.switchToSession("abc123")
    │     → currentSession = SessionDao.getSessionById("abc123")
    │     → 返回 Result<SessionEntity>
    │
    ├─ 3. _currentSessionId.value = "abc123"
    │     → UI 的 collectAsState 自动触发重绘
    │
    └─ 4. [可选] loadHistoryMessages("abc123")
          → MessageDao.getMessagesBySessionIdWithLimit(sessionId, limit=50, offset=0)
          → 将 MessageEntity 转为 ChatMessage
          → _messages.value = convertedList
```

### 6.3 ChatViewModel 实现

```kotlin
// 在 ChatViewModel 中增加：

/** 切换到新会话（核心方法）*/
suspend fun switchToSession(sessionId: String) {
    try {
        // 1. 清空当前状态
        clearHistory()

        // 2. 切换底层会话
        sessionManager?.switchToSession(sessionId)?.getOrNull()?.let { session ->
            _currentSessionId.value = session.sessionId

            // 3. 加载历史消息（最近 50 条）
            loadHistoryMessages(sessionId)

            Log.d(TAG, "Switched to session: ${session.name ?: "新对话"}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to switch session", e)
    }
}

/** 从数据库加载历史消息到 UI */
private suspend fun loadHistoryMessages(sessionId: String) {
    val entities = database.messageDao()
        .getMessagesBySessionIdWithLimit(sessionId, limit = 50, offset = 0)

    val chatMessages = entities.map { entity ->
        ChatMessage(
            id = entity.id.toString(),
            role = entity.role.name.lowercase(),
            content = entity.content,
            timestamp = entity.timestamp
        )
    }

    _messages.value = chatMessages
    Log.d(TAG, "Loaded ${chatMessages.size} history messages for session $sessionId")
}
```

---

## 7. 实现优先级

| 优先级 | 任务 | 预估工作量 |
|--------|------|-----------|
| P0 | 扩展 ChatViewModel 增加 session 管理方法 | 1h |
| P0 | 创建 SessionListDrawer 组件 | 1.5h |
| P0 | 创建 SessionListItem 组件 | 0.5h |
| P0 | 修改 MainScreen 集成 ModalNavigationDrawer | 1h |
| P0 | 修改 ChatScreen TopBar 添加菜单按钮 | 0.5h |
| P1 | 添加 formatRelativeTime 工具函数 | 0.25h |
| P1 | 切换会话时加载历史消息 | 0.5h |
| P1 | 重命名对话框 | 0.5h |
| P1 | 删除确认对话框 | 0.5h |
| P2 | 手势滑动打开侧边栏 | 0.25h |
| P2 | 长按操作菜单 DropdownMenu | 0.5h |
| P2 | 空状态占位 UI | 0.25h |

**总计**: ~7 小时（含测试）

---

## 8. 关键设计决策

### 8.1 为什么不新建 ViewModel？

- ChatViewModel 已经持有 `sessionManager: HybridSessionManager`
- HybridSessionManager 已有 `createNamedSession()`, `switchToSession()` 等方法
- 新建 ViewModel 会导致状态分裂和生命周期管理复杂化
- 方案：在 ChatViewModel 中暴露 session 管理的 StateFlow + 方法

### 8.2 为什么用 ModalNavigationDrawer 而不是 NavigationDrawer？

- ModalNavigationDrawer 支持浮层模式，不挤压内容区域
- ChatScreen 的布局不需要随侧边栏改变
- Material3 推荐的标准侧边栏方案
- 支持手势滑动打开/关闭

### 8.3 消息数获取策略

- MessageDao 已有 `getMessageCountBySessionId(sessionId): Int` (suspend)
- 在 SessionListItem 中用 LaunchedEffect 异步获取
- 不放在 SessionEntity 中是因为需要实时计数，避免缓存不一致

### 8.4 切换会话时为何要 clearHistory？

- AgentSession 内部持有对话历史列表
- 切换会话后，旧的对话历史属于旧会话
- 必须清空后才能开始新会话的对话
- 历史消息通过 loadHistoryMessages 从 DB 加载回来
