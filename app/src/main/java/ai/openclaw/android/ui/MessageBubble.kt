package ai.openclaw.android.ui

import ai.openclaw.android.ChatMessage
import ai.openclaw.android.ui.A2UICardParser
import ai.openclaw.android.ui.MessageSegment
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/**
 * 增强的消息气泡组件
 * 支持渐变背景、AI 头像、复制按钮、时间戳优化
 */

// ==================== 消息状态 ====================

enum class MessageStatus {
    SENDING, SENT, READ
}

// ==================== 消息气泡 ====================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EnhancedMessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    status: MessageStatus = MessageStatus.SENT,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // AI 头像（仅 AI 消息显示）
        if (!isUser) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier
                    .size(28.dp)
                    .padding(end = 0.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "\uD83E\uDD9E",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        // 气泡主体
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Box {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    color = if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = if (isUser) 0.dp else 1.dp,
                    shadowElevation = if (isUser) 2.dp else 0.dp,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .then(
                            if (!isUser) Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 16.dp
                                )
                            ) else Modifier
                        )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 消息内容
                        val segments = remember(message.content) { A2UICardParser.parse(message.content) }
                        val contentColor = if (isUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant

                        for (segment in segments) {
                            when (segment) {
                                is MessageSegment.Text -> {
                                    MarkdownText(
                                        text = segment.text,
                                        color = contentColor,
                                        isUser = isUser
                                    )
                                }
                                is MessageSegment.A2UICard -> {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    A2UICardRouter(card = segment.card)
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }

                        // 底部：时间 + 状态
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(
                                text = formatSmartTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.5f)
                            )

                            // 用户消息状态指示
                            if (isUser) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageStatusIcon(status)
                            }
                        }
                    }
                }

                // 复制按钮（悬浮在气泡右上角）
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(message.content))
                                copied = true
                            }
                    ) {
                        if (copied) {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = "已复制",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            // 自动恢复图标
                            LaunchedEffect(copied) {
                                if (copied) {
                                    kotlinx.coroutines.delay(1500)
                                    copied = false
                                }
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 消息状态图标 ====================

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "发送中",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = "已发送",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "已读",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
            )
        }
    }
}

// ==================== 智能时间格式 ====================

private fun formatSmartTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        // 今天
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        // 昨天
        run {
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            yesterday.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                    yesterday.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
        } -> {
            "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
        }
        // 今年
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) -> {
            SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        // 更早
        else -> {
            SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
