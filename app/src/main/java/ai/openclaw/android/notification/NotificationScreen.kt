package ai.openclaw.android.notification

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * 通知管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    notifications: List<SmartNotification>,
    onMarkAsRead: (String) -> Unit,
    onMarkAllAsRead: () -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val classifier = remember { NotificationClassifier(context) }
    
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部状态栏
        if (hasPermission) {
            NotificationStatusBar(
                notifications = notifications,
                onMarkAllAsRead = onMarkAllAsRead,
                onClearAll = onClearAll
            )
            
            // 通知列表
            if (notifications.isEmpty()) {
                EmptyNotificationState()
            } else {
                NotificationList(
                    notifications = notifications,
                    classifier = classifier,
                    onMarkAsRead = onMarkAsRead,
                    onDelete = onDelete
                )
            }
        } else {
            // 权限请求提示
            PermissionRequestCard(onRequestPermission)
        }
    }
}

/**
 * 顶部状态栏
 */
@Composable
fun NotificationStatusBar(
    notifications: List<SmartNotification>,
    onMarkAllAsRead: () -> Unit,
    onClearAll: () -> Unit
) {
    val pendingCount = notifications.count { !it.isRead }
    val urgentCount = notifications.count { it.category == NotificationCategory.URGENT && !it.isRead }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (urgentCount > 0) 
                MaterialTheme.colorScheme.errorContainer 
            else 
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (pendingCount > 0) "你有 $pendingCount 条未读通知" else "通知中心",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (urgentCount > 0) {
                    Text(
                        text = "包含 $urgentCount 条紧急通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Row {
                if (pendingCount > 0) {
                    TextButton(onClick = onMarkAllAsRead) {
                        Text("全部已读")
                    }
                }
                if (notifications.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("清空")
                    }
                }
            }
        }
    }
}

/**
 * 通知列表
 */
@Composable
fun NotificationList(
    notifications: List<SmartNotification>,
    classifier: NotificationClassifier,
    onMarkAsRead: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(notifications, key = { it.id }) { notification ->
            NotificationItem(
                notification = notification,
                appName = classifier.getAppName(notification.packageName),
                dateFormat = dateFormat,
                dayFormat = dayFormat,
                onMarkAsRead = { onMarkAsRead(notification.id) },
                onDelete = { onDelete(notification.id) }
            )
        }
    }
}

/**
 * 单条通知项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationItem(
    notification: SmartNotification,
    appName: String,
    dateFormat: SimpleDateFormat,
    dayFormat: SimpleDateFormat,
    onMarkAsRead: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    val categoryColor = when (notification.category) {
        NotificationCategory.URGENT -> MaterialTheme.colorScheme.errorContainer
        NotificationCategory.IMPORTANT -> MaterialTheme.colorScheme.tertiaryContainer
        NotificationCategory.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
        NotificationCategory.NOISE -> MaterialTheme.colorScheme.surface
        NotificationCategory.PENDING -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val categoryIcon = when (notification.category) {
        NotificationCategory.URGENT -> "🔴"
        NotificationCategory.IMPORTANT -> "🟡"
        NotificationCategory.NORMAL -> "🟢"
        NotificationCategory.NOISE -> "⚪"
        NotificationCategory.PENDING -> "🔵"
    }
    
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (!notification.isRead) onMarkAsRead() },
                    onLongClick = { showMenu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = categoryColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // 标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = categoryIcon, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!notification.isRead) {
                                Icon(
                                    imageVector = Icons.Default.FiberManualRecord,
                                    contentDescription = "未读",
                                    modifier = Modifier.size(8.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (System.currentTimeMillis() - notification.timestamp < 86400000) {
                                    dateFormat.format(Date(notification.timestamp))
                                } else {
                                    dayFormat.format(Date(notification.timestamp))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // 通知标题
                    if (notification.title.isNotBlank()) {
                        Text(
                            text = notification.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    // 通知内容
                    if (notification.text.isNotBlank()) {
                        Text(
                            text = notification.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                // 长按菜单
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!notification.isRead) {
                        DropdownMenuItem(
                            text = { Text("标记已读") },
                            leadingIcon = { Icon(Icons.Default.Done, "标记已读") },
                            onClick = {
                                onMarkAsRead()
                                showMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, "删除") },
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 空状态
 */
@Composable
fun EmptyNotificationState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = "无通知",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无通知",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "新通知会自动出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 权限请求卡片
 */
@Composable
fun PermissionRequestCard(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "通知权限",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "开启通知管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "需要通知访问权限来智能管理你的通知\n帮你过滤噪音、分类重要信息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Settings, "设置")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("前往设置")
                }
            }
        }
    }
}