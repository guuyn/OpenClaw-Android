# UI/UX 改进 — 实现计划

> 目标: 7 个任务，逐个 TDD 实现
> 项目: `/home/guuya/OpenClaw-Android-build`
> 构建验证: `./gradlew compileDebugKotlin --no-daemon`

---

## Task 1: 空状态 Welcome Card
**文件**: `ChatScreen.kt`
**测试**: 无 UI 测试，手动验证

**改动**:
- 在 `ChatScreen` 中，当 `messages.isEmpty()` 时显示 Welcome Card
- 内容：标题 "👋 你好，我是 OpenClaw" + 副标题 + 3 个快速提问按钮
- 样式：Sci-Fi 风格，居中，圆角卡片

**验证**: 清除聊天记录后看到欢迎卡片，点击按钮能发消息

---

## Task 2: 操作反馈系统 (Snackbar)
**文件**: `ChatScreen.kt`, `MainActivity.kt`
**测试**: 无

**改动**:
- 新增 `showSnackbar(state, message)` 辅助函数
- 复制消息成功 → "已复制到剪贴板"
- 保存配置成功 → "配置已保存"
- 删除消息成功 → "消息已删除"

**验证**: 每个操作后看到 Snackbar

---

## Task 3: 设置页 API Key 隐藏
**文件**: `MainActivity.kt`
**测试**: 无

**改动**:
- 新增 `showApiKey: Boolean` state
- API Key 输入框默认 `PasswordVisualTransformation`
- 添加 👁 按钮切换显示/隐藏
- 加载配置时仍从 ConfigManager 读取真实值

**验证**: 打开设置页 API Key 默认隐藏，点击眼睛可切换

---

## Task 4: 消息时间戳
**文件**: `ChatScreen.kt`
**测试**: 无

**改动**:
- 在消息气泡右下角加 `HH:mm` 时间戳
- 字体: 11sp，颜色: `SciFiOnSurfaceVariant.copy(alpha = 0.5f)`
- 格式: `SimpleDateFormat("HH:mm")`

**验证**: 每条消息右下角显示发送时间

---

## Task 5: 语音输入状态指示
**文件**: `ChatScreen.kt`
**测试**: 无

**改动**:
- 录音时（`VoiceState.Recording`）麦克风按钮变红色
- 添加脉冲动画（`infiniteRepeatable`）
- 输入框区域顶部显示 "🎤 正在聆听..." 文字

**验证**: 点击麦克风后看到状态变化

---

## Task 6: 左滑删除消息
**文件**: `ChatScreen.kt`
**测试**: 无

**改动**:
- 使用 `SwipeToDismissBox` 或手动 `Modifier.offset` 实现左滑
- 左滑后显示红色删除背景 + 删除图标
- 松手确认后从 `messages` 列表中移除

**验证**: 左滑消息可删除

---

## Task 7: 清除对话历史
**文件**: `MainActivity.kt`, `ChatScreen.kt`
**测试**: 无

**改动**:
- 设置页添加"清除对话历史"按钮
- 点击弹出确认对话框 "确定要清除所有对话历史吗？"
- 确认后清空 `messages` 列表

**验证**: 点击确认后聊天记录清空，回到 Welcome Card

---

## 执行策略

采用 **Subagent-Driven** 模式：
- Task 1-4: 独立子代理，互不依赖，可并行
- Task 5-7: 依赖 ChatScreen 改动，串行执行
- 每个任务完成后编译验证
