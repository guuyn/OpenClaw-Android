package ai.openclaw.android

import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import java.io.File
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.android.domain.Deliverable
import ai.openclaw.android.domain.RichContent
import ai.openclaw.android.voice.VoiceState
import ai.openclaw.android.ui.A2UICard
import ai.openclaw.android.ui.A2UICardParser
import ai.openclaw.android.ui.A2UICardRouter
import ai.openclaw.android.ui.A2UIComposeRenderer
import ai.openclaw.android.ui.CardAction
import ai.openclaw.android.ui.MessageSegment
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.rememberA2UIRenderer
import org.json.JSONObject
import ai.openclaw.android.ui.components.ConnectionState
import ai.openclaw.android.ui.components.EnergyBar
import ai.openclaw.android.ui.components.HapticHelper
import ai.openclaw.android.ui.components.StatusIndicator
import ai.openclaw.android.ui.components.TypingCursor
import ai.openclaw.android.ui.components.rememberHapticHelper
import ai.openclaw.android.ui.theme.MonospaceAccent
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.ImageUtils
import ai.openclaw.android.ui.theme.gradientDivider
import ai.openclaw.android.ui.theme.neonBorder
import ai.openclaw.android.ui.theme.sciFiGlow
import ai.openclaw.android.ui.theme.SciFiOnBackground
import ai.openclaw.android.ui.theme.SciFiError
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share

// ==================== A2UI Protocol Bridge ====================

