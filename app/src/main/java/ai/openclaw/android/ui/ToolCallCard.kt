package ai.openclaw.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 工具调用状态 */
enum class ToolCallStatus {
    RUNNING, SUCCESS, FAILED
}

/** 工具调用数据 */
data class ToolCallInfo(
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val status: ToolCallStatus = ToolCallStatus.RUNNING,
    val durationMs: Long = 0,
    val error: String? = null
)

/**
 * 工具调用可视化卡片
 * 显示工具名称、参数、执行状态和耗时
 */
@Composable
fun ToolCallCard(
    info: ToolCallInfo,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 头部：工具名称 + 状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 状态图标
                when (info.status) {
                    ToolCallStatus.RUNNING -> {
                        val infiniteTransition = rememberInfiniteTransition(label = "toolSpin")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "toolRotation"
                        )
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "执行中",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(rotation)
                        )
                    }
                    ToolCallStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "成功",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    ToolCallStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "失败",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 工具名称
                Text(
                    text = info.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )

                Spacer(modifier = Modifier.weight(1f))

                // 耗时
                if (info.status != ToolCallStatus.RUNNING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatDuration(info.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                // 展开/折叠按钮（有参数时显示）
                if (info.params.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { expanded = !expanded }
                    )
                }
            }

            // 状态文本
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusText = when (info.status) {
                    ToolCallStatus.RUNNING -> "执行中..."
                    ToolCallStatus.SUCCESS -> "成功"
                    ToolCallStatus.FAILED -> "失败"
                }
                val statusColor = when (info.status) {
                    ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                    ToolCallStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )

                if (info.error != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = info.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 参数列表（可折叠）
            if (info.params.isNotEmpty()) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        info.params.forEach { (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$key=",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
        else -> "${ms / 60_000}m ${"%.0f".format((ms % 60_000) / 1000.0)}s"
    }
}
