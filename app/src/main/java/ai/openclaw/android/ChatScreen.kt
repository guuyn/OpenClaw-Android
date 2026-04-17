package ai.openclaw.android

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.openclaw.android.voice.VoiceInteractionManager
import ai.openclaw.android.voice.VoiceState
import ai.openclaw.android.ui.A2UICard
import ai.openclaw.android.ui.A2UICardParser
import ai.openclaw.android.ui.A2UICardRouter
import ai.openclaw.android.ui.CardAction
import ai.openclaw.android.ui.MessageSegment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Thermostat
import ai.openclaw.android.ui.theme.SciFiUserBubbleStart
import ai.openclaw.android.ui.theme.SciFiUserBubbleEnd
import ai.openclaw.android.ui.theme.SciFiAiBubbleBg
import ai.openclaw.android.ui.theme.SciFiAiBubbleBorder
import ai.openclaw.android.ui.theme.SciFiBackground
import ai.openclaw.android.ui.theme.SciFiSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiOnSurfaceVariant
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiOutlineVariant
import ai.openclaw.android.ui.theme.SciFiGlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.*
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.rememberA2UIRenderer

// ==================== A2UI Protocol Bridge ====================

/**
 * Converts a legacy A2UICard (type+data from skills) into an A2UI protocol message
 * that the A2UI component library can render. Returns null if the card data
 * cannot be converted to a valid A2UI message.
 */
private fun tryBuildA2UIMessage(card: A2UICard): String? {
    val surfaceId = "msg_card_${card.type}"
    val data = card.rawData.mapValues { it.value?.toString() ?: "" }
    val children = data.entries.mapIndexed { idx, (key, value) ->
        """{"id":"row_$idx","component":"Row","children":{"array":["label_$idx","value_$idx"]}}""" + "\n" +
        """{"id":"label_$idx","component":"Text","text":"$key: ","variant":"body"}""" + "\n" +
        """{"id":"value_$idx","component":"Text","text":"$value","variant":"body"}"""
    }

    // Build A2UI protocol message
    val componentsJson = buildString {
        append("""{"id":"root","component":"Card","children":{"array":[""")
        children.forEachIndexed { idx, comp ->
            val parts = comp.split("\n").filter { it.isNotBlank() }
            parts.forEach { part ->
                append(part.trimEnd())
                append(",")
            }
        }
        // Remove trailing comma
        if (isNotEmpty() && last() == ',') deleteCharAt(length - 1)
        append("]}}")
    }

    // Additional component entries (label/value Texts)
    val extraComponents = buildString {
        children.forEach { comp ->
            val parts = comp.split("\n").filter { it.isNotBlank() }
            parts.drop(1).forEach { part ->
                append(",")
                append(part.trimEnd())
            }
        }
    }

    return buildString {
        append("{\"version\":\"v0.10\",")
        append("\"createSurface\":{\"surfaceId\":\"$surfaceId\"},")
        append("\"updateComponents\":{\"surfaceId\":\"$surfaceId\",\"components\":[$componentsJson$extraComponents]}}")
    }
}

// ==================== Data Model ====================

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ==================== Card Action Message Mapping ====================

/** Result of mapping a card action to a message action */
sealed class CardActionResult {
    object NoOp : CardActionResult()
    object ResendLast : CardActionResult()
    data class SendMessage(val text: String) : CardActionResult()
}

/**
 * Maps a [CardAction] to the appropriate message action.
 * Pure function — testable without Compose runtime.
 */
fun mapCardActionToMessage(action: CardAction, messages: List<ChatMessage>): CardActionResult {
    return when (action.action) {
        "set_reminder" -> CardActionResult.SendMessage("设置提醒")
        "retry", "resend" -> {
            val hasUserMessage = messages.any { it.role == "user" }
            if (hasUserMessage) CardActionResult.ResendLast else CardActionResult.NoOp
        }
        "cancel" -> CardActionResult.SendMessage("取消")
        "confirm" -> CardActionResult.SendMessage("确认")
        else -> {
            // Fall back to button label text for unknown actions
            if (action.label.isNotBlank()) {
                CardActionResult.SendMessage(action.label)
            } else {
                CardActionResult.NoOp
            }
        }
    }
}

