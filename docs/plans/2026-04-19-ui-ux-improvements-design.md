# OpenClaw Android UI/UX 改进设计文档

> 创建时间: 2026-04-19
> 基于: ui-ux-pro-max skill 诊断报告
> 目标平台: Android (Jetpack Compose)
> 设计风格: Sci-Fi Cyberpunk (暗色 + 青色霓虹)

---

## 当前状态

OpenClaw Android 应用已有基本功能（聊天、设置、通知三 Tab），但缺少关键 UX 细节：
- 新安装后聊天页为空屏，无引导
- 操作无反馈（复制、删除、保存均无提示）
- 设置页 API Key 明文暴露
- 消息无时间戳
- 语音输入无状态指示

---

## 改进项清单

### P0: 空状态 Welcome Card
- **文件**: `ChatScreen.kt`
- **行为**: 当 `messages.isEmpty()` 时，显示欢迎卡片
- **内容**: 标题 + 副标题 + 3 个快速提问按钮

### P0: 操作反馈系统
- **文件**: `ChatScreen.kt`, `MainActivity.kt`
- **新增**: Snackbar 封装，统一操作反馈
- **场景**: 复制成功、删除成功、保存成功

### P0: 设置页 API Key 隐藏
- **文件**: `MainActivity.kt`
- **行为**: API Key 默认 `••••••••`，点击 👁 切换显示
- **新增**: `passwordVisualTransformation` 参数

### P1: 消息时间戳
- **文件**: `ChatScreen.kt`
- **行为**: 每条消息气泡右下角显示 `HH:mm`

### P1: 语音输入状态指示
- **文件**: `ChatScreen.kt`
- **行为**: 录音时麦克风按钮变色 + 脉冲动画 + "正在聆听..." 文字

### P2: 左滑删除消息
- **文件**: `ChatScreen.kt`
- **行为**: 左滑消息气泡，显示删除按钮

### P2: 清除对话历史
- **文件**: `MainActivity.kt`, `AgentSession.kt`
- **行为**: 设置页添加"清除历史"按钮 → 确认弹窗 → 调用 `clearHistory()`

---

## 设计系统 Token 更新

```kotlin
// 新增/调整
--spacing-xs:   4dp
--spacing-sm:   8dp
--spacing-md:  16dp
--spacing-lg:  24dp
--spacing-xl:  32dp

--text-caption: 11sp / 1.4 line-height (时间戳)
--text-body:    14sp / 1.5 line-height
--text-title:   18sp / 1.3 line-height

--radius-sm:    8dp  (按钮)
--radius-md:   12dp  (输入框)
--radius-lg:   16dp  (消息气泡)

--glow-recording: 0 0 12px rgba(255, 68, 68, 0.5) (录音状态)
```
