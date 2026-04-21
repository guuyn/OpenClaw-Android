# OpenClaw-Android 项目上下文

> **Coder Agent 必读**。每次任务开始前先理解这些规则。

## 变更日志

| 日期 | 变更 | 提交 |
|------|------|------|
| 2026-04-21 | 多轮自我反思功能集成（ReflectionStrategy） | `4919c13` |
| 2026-04-20 | 语音模块重构：长按模式 + 确认弹窗 | `26aabcb` |
| 2026-04-20 | 修复语音 ANR（IO dispatcher） | `117f8ad` |
| 2026-04-20 | 修复语音 flow 不结束（移除 awaitClose） | - |
| 2026-04-20 | 创建本文档 + Subagent 规范 | - |
| 2026-04-19 | sherpa-onnx STT/TTS + ResponseRouter | `00c82ac` |
| 2026-04-18 | 科幻 UI 主题落地 | 多个 commit |

---

## 项目基本信息

| 项 | 值 |
|---|---|
| 项目路径 | `~/.openclaw/workspace/openclaw-android/`（WSL2 主副本） |
| Windows 路径 | `E:\Android\OpenClaw-Android\`（Windows 端开发，定期同步） |
| 远程仓库 | https://github.com/guuyn/OpenClaw-Android.git |
| 包名 | `ai.openclaw.android` |
| compileSdk | 36 (Android 16) |
| minSdk | 29 |
| Kotlin | 2.1.0 |
| Compose | 最新 BOM |

---

## 构建与验证

### 编译命令
```bash
cd ~/.openclaw/workspace/openclaw-android
export ANDROID_HOME=/home/guuya/Android/Sdk
./gradlew assembleDebug
```

### 完成标准
- **BUILD SUCCESSFUL** 才算完成任务
- 编译失败 = 未完成，不要报告完成

### 安装到手机
```bash
# WSL2 → Windows 桌面
/mnt/c/Windows/System32/cmd.exe /c "copy /Y E:\\Android\\OpenClaw-Android\\app\\build\\outputs\\apk\\debug\\app-debug.apk C:\\Users\\guuya\\Desktop\\app-debug.apk"

# 安装
/mnt/e/Android/Sdk/platform-tools/adb.exe install -r "C:\Users\guuya\Desktop\app-debug.apk"
```

---

## 已知坑 & 解决方案

### 1. Compose 导入路径
```kotlin
// ❌ 错误
import androidx.compose.ui.input.pointer.detectTapGestures
import androidx.compose.ui.input.pointer.tryAwaitRelease

// ✅ 正确
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.flow.channelFlow
```

### 2. 协程 Flow
```kotlin
// ❌ 错误：flow<T>() 需要 lambda 参数
return kotlinx.coroutines.flow.flow<SttResult>()

// ✅ 正确：返回空 Flow
return kotlinx.coroutines.flow.emptyFlow()

// channelFlow 需要导入
import kotlinx.coroutines.flow.channelFlow
```

### 3. Git 换行符
- 项目已配置 `.gitattributes` 强制 `eol=lf`
- 如果文件被 CRLF 污染：`git checkout -- <file>` 恢复
- 永远不要执行 `git checkout HEAD -- .`（会丢失未提交修改）

### 4. WSL2 路径
- Windows 路径映射：`E:\Android\` → `/mnt/e/Android/`
- ADB 在 Windows 侧：`/mnt/e/Android/Sdk/platform-tools/adb.exe`
- 安装 APK 需要先 copy 到 Windows 桌面，adb 才能访问

### 5. ChatScreen 语音逻辑
- 麦克风按钮使用 `MutableInteractionSource` + `LaunchedEffect(isMicPressed)`
- **不要**同时使用 `pointerInput + detectTapGestures`（会导致两套逻辑冲突，按钮无响应）
- 麦克风按钮必须设置 `interactionSource = micInteractionSource`

### 6. VoiceInteractionManager API 变更历史
- **旧 API**（2026-04-19 前）: `startSession(onTranscript)` — 已废弃
- **新 API**（2026-04-20）: `startListening(): Flow<SttResult>` + `stopListening()`
- `voiceSessionHandler` 参数已从 ChatScreen 移除

---

## 架构要点

### 语音模块（VoiceInteractionManager）
- **新 API**（2026-04-20 更新）:
  - `startListening(): Flow<SttResult>` — 开始识别
  - `stopListening()` — 停止识别
  - `transcript: StateFlow<String>` — 实时文字
  - `finalTranscript: StateFlow<String>` — 最终文字
- **旧 API 已废弃**: `startSession`, `voiceSessionHandler`
- 长按模式：press→startListening, release→stopListening→弹确认框

### STT 引擎
- `SherpaSttEngine` — 本地 ONNX 模型，无需 Google 服务
- `AndroidSpeechRecognizer` — 降级方案（需要 GMS）
- 自动检测模型，优先 Sherpa

### ChatScreen
- Compose 实现的聊天界面
- 科幻主题（Sci-Fi）
- 不支持 `voiceSessionHandler` 参数（已移除）

---

## 编码规范

1. **TDD**：重要功能先写测试
2. **最小改动**：不要修改任务范围外的文件
3. **编译验证**：每次改动后跑 `./gradlew assembleDebug`
4. **Commit 频繁**：每次功能完成后 commit
5. **文档同步**（⭐ 重要）
   - 修改代码的同时**必须更新** `docs/` 下的设计文档
   - 架构变更 → 更新架构文档
   - API 变更 → 更新 API 文档/本文档
   - UI 变更 → 更新 `docs/ui-*.md`
   - 语音模块变更 → 更新本文档
   - **目的**：让各个 agent 阅读 docs 就能快速了解项目整体情况

---

## 文件结构
```
app/src/main/java/ai/openclaw/android/
├── ChatScreen.kt          # 聊天界面
├── MainActivity.kt        # 主入口
├── GatewayManager.kt      # Gateway 核心
├── ConfigManager.kt       # 配置管理
├── voice/                 # 语音模块
│   ├── VoiceInteractionManager.kt
│   ├── stt/               # 语音识别
│   │   ├── SherpaSttEngine.kt
│   │   ├── AndroidSpeechRecognizer.kt
│   │   └── SherpaSttManager.kt
│   └── tts/               # 语音合成
│       ├── SherpaTtsEngine.kt
│       └── AndroidTTSEngine.kt
├── agent/                 # Agent 系统
├── model/                 # LLM 客户端
├── skill/                 # 技能系统
├── trigger/               # Cron 触发器
├── ui/                    # UI 组件
└── data/                  # Room 数据库
```
