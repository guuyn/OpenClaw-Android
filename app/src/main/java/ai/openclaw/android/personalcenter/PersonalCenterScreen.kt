@file:OptIn(ExperimentalFoundationApi::class)

package ai.openclaw.android.personalcenter

import android.content.Intent
import android.provider.Settings
import android.app.PendingIntent
import android.content.IntentSender
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.android.personalcenter.models.CenterItem
import ai.openclaw.android.personalcenter.sources.ItemSource
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOnSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOutlineVariant
import java.text.SimpleDateFormat
import java.util.*

/**
 * 个人中心页面
 * 按重要程度排序的统一信息流
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCenterScreen(
    viewModel: PersonalCenterViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val calPerm by viewModel.calendarPermissionGranted.collectAsState()
    val smsPerm by viewModel.smsPermissionGranted.collectAsState()

    // 统计
    val notifCount = items.count { it.source == ItemSource.NOTIFICATION }
    val calCount = items.count { it.source == ItemSource.CALENDAR }
    val smsCount = items.count { it.source == ItemSource.SMS }
    val unreadCount = items.count { !it.isRead }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部标题栏
        PersonalCenterHeader(
            unreadCount = unreadCount,
            onRefresh = { viewModel.refresh() },
            onMarkAllRead = { viewModel.markAllAsRead() }
        )

        // 统计卡片
        StatsCards(
            notifCount = notifCount,
            calCount = calCount,
            smsCount = smsCount,
            calPermGranted = calPerm,
            smsPermGranted = smsPerm,
            onGrantCalendarPerm = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            },
            onGrantSmsPerm = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        )

        // 列表 / 加载 / 空状态
        if (isLoading && items.isEmpty()) {
            LoadingState()
        } else if (items.isEmpty()) {
            EmptyState()
        } else {
            CenterItemList(
                items = items,
                onMarkRead = { viewModel.markAsRead(it) },
                onRemove = { viewModel.removeItem(it) },
                onOpen = { item ->
                    item.openIntent?.let {
                        try {
                            it.send()
                        } catch (e: PendingIntent.CanceledException) {
                            Log.w("PersonalCenter", "PendingIntent canceled: ${e.message}")
                        } catch (e: Exception) {
                            Log.e("PersonalCenter", "Failed to open: ${e.message}")
                        }
                    }
                }
            )
        }
    }
}

/**
 * 顶部标题栏
 */
@Composable
fun PersonalCenterHeader(
    unreadCount: Int,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (unreadCount > 0)
                MaterialTheme.colorScheme.primaryContainer
            else
                SciFiSurfaceVariant
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
                    text = "🦞 个人中心",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (unreadCount > 0) {
                    Text(
                        text = "$unreadCount 条未读",
                        style = MaterialTheme.typography.bodySmall,
                        color = SciFiPrimary
                    )
                }
            }
            Row {
                if (unreadCount > 0) {
                    TextButton(onClick = onMarkAllRead) {
                        Text("全部已读")
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
            }
        }
    }
}

/**
 * 统计卡片行
 */
@Composable
fun StatsCards(
    notifCount: Int,
    calCount: Int,
    smsCount: Int,
    calPermGranted: Boolean,
    smsPermGranted: Boolean,
    onGrantCalendarPerm: () -> Unit,
    onGrantSmsPerm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MiniStatCard(
            icon = Icons.Default.Notifications,
            label = "通知",
            count = notifCount,
            tint = SciFiPrimary,
            modifier = Modifier.weight(1f)
        )
        MiniStatCard(
            icon = Icons.Default.Event,
            label = "日程",
            count = calCount,
            tint = if (calPermGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
            modifier = Modifier.weight(1f),
            onDenied = if (!calPermGranted) onGrantCalendarPerm else null
        )
        MiniStatCard(
            icon = Icons.Default.Message,
            label = "短信",
            count = smsCount,
            tint = if (smsPermGranted) Color(0xFF2196F3) else Color(0xFFFF9800),
            modifier = Modifier.weight(1f),
            onDenied = if (!smsPermGranted) onGrantSmsPerm else null
        )
    }
}

