package ai.openclaw.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== 卡片容器 ====================

private val CardShape = RoundedCornerShape(16.dp)

@Composable
private fun CardContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

// ==================== 卡片头部 ====================

@Composable
private fun CardHeader(
    icon: String,
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = icon,
                    fontSize = 18.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ==================== 通用操作按钮栏 ====================

@Composable
fun CardActionButtons(
    actions: List<CardAction>,
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    val primaryActions = actions.filter { it.style == ButtonStyle.Primary }
    val secondaryActions = actions.filter { it.style == ButtonStyle.Secondary }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    Spacer(modifier = Modifier.height(8.dp))

    Column(modifier = modifier) {
        // Primary actions: full-width, filled buttons
        primaryActions.forEach { action ->
            Button(
                onClick = { onActionClick(action) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (primaryActions.indexOf(action) < primaryActions.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (primaryActions.isNotEmpty() && secondaryActions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Secondary actions: text buttons, side-by-side
        if (secondaryActions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                secondaryActions.forEach { action ->
                    TextButton(
                        onClick = { onActionClick(action) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ==================== 1. WeatherCard ====================

@Composable
fun WeatherCard(
    data: WeatherCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        CardHeader(icon = "🌤️", title = data.title)

        Spacer(modifier = Modifier.height(16.dp))

        // 主天气信息
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 天气图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = getWeatherEmoji(data.condition),
                        fontSize = 32.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 温度和城市
            Column {
                Text(
                    text = data.temperature,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${data.city} · ${data.condition}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 额外信息
        val extraInfo = listOfNotNull(
            data.feelsLike?.let { "体感 $it" },
            data.humidity?.let { "湿度 $it" },
            data.wind?.let { "风速 $it" }
        )
        if (extraInfo.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                extraInfo.forEach { info ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // 天气提醒
        data.alert?.let { alert ->
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("⚠️", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = alert,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // 未来预报
        if (data.forecast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "未来预报",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                data.forecast.take(5).forEach { day ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(48.dp)
                    ) {
                        Text(
                            text = day.day,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (day.icon.isNotEmpty()) getConditionEmoji(day.condition) else "🌡️",
                            fontSize = 20.sp
                        )
                        Text(
                            text = "${day.low}°/${day.high}°",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 2. SearchResultCard ====================

@Composable
fun SearchResultCard(
    data: SearchResultCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        CardHeader(icon = "🔍", title = data.title)

        Spacer(modifier = Modifier.height(8.dp))

        // 查询词和统计
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = data.query,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.total?.let {
                        Text(
                            text = "$it 条结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    data.time?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 搜索结果列表
        if (data.items.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            data.items.take(5).forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (item.snippet.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 28.dp)
                        )
                    }
                    if (item.source.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 28.dp)
                        )
                    }
                }
            }
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 3. TranslationCard ====================

@Composable
fun TranslationCard(
    data: TranslationCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "翻译",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "${data.sourceLang} → ${data.targetLang}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 原文
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = data.sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                data.pronunciation?.let { pron ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "/ $pron /",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }

        // 箭头
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 译文
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Text(
                text = data.targetText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 4. ReminderCard ====================

@Composable
fun ReminderCard(
    data: ReminderCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        when (data.mode) {
            ReminderMode.List -> {
                // 列表模式
                CardHeader(icon = "📋", title = data.title)
                data.count?.let { count ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 $count 个提醒",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (data.items.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    data.items.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 状态图标
                            val statusIcon = when (item.status) {
                                "completed" -> "✅"
                                "cancelled" -> "❌"
                                else -> "⏳"
                            }
                            Text(statusIcon, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 状态标签
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (item.status) {
                                    "completed" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                                    "cancelled" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                }
                            ) {
                                Text(
                                    text = item.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            ReminderMode.Confirm -> {
                // 确认模式
                CardHeader(icon = "⏰", title = data.title)

                if (data.items.isNotEmpty()) {
                    val item = data.items.first()
                    Spacer(modifier = Modifier.height(16.dp))

                    // 提醒详情
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item.time,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 5. CalendarCard ====================

@Composable
fun CalendarCard(
    data: CalendarCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        CardHeader(icon = "📅", title = data.title)

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = data.date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (data.items.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            data.items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 颜色标记条
                    val color = runCatching { Color(android.graphics.Color.parseColor(item.color)) }
                        .getOrNull() ?: MaterialTheme.colorScheme.primary
                    Surface(
                        color = color,
                        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
                        modifier = Modifier
                            .width(4.dp)
                            .height(40.dp)
                    ) {}

                    Spacer(modifier = Modifier.width(12.dp))

                    // 时间
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(90.dp)
                    )

                    // 标题和地点
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.location.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = item.location,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "今天没有日程安排",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 6. LocationCard ====================

@Composable
fun LocationCard(
    data: LocationCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        CardHeader(icon = "📍", title = data.title)

        Spacer(modifier = Modifier.height(12.dp))

        // 地址
        Text(
            text = data.address,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 经纬度
        if (data.latitude != null && data.longitude != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📐 ${data.latitude}, ${data.longitude}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 附近地点
        if (data.nearby.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "附近",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            data.nearby.take(3).forEach { place ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("📌", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = place.distance,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== 7. ActionConfirmCard ====================

@Composable
fun ActionConfirmCard(
    data: ActionConfirmCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        // 头部
        CardHeader(icon = data.icon, title = data.title)

        Spacer(modifier = Modifier.height(12.dp))

        // 描述
        Text(
            text = data.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 详细信息
        if (data.details.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    data.details.forEach { (key, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(80.dp)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 风险等级
        Spacer(modifier = Modifier.height(12.dp))
        val (riskColor, riskLabel, riskBgColor) = when (data.riskLevel) {
            RiskLevel.Low -> Triple(
                MaterialTheme.colorScheme.primary,
                "低风险",
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
            RiskLevel.Medium -> Triple(
                Color(0xFFE67E22),
                "中等风险",
                Color(0xFFFFF3E0)
            )
            RiskLevel.High -> Triple(
                MaterialTheme.colorScheme.error,
                "⚠️ 高风险",
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = riskBgColor
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = riskLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = riskColor
                )
                if (data.riskLevel == RiskLevel.High && data.warning != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = data.warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 操作按钮
        CardActionButtons(actions, onActionClick)
    }
}

// ==================== InfoCard (fallback) ====================

@Composable
fun InfoCard(
    data: InfoCardData,
    actions: List<CardAction> = emptyList(),
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    CardContainer(modifier = modifier) {
        CardHeader(icon = data.icon, title = data.title ?: "信息")

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = data.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        data.summary?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        CardActionButtons(actions, onActionClick)
    }
}

// ==================== A2UICardRouter — 完整实现 ====================

@Composable
fun A2UICardRouter(
    card: A2UICard,
    onActionClick: (CardAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (card.type) {
        "weather" -> WeatherCard(card.asWeatherCard()!!, card.actions, onActionClick, modifier)
        "search_result" -> SearchResultCard(card.asSearchResultCard()!!, card.actions, onActionClick, modifier)
        "translation" -> TranslationCard(card.asTranslationCard()!!, card.actions, onActionClick, modifier)
        "reminder" -> ReminderCard(card.asReminderCard()!!, card.actions, onActionClick, modifier)
        "calendar" -> CalendarCard(card.asCalendarCard()!!, card.actions, onActionClick, modifier)
        "location" -> LocationCard(card.asLocationCard()!!, card.actions, onActionClick, modifier)
        "action_confirm" -> ActionConfirmCard(card.asActionConfirmCard()!!, card.actions, onActionClick, modifier)
        else -> InfoCard(
            card.asInfoCard() ?: InfoCardData(
                title = "未知卡片",
                icon = "info",
                content = "类型: ${card.type}",
                summary = null
            ),
            card.actions,
            onActionClick,
            modifier
        )
    }
}

// ==================== 旧版路由（向后兼容） ====================

@Composable
fun A2UICardRouterLegacy(
    type: String,
    data: Map<String, String>,
    modifier: Modifier = Modifier
) {
    when (type) {
        "weather" -> WeatherCard(data, modifier)
        "location" -> LocationCard(data, modifier)
        "search" -> SearchCard(data, modifier)
        "translation" -> TranslationCard(data, modifier)
        "reminder" -> ReminderCard(data, modifier)
        else -> GenericA2UICard(type, data, modifier)
    }
}

// ==================== Emoji 工具函数 ====================

@Composable
private fun getWeatherEmoji(condition: String): String = when {
    condition.contains("晴") -> "☀️"
    condition.contains("云") || condition.contains("阴") -> "☁️"
    condition.contains("雨") -> "🌧️"
    condition.contains("雪") -> "❄️"
    condition.contains("雷") -> "⛈️"
    condition.contains("雾") || condition.contains("霾") -> "🌫️"
    else -> "🌤️"
}

private fun getConditionEmoji(condition: String): String = when {
    condition.contains("晴") -> "☀️"
    condition.contains("云") || condition.contains("阴") -> "☁️"
    condition.contains("雨") -> "🌧️"
    condition.contains("雪") -> "❄️"
    else -> "🌤️"
}

// ==================== 旧版卡片实现（向后兼容，默认不再使用） ====================

@Composable
fun WeatherCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = getWeatherEmoji(data["condition"] ?: ""), fontSize = 28.sp)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = data["temperature"] ?: "--°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = data["condition"] ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = data["location"] ?: data["city"] ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            val extraInfo = buildList {
                data["humidity"]?.let { add("湿度 $it") }
                data["wind"]?.let { add("风速 $it") }
            }
            if (extraInfo.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    extraInfo.forEach { info ->
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                            Text(text = info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.LocationOn, contentDescription = "位置", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = data["name"] ?: data["address"] ?: "位置信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            data.filterKeys { it !in setOf("name", "address", "type") }.forEach { (key, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(text = "$key: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
fun SearchCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = data["query"] ?: data["keyword"] ?: "搜索结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            val results = buildSearchResults(data)
            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                results.forEachIndexed { index, (title, snippet) ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f))
                    }
                    Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    if (snippet.isNotEmpty()) {
                        Text(text = snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f), maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun buildSearchResults(data: Map<String, String>): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    for (i in 1..5) {
        val title = data["result${i}"] ?: data["title${i}"] ?: continue
        val snippet = data["snippet${i}"] ?: data["desc${i}"] ?: ""
        results.add(title to snippet)
    }
    return results
}

@Composable
fun TranslationCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Translate, contentDescription = "翻译", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                val langPair = buildString {
                    append(data["source_lang"] ?: data["from"] ?: "")
                    append(" → ")
                    append(data["target_lang"] ?: data["to"] ?: "")
                }
                if (langPair.isNotBlank()) {
                    Text(text = langPair, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val originalText = data["original"] ?: data["source"] ?: data["text"] ?: ""
            if (originalText.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)) {
                    Text(text = originalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), modifier = Modifier.padding(12.dp))
                }
            }
            val translatedText = data["translated"] ?: data["result"] ?: data["translation"] ?: ""
            if (translatedText.isNotEmpty()) {
                if (originalText.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                Text(text = translatedText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun ReminderCard(data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "⏰", fontSize = 24.sp)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = data["title"] ?: data["text"] ?: "提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.height(4.dp))
                val time = data["time"] ?: data["date"] ?: data["datetime"] ?: ""
                if (time.isNotEmpty()) {
                    Text(text = time, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                val note = data["note"] ?: data["description"] ?: ""
                if (note.isNotEmpty()) {
                    Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
fun GenericA2UICard(type: String, data: Map<String, String>, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = type, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            data.forEach { (key, value) ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(text = "$key: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
