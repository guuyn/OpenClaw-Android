@file:OptIn(ExperimentalFoundationApi::class)

package ai.openclaw.android.personalcenter

import android.app.PendingIntent
import android.content.Intent

import android.provider.Settings
import android.util.Log

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
 * 个人中心页面 — 三层信息架构
 *
 * 第一层：顶部标题栏 + 紧凑统计行
 * 第二层：待办优先区（importance >= 0.7 或 isRead == false）
 * 第三层：分类浏览（四个可展开/折叠的分类卡片）
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
    val callLogPerm by viewModel.callLogPermissionGranted.collectAsState()

    // 统计
    val notifCount = items.count { it.source == ItemSource.NOTIFICATION }
    val calCount = items.count { it.source == ItemSource.CALENDAR }
    val smsCount = items.count { it.source == ItemSource.SMS }
    val callLogCount = items.count { it.source == ItemSource.CALL_LOG }
    val unreadCount = items.count { !it.isRead }

    // 第二层：待办优先区数据
    val priorityItems = remember(items) {
        items.filter { it.importance >= 0.7f || !it.isRead }
            .sortedByDescending { it.importance }
    }

    // 第三层：分类展开状态
    val expandedSections = remember { mutableStateMapOf<ItemSource, Boolean>() }

    // 分类内 items 按时间倒序
    val itemsBySource = remember(items) {
        ItemSource.entries.associateWith { source ->
            items.filter { it.source == source }
                .sortedByDescending { it.timestamp }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 第一层：顶部标题栏 + 统计行 =====
        PersonalCenterHeader(
            unreadCount = unreadCount,
            onRefresh = { viewModel.refresh() },
            onMarkAllRead = { viewModel.markAllAsRead() }
        )

        CompactStatsRow(
            notifCount = notifCount,
            calCount = calCount,
            smsCount = smsCount,
            callLogCount = callLogCount,
            calPermGranted = calPerm,
            smsPermGranted = smsPerm,
            callLogPermGranted = callLogPerm,
            onStatClick = { source ->
                expandedSections[source] = !(expandedSections[source] ?: false)
            },
            onGrantCalendarPerm = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            },
            onGrantSmsPerm = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            },
            onGrantCallLogPerm = {
                viewModel.checkAndRequestCallLogPermission()
            }
        )

        // ===== 列表 / 加载 / 空状态 =====
        if (isLoading && items.isEmpty()) {
            LoadingState()
        } else if (items.isEmpty()) {
            EmptyState()
        } else {
            CenterItemList(
                priorityItems = priorityItems,
                itemsBySource = itemsBySource,
                expandedSections = expandedSections,
                onMarkRead = { itemId -> viewModel.markAsRead(itemId) },
                onRemove = { itemId -> viewModel.removeItem(itemId) },
                onToggleSection = { source -> expandedSections[source] = !(expandedSections[source] ?: false) },
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
 * 顶部标题栏（保持不变）
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
 * 紧凑统计行：通知N | 日程N | 短信N | 通话N
 * 点击可切换对应分类的展开/折叠
 */
@Composable
fun CompactStatsRow(
    notifCount: Int,
    calCount: Int,
    smsCount: Int,
    callLogCount: Int,
    calPermGranted: Boolean,
    smsPermGranted: Boolean,
    callLogPermGranted: Boolean,
    onStatClick: (ItemSource) -> Unit,
    onGrantCalendarPerm: () -> Unit,
    onGrantSmsPerm: () -> Unit,
    onGrantCallLogPerm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactStatItem(
            emoji = "🔔",
            label = "通知",
            count = notifCount,
            tint = SciFiPrimary,
            onClick = { onStatClick(ItemSource.NOTIFICATION) }
        )

        Divider(
            modifier = Modifier.height(16.dp),
            color = SciFiOutlineVariant.copy(alpha = 0.3f)
        )

        CompactStatItem(
            emoji = "📅",
            label = "日程",
            count = calCount,
            tint = if (calPermGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
            onClick = { onStatClick(ItemSource.CALENDAR) },
            denied = !calPermGranted,
            onDeniedClick = onGrantCalendarPerm
        )

        Divider(
            modifier = Modifier.height(16.dp),
            color = SciFiOutlineVariant.copy(alpha = 0.3f)
        )

        CompactStatItem(
            emoji = "💬",
            label = "短信",
            count = smsCount,
            tint = if (smsPermGranted) Color(0xFF2196F3) else Color(0xFFFF9800),
            onClick = { onStatClick(ItemSource.SMS) },
            denied = !smsPermGranted,
            onDeniedClick = onGrantSmsPerm
        )

        Divider(
            modifier = Modifier.height(16.dp),
            color = SciFiOutlineVariant.copy(alpha = 0.3f)
        )

        CompactStatItem(
            emoji = "📞",
            label = "通话",
            count = callLogCount,
            tint = if (callLogPermGranted) Color(0xFF9C27B0) else Color(0xFFFF9800),
            onClick = { onStatClick(ItemSource.CALL_LOG) },
            denied = !callLogPermGranted,
            onDeniedClick = onGrantCallLogPerm
        )
    }
}

@Composable
fun CompactStatItem(
    emoji: String,
    label: String,
    count: Int,
    tint: Color,
    onClick: () -> Unit,
    denied: Boolean = false,
    onDeniedClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .combinedClickable(
                onClick = { if (denied) onDeniedClick?.invoke() else onClick() },
                onLongClick = {}
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = emoji, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "$count",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = tint
        )
        if (denied) {
            Text(
                text = "!",
                fontSize = 10.sp,
                color = Color(0xFFFF9800),
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

/**
 * 统一滚动的 LazyColumn 列表
 * 包含：待办优先区 + 四个分类折叠区
 */
@Composable
fun CenterItemList(
    priorityItems: List<CenterItem>,
    itemsBySource: Map<ItemSource, List<CenterItem>>,
    expandedSections: Map<ItemSource, Boolean>,
    onMarkRead: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (CenterItem) -> Unit,
    onToggleSection: (ItemSource) -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===== 第二层：待办优先区 =====
        if (priorityItems.isNotEmpty()) {
            item {
                PriorityZoneHeader()
            }
            items(priorityItems, key = { it.id }) { item ->
                PriorityItemCard(
                    item = item,
                    timeText = formatTimestamp(item.timestamp, timeFormat, dayFormat),
                    onMarkRead = { onMarkRead(item.id) },
                    onRemove = { onRemove(item.id) },
                    onOpen = { onOpen(item) }
                )
            }
        }

        // ===== 第三层：分类浏览 =====
        val categoryOrder = listOf(
            ItemSource.NOTIFICATION,
            ItemSource.CALENDAR,
            ItemSource.SMS,
            ItemSource.CALL_LOG
        )

        for (source in categoryOrder) {
            val sourceItems = itemsBySource[source] ?: emptyList()
            if (sourceItems.isEmpty()) continue

            item(key = "section-$source") {
                CategorySection(
                    source = source,
                    count = sourceItems.size,
                    isExpanded = expandedSections[source] ?: false,
                    onToggle = { onToggleSection(source) }
                )
            }

            if (expandedSections[source] == true) {
                items(sourceItems, key = { it.id }) { item ->
                    CenterItemCard(
                        item = item,
                        timeText = formatTimestamp(item.timestamp, timeFormat, dayFormat),
                        onMarkRead = { onMarkRead(item.id) },
                        onRemove = { onRemove(item.id) },
                        onOpen = { onOpen(item) }
                    )
                }
            }
        }
    }
}

/**
 * 时间格式化辅助函数
 */
private fun formatTimestamp(
    timestamp: Long,
    timeFormat: SimpleDateFormat,
    dayFormat: SimpleDateFormat
): String {
    if (timestamp <= 0) return "未知"
    return try {
        if (System.currentTimeMillis() - timestamp < 86400000) {
            timeFormat.format(Date(timestamp))
        } else {
            dayFormat.format(Date(timestamp))
        }
    } catch (_: Exception) {
        "--:--"
    }
}

// ============== 第二层：待办优先区 ==============

@Composable
fun PriorityZoneHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🔴 待办优先",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF5252)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Divider(
            modifier = Modifier.weight(1f).height(1.dp),
            color = Color(0xFFFF5252).copy(alpha = 0.3f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PriorityItemCard(
    item: CenterItem,
    timeText: String,
    onMarkRead: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val priorityColor = when {
        item.importance >= 0.7f -> Color(0xFFFF5252)
        !item.isRead -> Color(0xFFFFB74D)
        else -> Color(0xFFFFB74D)
    }

    val sourceIcon = when (item.source) {
        ItemSource.NOTIFICATION -> "📱"
        ItemSource.CALENDAR -> "📅"
        ItemSource.SMS -> "💬"
        ItemSource.CALL_LOG -> "📞"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = priorityColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 4.dp.toPx()
                )
            }
            .combinedClickable(
                onClick = {
                    if (!item.isRead) onMarkRead()
                    onOpen()
                },
                onLongClick = { showMenu = true }
            ),
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Box {
            Column(modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp)) {
                // 顶部行：来源 + 时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = sourceIcon, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.sourceApp,
                            style = MaterialTheme.typography.labelSmall,
                            color = SciFiOnSurfaceVariant
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
                                tint = priorityColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = SciFiOutlineVariant
                        )
                    }
                }

                // 标题（加粗）
                if (item.title.isNotBlank()) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.isRead) SciFiOutlineVariant else Color.Unspecified,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // 正文（最多 2 行）
                if (item.body.isNotBlank()) {
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
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

// ============== 第三层：分类浏览 ==============

@Composable
fun CategorySection(
    source: ItemSource,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val (emoji, label) = when (source) {
        ItemSource.NOTIFICATION -> "🔔" to "通知"
        ItemSource.CALENDAR -> "📅" to "日程"
        ItemSource.SMS -> "💬" to "短信"
        ItemSource.CALL_LOG -> "📞" to "通话"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = {}
            ),
        colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            // 分类标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = emoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = SciFiOnSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$count",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SciFiPrimary
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        tint = SciFiOutlineVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 展开指示线
            if (isExpanded) {
                Divider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = SciFiOutlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

// ============== 单条信息卡片（分类列表中使用） ==============

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
        item.importance >= 0.7f -> Color(0xFFFF5252)
        item.importance >= 0.4f -> Color(0xFFFFB74D)
        else -> Color(0xFF81C784)
    }

    val sourceIcon = when (item.source) {
        ItemSource.NOTIFICATION -> "📱"
        ItemSource.CALENDAR -> "📅"
        ItemSource.SMS -> "💬"
        ItemSource.CALL_LOG -> "📞"
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
            shape = RoundedCornerShape(10.dp)
        ) {
            Box {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 顶部行：来源 + 时间
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
                text = "通知、日程、短信和通话记录会出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