// ==================== Chat Screen ====================

@Composable
fun ChatScreen(
    sendMessage: (String) -> Unit,
    messages: List<ChatMessage>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    voiceSessionHandler: (suspend (String) -> String?)? = null
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    // Voice interaction
    val voiceManager = remember { voiceSessionHandler?.let { VoiceInteractionManager(context) } }
    val voiceState by voiceManager?.sessionState?.collectAsState()
        ?: remember { mutableStateOf(VoiceState.Idle) }
    val voiceTranscript by voiceManager?.transcript?.collectAsState()
        ?: remember { mutableStateOf("") }

    val sendInteraction = remember { MutableInteractionSource() }
    val isSendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (isSendPressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "sendScale"
    )

    // Initialize and cleanup voice manager
    LaunchedEffect(voiceManager) {
        voiceManager?.initialize()
    }
    DisposableEffect(voiceManager) {
        onDispose { voiceManager?.destroy() }
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && voiceManager != null) {
            voiceManager.startSession { transcript ->
                voiceSessionHandler?.invoke(transcript)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Card action callback: maps button clicks to message text
    val onCardAction: (CardAction) -> Unit = { action ->
        val mappedResult = mapCardActionToMessage(action, messages)
        when (mappedResult) {
            is CardActionResult.SendMessage -> {
                if (!isLoading && mappedResult.text.isNotBlank()) {
                    sendMessage(mappedResult.text)
                }
            }
            is CardActionResult.ResendLast -> {
                if (!isLoading) {
                    messages.lastOrNull { it.role == "user" }?.let {
                        sendMessage(it.content)
                    }
                }
            }
            CardActionResult.NoOp -> { /* safety: no message for unhandled actions */ }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(300)) +
                            slideInVertically(animationSpec = tween(300)) { it / 2 },
                    modifier = Modifier.animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                ) {
                    MessageBubble(
                        message = message,
                        dateFormat = dateFormat,
                        onCardAction = onCardAction
                    )
                }
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // Voice state indicator
        AnimatedVisibility(
            visible = voiceState != VoiceState.Idle,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            VoiceStateIndicator(
                voiceState = voiceState,
                transcript = voiceTranscript
            )
        }

        // 输入区
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SciFiBackground,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 轻量级输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 120.dp)
                        .background(SciFiSurfaceVariant, RoundedCornerShape(24.dp))
                        .border(
                            width = 1.dp,
                            color = SciFiOutlineVariant,
                            shape = RoundedCornerShape(24.dp)
                        )
                        .focusRequester(focusRequester)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "输入消息...",
                            color = SciFiOnSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = SciFiOnSurfaceVariant
                        ),
                        maxLines = 4
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // 发送按钮
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    interactionSource = sendInteraction,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "发送",
                        modifier = Modifier.scale(sendScale),
                        tint = if (inputText.isNotBlank() && !isLoading)
                            SciFiPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }

                // Voice button
                if (voiceSessionHandler != null && voiceManager != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    VoiceButton(
                        voiceState = voiceState,
                        onClick = {
                            if (voiceState != VoiceState.Idle) {
                                voiceManager.cancelSession()
                            } else if (voiceManager.hasRecordAudioPermission()) {
                                voiceManager.startSession { transcript ->
                                    voiceSessionHandler.invoke(transcript)
                                }
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==================== Message Bubble ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            // 用户消息：青蓝渐变气泡
            UserMessageBubble(
                message = message,
                dateFormat = dateFormat,
                onCardAction = onCardAction,
                onLongClick = { showMenu = true },
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it }
            )
        } else {
            // AI 消息：暗色背景 + 青色左边框
            AiMessageBubble(
                message = message,
                dateFormat = dateFormat,
                onCardAction = onCardAction,
                onLongClick = { showMenu = true },
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it }
            )
        }
    }
}