@Composable
fun MiniStatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    onDenied: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .then(
                    if (onDenied != null) Modifier.combinedClickable(
                        onClick = onDenied,
                        onLongClick = {}
                    ) else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$count",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = tint
            )
            Text(
                text = label + if (onDenied != null) " (未授权)" else "",
                fontSize = 11.sp,
                color = SciFiOnSurfaceVariant.copy(alpha = if (onDenied != null) 0.8f else 0.6f)
            )
        }
    }
}

/**
 * 信息列表
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CenterItemList(
    items: List<CenterItem>,
    onMarkRead: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (CenterItem) -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            CenterItemCard(
                item = item,
                timeText = if (item.timestamp > 0) {
                    if (System.currentTimeMillis() - item.timestamp < 86400000) {
                        try {
                            timeFormat.format(Date(item.timestamp))
                        } catch (_: Exception) { "--:--" }
                    } else {
                        try {
                            dayFormat.format(Date(item.timestamp))
                        } catch (_: Exception) { "--/--" }
                    }
                } else {
                    "未知"
                },
                onMarkRead = { onMarkRead(item.id) },
                onRemove = { onRemove(item.id) },
                onOpen = { onOpen(item) }
            )
        }
    }
}

/**
 * 单条信息卡片
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CenterItemCard(
    item: CenterItem,
    timeText: String,
    onMarkRead: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val importanceColor = when {
        item.importance >= 0.7f -> Color(0xFFFF5252)  // 红 - 高重要
        item.importance >= 0.4f -> Color(0xFFFFB74D)  // 橙 - 中重要
        else -> Color(0xFF81C784)                      // 绿 - 低重要
    }

    val sourceIcon = when (item.source) {
        ItemSource.NOTIFICATION -> "📱"
        ItemSource.CALENDAR -> "📅"
        ItemSource.SMS -> "💬"
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!item.isRead) Modifier.drawBehind {
                        drawLine(
                            color = importanceColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 4.dp.toPx()
                        )
                    } else Modifier
                )
                .combinedClickable(
                    onClick = {
                        if (!item.isRead) onMarkRead()
                        onOpen()
                    },
                    onLongClick = { showMenu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 顶部行：来源 + 时间 + 重要度
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = sourceIcon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.sourceApp,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.isRead) SciFiOutlineVariant else SciFiOnSurfaceVariant
                            )
                            if (item.mergedCount > 1) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "🔗${item.mergedCount}",
                                    fontSize = 10.sp,
                                    color = SciFiPrimary
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!item.isRead) {
                                Icon(
                                    imageVector = Icons.Default.FiberManualRecord,
                                    contentDescription = "未读",
                                    modifier = Modifier.size(8.dp),
                                    tint = importanceColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = SciFiOutlineVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${(item.importance * 100).toInt()}",
                                fontSize = 10.sp,
                                color = importanceColor.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // 标题
                    if (item.title.isNotBlank()) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (item.isRead) SciFiOutlineVariant else Color.Unspecified,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // 正文
                    if (item.body.isNotBlank()) {
                        Text(
                            text = item.body,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = if (item.isRead) SciFiOutlineVariant else SciFiOnSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // 长按菜单
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!item.isRead) {
                        DropdownMenuItem(
                            text = { Text("标记已读") },
                            leadingIcon = { Icon(Icons.Default.Done, null) },
                            onClick = { onMarkRead(); showMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { onRemove(); showMenu = false }
                    )
                }
            }
        }
    }
}

/**
 * 加载状态
 */
@Composable
fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = SciFiPrimary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在加载...", color = SciFiOnSurfaceVariant)
        }
    }
}

/**
 * 空状态
 */
@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = "无内容",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无内容",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "通知、日程和短信会出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