// Legacy function marked as deprecated, kept for backward compatibility
@Deprecated("Use A2UIComposeRenderer which handles standard protocol natively")
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
    val timestamp: Long = System.currentTimeMillis(),
    val images: List<ai.openclaw.android.model.ImageContent>? = null
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
    sendMessage: (String, List<ImageContent>) -> Unit,
    messages: List<ChatMessage>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    scaffoldPadding: PaddingValues = PaddingValues(),
    lastDeliverable: Deliverable? = null,
    lastRichContent: RichContent? = null,
    onSpeakText: ((String) -> Unit)? = null,
    // Voice callbacks — hoisted to single VoiceInteractionManager in MainActivity
    voiceState: ai.openclaw.android.voice.VoiceState = ai.openclaw.android.voice.VoiceState.Idle,
    voiceTranscript: String = "",
    onStartListening: (() -> Unit)? = null,
    onStopListening: (() -> Unit)? = null,
    hasRecordAudioPermission: () -> Boolean = { false },
    onRequestAudioPermission: (() -> Unit)? = null,
    // Volume key voice input
    isVolumeKeyListening: () -> Boolean = { false },
    volumeKeyTranscriptProvider: () -> String = { "" },
    showVolumeKeyConfirm: () -> Boolean = { false },
    volumeKeyPendingTextProvider: () -> String = { "" },
    onDismissVolumeKeyConfirm: (() -> Unit)? = null,
    onSendVolumeKeyVoice: ((String) -> Unit)? = null,
) {
    var renderedRichContent by remember { mutableStateOf<Map<String, RichContent>>(emptyMap()) }

    // Store rich content against the last assistant message ID
    LaunchedEffect(messages.size, lastRichContent) {
        if (lastRichContent != null) {
            val lastAiIdx = messages.indexOfLast { it.role == "assistant" }
            if (lastAiIdx >= 0) {
                renderedRichContent = renderedRichContent + (messages[lastAiIdx].id to lastRichContent)
            }
        }
    }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 图片输入状态
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showImageOptions by remember { mutableStateOf(false) }

    // 图片选择 Launcher（从相册选择多张）
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedImages = (selectedImages + uris).take(3)  // 最多3张
    }

    // 拍照 Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val uri = ImageUtils.saveBitmapToTempUri(context, bitmap)
            if (uri != null) {
                selectedImages = (selectedImages + uri).take(3)
            }
        }
    }

    // Voice recording states (UI-only, hoisted VoiceInteractionManager in MainActivity)
    var isRecording by remember { mutableStateOf(false) }
    var pendingVoiceText by remember { mutableStateOf("") }
    var showVoiceConfirm by remember { mutableStateOf(false) }
    var lastFinalTranscript by remember { mutableStateOf("") }

    val sendInteraction = remember { MutableInteractionSource() }
    val isSendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (isSendPressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "sendScale"
    )

    // Audio permission launcher — calls back to hoisted VoiceInteractionManager
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isRecording && !isLoading) {
            onStartListening?.invoke()
        }
    }

    // 监听麦克风按钮的按下/释放事件
    val micInteractionSource = remember { MutableInteractionSource() }
    val isMicPressed by micInteractionSource.collectIsPressedAsState()

    LaunchedEffect(isMicPressed) {
        if (isMicPressed && !isRecording && !isLoading) {
            if (hasRecordAudioPermission()) {
                isRecording = true
                onStartListening?.invoke()
            } else {
                onRequestAudioPermission?.invoke()
            }
        } else if (!isMicPressed && isRecording) {
            // 松手 → 停止识别
            onStopListening?.invoke()
            isRecording = false
            // 如果有识别到文字，弹出确认框
            if (voiceTranscript.isNotBlank()) {
                lastFinalTranscript = voiceTranscript
                pendingVoiceText = voiceTranscript
                showVoiceConfirm = true
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Handle deliverable: auto-speak voice responses
    LaunchedEffect(lastDeliverable) {
        val deliverable = lastDeliverable ?: return@LaunchedEffect
        when (deliverable) {
            is Deliverable.Voice -> {
                onSpeakText?.invoke(deliverable.text)
            }
            is Deliverable.Mixed -> {
                deliverable.voice?.let { onSpeakText?.invoke(it) }
            }
            is Deliverable.PlainText -> { /* no special action */ }
            is Deliverable.RichText -> { /* no special action */ }
        }
    }

    // Card action callback: maps button clicks to message text
    val onCardAction: (CardAction) -> Unit = { action ->
        val mappedResult = mapCardActionToMessage(action, messages)
        when (mappedResult) {
            is CardActionResult.SendMessage -> {
                if (!isLoading && mappedResult.text.isNotBlank()) {
                    sendMessage(mappedResult.text, emptyList())
                }
            }
            is CardActionResult.ResendLast -> {
                if (!isLoading) {
                    messages.lastOrNull { it.role == "user" }?.let {
                        sendMessage(it.content, emptyList())
                    }
                }
            }
            CardActionResult.NoOp -> { /* safety: no message for unhandled actions */ }
        }
    }

    // Manual keyboard height detection for OEM devices with broken WindowInsets.ime.
    // Uses ViewCompat.getRootWindowInsets to read raw IME inset, then subtracts
    // the Scaffold bottom padding (NavigationBar) to avoid double-counting.
    // Only applies when adjustResize is not working (window didn't shrink with keyboard).
    val view = LocalView.current
    val density = LocalDensity.current
    val scaffoldBottomPx = with(density) { scaffoldPadding.calculateBottomPadding().toPx() }.toInt()
    val keyboardHeightDp = remember { mutableStateOf(0.dp) }
    DisposableEffect(view) {
        var initialWindowHeight = 0
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rootH = view.rootView.height
            if (initialWindowHeight == 0 && rootH > 0) initialWindowHeight = rootH
            // API 30+ uses WindowInsets.Type.ime(), fallback to keyboardHeightDp calculation
            val imeBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
            } else {
                // API 29 and below: detect keyboard by window height difference
                maxOf(0, initialWindowHeight - rootH)
            }
            if (imeBottom > 0 && rootH >= initialWindowHeight) {
                // Window didn't shrink → adjustResize not working → manual padding needed
                keyboardHeightDp.value = with(density) { maxOf(0, imeBottom - scaffoldBottomPx).toDp() }
            } else {
                keyboardHeightDp.value = 0.dp
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    Column(modifier = modifier.fillMaxSize().padding(bottom = keyboardHeightDp.value)) {
        // Top bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SciFiBackground.copy(alpha = 0.85f),
            tonalElevation = 0.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(state = ConnectionState.ONLINE)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OpenClaw",
                        color = SciFiOnBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "· qwen-plus",
                        color = SciFiOutlineVariant,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .gradientDivider()
                )
            }
        }

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
                        onCardAction = onCardAction,
                        richContent = renderedRichContent[message.id]
                    )
                }
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        SciFiThinkingDots()
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

        // Input area
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SciFiBackground.copy(alpha = 0.6f),
            tonalElevation = 0.dp
        ) {
            Column {
                // 图片预览条
                if (selectedImages.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedImages.size) { index ->
                            val uri = selectedImages[index]
                            Box(modifier = Modifier.size(64.dp)) {
                                val bitmap: Bitmap? = remember(key1 = uri) {
                                    context.contentResolver.openInputStream(uri)?.use {
                                        BitmapFactory.decodeStream(it)
                                    }
                                }
                                bitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, SciFiOutlineVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                // 删除按钮
                                IconButton(
                                    onClick = { selectedImages = selectedImages - uri },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(14.dp),
                                        tint = SciFiOnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                var isInputFocused by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图片附件按钮
                    Box {
                        IconButton(
                            onClick = { showImageOptions = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = "添加图片",
                                tint = if (selectedImages.isNotEmpty()) SciFiPrimary
                                    else SciFiOnSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showImageOptions,
                            onDismissRequest = { showImageOptions = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("从相册选择") },
                                leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showImageOptions = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showImageOptions = false
                                    cameraLauncher.launch(null)
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp)
                            .neonBorder(
                                focused = isInputFocused,
                                cornerRadius = 24.dp
                            )
                            .background(
                                SciFiSurfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(24.dp)
                            )
                            .focusRequester(focusRequester)
                            .onFocusChanged { isInputFocused = it.isFocused }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("message_input")
                                .focusable()
                                .semantics(mergeDescendants = true) {
                                    this.isEditable = true
                                    this.editableText = androidx.compose.ui.text.buildAnnotatedString {
                                        append(inputText.takeIf { it.isNotEmpty() } ?: "输入消息...")
                                    }
                                },
                            textStyle = LocalTextStyle.current.copy(
                                color = SciFiOnSurfaceVariant
                            ),
                            maxLines = 4
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    val sendEnabled = (inputText.isNotBlank() || selectedImages.isNotEmpty()) && !isLoading
                    IconButton(
                        onClick = {
                            if (sendEnabled) {
                                val images = selectedImages.mapNotNull { uri ->
                                    ImageUtils.uriToBase64(context, uri)
                                }.let { ImageUtils.validateImages(it) }
                                sendMessage(inputText, images)
                                inputText = ""
                                selectedImages = emptyList()
                            }
                        },
                        enabled = sendEnabled,
                        interactionSource = sendInteraction,
                        modifier = Modifier
                            .size(40.dp)
                            .then(
                                if (sendEnabled) Modifier.sciFiGlow(radius = 4.dp)
                                else Modifier
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            modifier = Modifier.scale(sendScale),
                            tint = if (sendEnabled) SciFiPrimary
                            else SciFiOnSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }

                    IconButton(
                        onClick = {},
                        interactionSource = micInteractionSource,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "长按说话",
                            modifier = Modifier.size(32.dp),
                            tint = if (isRecording) SciFiPrimary else SciFiOnSurfaceVariant
                        )
                    }
                }

                EnergyBar(isFocused = isInputFocused)

                // 音量键语音输入提示
                if (!isVolumeKeyListening()) {
                    Text(
                        text = "💡 长按音量下键可语音输入",
                        color = SciFiOnSurfaceVariant.copy(alpha = 0.35f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 语音识别确认弹窗（麦克风按钮）
        if (showVoiceConfirm) {
            AlertDialog(
                onDismissRequest = { 
                    showVoiceConfirm = false
                    pendingVoiceText = ""
                },
                title = { Text("确认发送") },
                text = { 
                    Text(pendingVoiceText) 
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            sendMessage(pendingVoiceText, emptyList())
                            showVoiceConfirm = false
                            pendingVoiceText = ""
                        }
                    ) { 
                        Text("发送") 
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showVoiceConfirm = false
                            pendingVoiceText = ""
                        }
                    ) { 
                        Text("取消") 
                    }
                }
            )
        }

        // 音量键语音确认弹窗
        if (showVolumeKeyConfirm()) {
            AlertDialog(
                onDismissRequest = { onDismissVolumeKeyConfirm?.invoke() },
                title = { Text("🎤 语音输入") },
                text = { Text(volumeKeyPendingTextProvider()) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onSendVolumeKeyVoice?.invoke(volumeKeyPendingTextProvider())
                        }
                    ) { Text("发送") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onDismissVolumeKeyConfirm?.invoke() }
                    ) { Text("取消") }
                }
            )
        }
    }
}

// ==================== Message Bubble ====================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit = {},
    richContent: RichContent? = null
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState()
    var showMenu by remember { mutableStateOf(false) }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            containerColor = SciFiSurfaceVariant
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.width(40.dp).height(4.dp)
                            .background(SciFiOutlineVariant, RoundedCornerShape(2.dp))
                    )
                }
                BottomMenuOption(Icons.Default.ContentCopy, "复制") {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                }
                if (!isUser) {
                    BottomMenuOption(Icons.Default.Refresh, "重新生成") { showMenu = false }
                }
                BottomMenuOption(Icons.Default.Share, "分享") { showMenu = false }
                BottomMenuOption(Icons.Default.Delete, "删除", tint = SciFiError) { showMenu = false }
            }
        }
    }

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
                onLongClick = { showMenu = true }
            )
        } else {
            // AI 消息：暗色背景 + 青色左边框
            AiMessageBubble(
                message = message,
                dateFormat = dateFormat,
                onCardAction = onCardAction,
                onLongClick = { showMenu = true },
                richContent = richContent
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
    onLongClick: () -> Unit
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(SciFiUserBubbleStart, SciFiUserBubbleEnd)
    )

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
            // 显示图片
            message.images?.forEach { imageContent ->
                val bitmap = remember(imageContent.base64) {
                    try {
                        val bytes = Base64.decode(imageContent.base64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Exception) { null }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = imageContent.description,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .padding(bottom = 4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
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
                    is MessageSegment.StandardProtocol -> {
                        val a2uiRenderer = rememberA2UIRenderer()
                        A2UIComposeRenderer(
                            content = "[A2UI]${segment.json}[/A2UI]",
                            renderer = a2uiRenderer,
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
}

/** AI 消息气泡 — 暗色背景 + 青色左边框 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiMessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat,
    onCardAction: (CardAction) -> Unit,
    onLongClick: () -> Unit,
    richContent: RichContent? = null
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    text = "🤖 OpenClaw",
                    style = MonospaceAccent,
                    color = SciFiOutlineVariant
                )
            }
            val segments = remember(message.content) { A2UICardParser.parse(message.content) }
            val a2uiRenderer = rememberA2UIRenderer()

            for (segment in segments) {
                when (segment) {
                    is MessageSegment.Text -> {
                        Text(
                            text = segment.text,
                            color = SciFiOnSurfaceVariant
                        )
                    }
                    is MessageSegment.A2UICard -> {
                        // Use A2UICardRouter directly for legacy card format
                        A2UICardRouter(
                            card = segment.card,
                            onActionClick = { action -> /* TODO: handle action */ },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    is MessageSegment.StandardProtocol -> {
                        A2UIComposeRenderer(
                            content = "[A2UI]${segment.json}[/A2UI]",
                            renderer = a2uiRenderer,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            // Render RichContent if available
            richContent?.let { content ->
                Spacer(modifier = Modifier.height(8.dp))
                RichContentCard(content = content)
            }

            Text(
                text = dateFormat.format(Date(message.timestamp)),
                style = MonospaceAccent,
                color = SciFiOnSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.End)
            )
        }
    }
}

// ==================== Rich Content Components ====================

/** Renders a RichContent object as Compose UI. */
@Composable
fun RichContentCard(
    content: RichContent,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SciFiSurfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, SciFiAiBubbleBorder, RoundedCornerShape(12.dp)),
        color = Color.Transparent
    ) {
        when (content) {
            is RichContent.ListCard -> ListCardContent(content)
            is RichContent.InfoCard -> InfoCardContent(content)
            is RichContent.CodeBlock -> CodeBlockContent(content)
        }
    }
}

@Composable
private fun ListCardContent(card: RichContent.ListCard) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = card.title,
            fontWeight = FontWeight.Bold,
            color = SciFiOnSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        card.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("• ", color = SciFiPrimary, fontWeight = FontWeight.Bold)
                Text(item, color = SciFiOnSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun InfoCardContent(card: RichContent.InfoCard) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = card.title,
            fontWeight = FontWeight.Bold,
            color = SciFiOnSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = card.body,
            color = SciFiOnSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CodeBlockContent(block: RichContent.CodeBlock) {
    Column(modifier = Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📝",
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                text = block.language.ifBlank { "code" },
                style = MonospaceAccent,
                color = SciFiPrimary,
                fontSize = 12.sp
            )
        }
        Text(
            text = block.code,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = SciFiOnSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(SciFiBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
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

@Composable
private fun SciFiThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition()
    Row(
        modifier = Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                )
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(SciFiPrimary.copy(alpha = alpha), CircleShape)
                    .scale(scale)
            )
        }
    }
}

@Composable
private fun BottomMenuOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = SciFiOnBackground,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

/** Full-page error card — sci-fi styled with friendly Chinese text */
@Composable
fun SciFiErrorCard(
    title: String,
    description: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = SciFiSurfaceVariant,
        border = BorderStroke(1.dp, SciFiError)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠", fontSize = 24.sp, color = SciFiError)
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = SciFiOnBackground, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, color = SciFiOnSurfaceVariant, fontSize = 13.sp)
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SciFiPrimary),
                    border = BorderStroke(1.dp, SciFiPrimary)
                ) {
                    Text("重新连接")
                }
            }
        }
    }
}

/** Inline error card for chat stream */
@Composable
fun InlineErrorCard(
    errorCode: String,
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = SciFiError.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = SciFiError,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠", fontSize = 12.sp, color = SciFiError)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(errorCode, style = MonospaceAccent, color = SciFiError)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(message, color = SciFiOnSurfaceVariant, fontSize = 13.sp)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("重试", color = SciFiPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}


