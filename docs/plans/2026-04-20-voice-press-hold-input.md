# 语音长按输入（飞书模式）Implementation Plan

> **For implementer:** TDD 不适用（Compose UI），但必须确保编译通过后再 commit。

**Goal:** 将语音输入从"点击自动发送"改为"长按录音 → 松手停止 → 弹窗确认发送/取消"

**Architecture:** 复用已修改的 `VoiceInteractionManager`（新 `startListening`/`stopListening` API），在 `ChatScreen` 中用 `Modifier.pointerInput` 实现长按检测，识别完成后弹出底部弹窗显示文字 + 发送/取消按钮。

**Tech Stack:** Kotlin, Jetpack Compose, Flow

---

### Task 1: ChatScreen — 长按麦克风 + 实时浮层

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/ChatScreen.kt`

**Step 1: 删除旧的 voiceSessionHandler 相关逻辑**
- 移除 `voiceSessionHandler` 参数
- 移除 `audioPermissionLauncher` 和旧的 `voiceManager.startSession { ... }` 调用
- 保留 `voiceManager` 初始化（initialize + destroy），但改用新 API

**Step 2: 添加状态变量**
```kotlin
var isRecording by remember { mutableStateOf(false) }
val scope = rememberCoroutineScope()
var voiceCollectJob by remember { mutableStateOf<Job?>(null) }
```

**Step 3: 替换麦克风按钮为长按交互**
```kotlin
val micInteractionSource = remember { MutableInteractionSource() }
val isMicPressed by micInteractionSource.collectIsPressedAsState()

// 监听 press/release 变化
LaunchedEffect(isMicPressed) {
    if (isMicPressed && !isRecording && !isLoading) {
        if (voiceManager.hasRecordAudioPermission()) {
            isRecording = true
            voiceCollectJob = scope.launch {
                voiceManager.startListening().collect { result ->
                    // 实时更新 _transcript（已有 VoiceStateIndicator 显示）
                }
            }
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    } else if (!isMicPressed && isRecording) {
        // 松手 → 停止识别
        voiceManager.stopListening()
        voiceCollectJob?.cancel()
        voiceCollectJob = null
        isRecording = false
        // 如果有识别到文字，弹出确认框
        val text = voiceManager.finalTranscript.value
        if (text.isNotBlank()) {
            pendingVoiceText = text
            showVoiceConfirm = true
        }
    }
}
```

**Step 4: 添加确认弹窗状态**
```kotlin
var pendingVoiceText by remember { mutableStateOf("") }
var showVoiceConfirm by remember { mutableStateOf(false) }

if (showVoiceConfirm) {
    AlertDialog(
        onDismissRequest = { showVoiceConfirm = false; pendingVoiceText = "" },
        title = { Text("确认发送") },
        text = { Text(pendingVoiceText) },
        confirmButton = {
            TextButton(onClick = {
                sendMessage(pendingVoiceText)
                showVoiceConfirm = false
                pendingVoiceText = ""
            }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = {
                showVoiceConfirm = false
                pendingVoiceText = ""
            }) { Text("取消") }
        }
    )
}
```

**Step 5: 编译验证**
```bash
cd /mnt/e/Android/OpenClaw-Android && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

**Step 6: Commit**
```bash
git add app/src/main/java/ai/openclaw/android/ChatScreen.kt
git commit -m "feat: press-and-hold voice input with send/cancel confirmation"
```

---

### Task 2: MainActivity — 清理 voiceSessionHandler

**Files:**
- Modify: `app/src/main/java/ai/openclaw/android/MainActivity.kt`

**Step 1: 移除 ChatScreen 调用中的 voiceSessionHandler**
- 从 `ChatScreen(...)` 调用中删除 `voiceSessionHandler = { ... }` 整段
- `ChatScreen` 现在只需要：`sendMessage, messages, isLoading, modifier, lastDeliverable, lastRichContent, onSpeakText`

**Step 2: 编译验证**
```bash
cd /mnt/e/Android/OpenClaw-Android && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add app/src/main/java/ai/openclaw/android/MainActivity.kt
git commit -m "refactor: remove voiceSessionHandler from MainActivity, ChatScreen handles voice independently"
```

---

### Task 3: 验证 APK 安装

**Step 1: 构建 APK**
```bash
cd /mnt/e/Android/OpenClaw-Android && ./gradlew assembleDebug
```

**Step 2: 安装到手机**
```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/guuya/Desktop/app-debug.apk
adb install -r "C:\Users\guuya\Desktop\app-debug.apk"
```

**Step 3: 验证**
- 长按麦克风图标 → 显示"正在听取..."
- 实时显示识别文字
- 松手 → 弹出确认弹窗
- 点击发送 → 消息正常发送