/** 用户消息气泡 — 青蓝渐变 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit,
    onLongClick: () -> Unit,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(SciFiUserBubbleStart, SciFiUserBubbleEnd)
    )
    val clipboard = LocalClipboardManager.current

    Box {
        Box(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                )
                .combinedClickable(
                    onClick = { },
                    onLongClick = onLongClick
                )
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                .background(gradientBrush)
                .padding(12.dp)
        ) {
            Column {
                val segments = remember(message.content) { A2UICardParser.parse(message.content) }
                for (segment in segments) {
                    when (segment) {
                        is MessageSegment.Text -> {
                            Text(
                                text = segment.text,
                                color = Color.White
                            )
                        }
                        is MessageSegment.A2UICard -> {
                            A2UICardRouter(
                                card = segment.card,
                                onActionClick = onCardAction,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    text = dateFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { onShowMenuChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, "复制") },
                onClick = {
                    clipboard.setText(AnnotatedString(message.content))
                    onShowMenuChange(false)
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, "删除") },
                onClick = { onShowMenuChange(false) }
            )
        }
    }
}

/** AI 消息气泡 — 暗色背景 + 青色左边框 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiMessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit,
    onLongClick: () -> Unit,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Box {
        Box(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                )
                .combinedClickable(
                    onClick = { },
                    onLongClick = onLongClick
                )
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp))
                .background(SciFiAiBubbleBg)
                .drawBehind {
                    // 青色左边框
                    drawLine(
                        color = SciFiAiBubbleBorder,
                        start = Offset(0f, 8.dp.toPx()),
                        end = Offset(0f, size.height - 8.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Column {
                val segments = remember(message.content) { A2UICardParser.parse(message.content) }
                val a2uiRenderer = rememberA2UIRenderer()

                val a2uiSegments = segments.filterIsInstance<MessageSegment.A2UICard>()
                if (a2uiSegments.isNotEmpty()) {
                    LaunchedEffect(message.id) {
                        try {
                            for (cardSegment in a2uiSegments) {
                                val a2uiMessage = tryBuildA2UIMessage(cardSegment.card)
                                if (a2uiMessage != null) {
                                    a2uiRenderer.processMessage(a2uiMessage)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatScreen", "A2UI processMessage failed", e)
                        }
                    }

                    for (segment in segments) {
                        when (segment) {
                            is MessageSegment.Text -> {
                                Text(
                                    text = segment.text,
                                    color = SciFiOnSurfaceVariant
                                )
                            }
                            is MessageSegment.A2UICard -> {
                                A2UICardRouter(
                                    card = segment.card,
                                    onActionClick = onCardAction,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    for (segment in segments) {
                        when (segment) {
                            is MessageSegment.Text -> {
                                Text(
                                    text = segment.text,
                                    color = SciFiOnSurfaceVariant
                                )
                            }
                            is MessageSegment.A2UICard -> {
                                A2UICardRouter(
                                    card = segment.card,
                                    onActionClick = onCardAction,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = dateFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = SciFiOnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { onShowMenuChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, "复制") },
                onClick = {
                    clipboard.setText(AnnotatedString(message.content))
                    onShowMenuChange(false)
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, "删除") },
                onClick = { onShowMenuChange(false) }
            )
        }
    }
}

// ==================== Voice UI Components ====================

@Composable
fun VoiceStateIndicator(
    voiceState: VoiceState,
    transcript: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = when (voiceState) {
            VoiceState.Listening -> MaterialTheme.colorScheme.primaryContainer
            VoiceState.Speaking -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (voiceState) {
                VoiceState.Listening -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Listening",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "正在听取...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (transcript.isNotEmpty()) {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                VoiceState.Speaking -> {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Speaking",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "正在朗读...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                VoiceState.Processing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "处理中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun VoiceButton(
    voiceState: VoiceState,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = when (voiceState) {
                VoiceState.Listening -> Icons.Default.Mic
                VoiceState.Speaking -> Icons.Default.VolumeUp
                VoiceState.Processing -> Icons.Default.Mic
                else -> Icons.Default.Mic
            },
            contentDescription = when (voiceState) {
                VoiceState.Listening -> "Stop listening"
                VoiceState.Speaking -> "Speaking"
                else -> "Start voice input"
            },
            tint = when (voiceState) {
                VoiceState.Listening -> MaterialTheme.colorScheme.error
                VoiceState.Speaking -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.primary
            }
        )
    }
}
