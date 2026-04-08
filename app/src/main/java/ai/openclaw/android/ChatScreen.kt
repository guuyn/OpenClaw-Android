package ai.openclaw.android

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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Thermostat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.rememberA2UIRenderer

// ==================== A2UI Parsing ====================

sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class A2UICard(val type: String, val data: Map<String, String>) : MessageSegment()
}

private fun parseMessageContent(content: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val startTag = "[A2UI]"
    val endTag = "[/A2UI]"
    var cursor = 0

    while (cursor < content.length) {
        val startIdx = content.indexOf(startTag, cursor)
        if (startIdx == -1) break

        // Text before [A2UI]
        if (startIdx > cursor) {
            val textBefore = content.substring(cursor, startIdx).trim()
            if (textBefore.isNotEmpty()) {
                segments.add(MessageSegment.Text(textBefore))
            }
        }

        val jsonStart = startIdx + startTag.length
        val endIdx = content.indexOf(endTag, jsonStart)
        if (endIdx == -1) break

        val jsonStr = content.substring(jsonStart, endIdx).trim()
        try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(jsonStr).jsonObject
            val type = element["type"]?.jsonPrimitive?.content ?: "generic"
            val dataObj = element["data"]?.jsonObject
            val data = dataObj?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            segments.add(MessageSegment.A2UICard(type, data))
        } catch (e: Exception) {
            segments.add(MessageSegment.Text(jsonStr))
        }

        cursor = endIdx + endTag.length
    }

    // Remaining text after last [/A2UI]
    if (cursor < content.length) {
        val remaining = content.substring(cursor).trim()
        if (remaining.isNotEmpty()) {
            segments.add(MessageSegment.Text(remaining))
        }
    }

    if (segments.isEmpty()) {
        segments.add(MessageSegment.Text(content))
    }

    return segments
}

@Composable
private fun A2UICardView(card: MessageSegment.A2UICard) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row with icon and type
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (card.type) {
                    "weather" -> Icons.Default.Thermostat
                    "location" -> Icons.Default.LocationOn
                    "search" -> Icons.Default.Search
                    "translation" -> Icons.Default.Translate
                    "reminder" -> Icons.Default.Notifications
                    else -> Icons.Default.Search
                }
                val label = when (card.type) {
                    "weather" -> "天气"
                    "location" -> "位置"
                    "search" -> "搜索"
                    "translation" -> "翻译"
                    "reminder" -> "提醒"
                    else -> card.type
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Data rows
            card.data.forEach { (key, value) ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "${key}: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==================== A2UI Protocol Bridge ====================

/**
 * Converts a legacy A2UICard (type+data from skills) into an A2UI protocol message
 * that the A2UI component library can render. Returns null if the card data
 * cannot be converted to a valid A2UI message.
 */
private fun tryBuildA2UIMessage(card: MessageSegment.A2UICard): String? {
    val surfaceId = "msg_card_${card.type}"
    val children = card.data.entries.mapIndexed { idx, (key, value) ->
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
                        dateFormat = dateFormat
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

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 120.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("输入消息...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
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
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    }
                )

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
    dateFormat: SimpleDateFormat
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                )
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true }
                )
        ) {
            Box {
                Column(modifier = Modifier.padding(12.dp)) {
                    val segments = remember(message.content) { parseMessageContent(message.content) }
                    val a2uiRenderer = rememberA2UIRenderer()

                    // Collect A2UI content and process with renderer
                    val a2uiSegments = segments.filterIsInstance<MessageSegment.A2UICard>()
                    if (!isUser && a2uiSegments.isNotEmpty()) {
                        // Process A2UI segments with the library renderer
                        LaunchedEffect(message.id) {
                            for (card in a2uiSegments) {
                                val a2uiMessage = tryBuildA2UIMessage(card)
                                if (a2uiMessage != null) {
                                    a2uiRenderer.processMessage(a2uiMessage)
                                }
                            }
                        }

                        // Render non-A2UI text segments
                        for (segment in segments) {
                            if (segment is MessageSegment.Text) {
                                Text(
                                    text = segment.text,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Render A2UI surfaces
                        for (card in a2uiSegments) {
                            val surfaceId = "msg_${message.id}_${card.type}"
                            val surfaceContent = a2uiRenderer.renderSurface(surfaceId)
                            surfaceContent()
                        }
                    } else {
                        // Original rendering for user messages or messages without A2UI
                        for (segment in segments) {
                            when (segment) {
                                is MessageSegment.Text -> {
                                    Text(
                                        text = segment.text,
                                        color = if (isUser)
                                            MaterialTheme.colorScheme.onPrimary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                is MessageSegment.A2UICard -> {
                                    A2UICardView(segment)
                                }
                            }
                        }
                    }

                    Text(
                        text = dateFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, "复制") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, "删除") },
                        onClick = {
                            showMenu = false
                        }
                    )
                }
            }
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
