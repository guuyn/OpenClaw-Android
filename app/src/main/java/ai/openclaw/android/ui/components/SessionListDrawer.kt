package ai.openclaw.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.ui.theme.SciFiBackground
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiOnBackground
import ai.openclaw.android.ui.theme.SciFiOnSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOutline
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant

/**
 * 会话列表侧边栏内容
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
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = sessions,
                key = { it.sessionId }
            ) { session ->
                SessionListItem(
                    session = session,
                    isActive = session.sessionId == currentSessionId,
                    onClick = { onSwitchSession(session.sessionId) },
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
                    "确定要删除「${deleteTargetSession?.displayName ?: "新对话"}」吗？\n此操作不可撤销。",
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

/** Extension property for convenient display name */
val SessionEntity.displayName: String
    get() = if (name.isNullOrBlank()) "新对话" else name

/**
 * 单个会话列表项
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
    var messageCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(session.sessionId) {
        messageCount = getMessageCount(session.sessionId)
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                SciFiSurfaceVariant.copy(alpha = 0.8f)
            else
                Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box {
            // 当前会话高亮指示器
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
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true }
                    )
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
                        text = session.displayName,
                        color = if (isActive) SciFiOnBackground else SciFiOnSurfaceVariant,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatRelativeTime(session.lastActiveAt),
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

    // 长按操作菜单
    SessionActionMenu(
        expanded = menuExpanded,
        onDismiss = { menuExpanded = false },
        onRename = onRename,
        onDelete = onDelete
    )
}

/**
 * 会话项长按操作菜单
 */
@Composable
fun SessionActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
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
}
