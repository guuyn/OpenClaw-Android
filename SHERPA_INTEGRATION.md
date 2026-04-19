# Sherpa-ONNX STT/TTS 集成指南

## 概述

本项目已集成 sherpa-onnx 作为本地语音识别 (STT) 和语音合成 (TTS) 引擎，替代依赖 Google 服务的 Android 系统语音组件。华为设备（无 GMS）也能正常使用语音交互。

## 新增文件

### Phase 1: STT 替换
| 文件 | 说明 |
|------|------|
| `voice/stt/SherpaSttEngine.kt` | sherpa-onnx STT 引擎实现 `SpeechToTextEngine` 接口 |
| `voice/stt/SherpaSttManager.kt` | STT 引擎生命周期管理（单例、懒加载） |

### Phase 2: TTS 升级
| 文件 | 说明 |
|------|------|
| `voice/tts/SherpaTtsEngine.kt` | sherpa-onnx TTS 引擎实现 `TextToSpeechEngine` 接口 |

### Phase 3: 模型下载管理
| 文件 | 说明 |
|------|------|
| `voice/ModelDownloadManager.kt` | 模型下载管理器（断点续传、SHA256校验） |
| `voice/ui/ModelDownloadScreen.kt` | Compose 下载界面（进度条、WiFi提示） |

### 修改的文件
| 文件 | 变更 |
|------|------|
| `voice/VoiceInteractionManager.kt` | 默认使用 Sherpa 引擎，保留 Android 引擎作为备选 |
| `app/build.gradle.kts` | 添加 sherpa-onnx AAR 依赖 |
| `app/proguard-rules.pro` | 添加 sherpa-onnx ProGuard 规则 |
| `scripts/download-sherpa-onnx.sh` | AAR 下载脚本 |

## 部署步骤

### 1. 下载 sherpa-onnx AAR（~56MB）

```bash
cd /mnt/e/Android/OpenClaw-Android
bash scripts/download-sherpa-onnx.sh
```

或手动下载：
```bash
curl -L -o app/libs/sherpa-onnx.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.39/sherpa-onnx-1.12.39.aar"
```

> **注意**：当前项目包含一个编译用的 stub AAR（仅含类签名），功能测试需要替换为真实的 AAR。

### 2. 下载语音模型

首次运行时，App 会自动检测模型是否就绪。未下载时会显示 `ModelDownloadScreen` 界面引导下载。

支持的模型：

#### STT 模型（任选其一）
| 模型 | 大小 | 说明 |
|------|------|------|
| `sherpa-onnx-streaming-zipformer-zh-14M` | ~70MB | 轻量级中文流式识别，推荐 |
| `sherpa-onnx-streaming-paraformer-bilingual-zh-en` | ~240MB | 中英双语，识别质量更高 |

#### TTS 模型
| 模型 | 大小 | 说明 |
|------|------|------|
| `vits-melo-tts-zh_en` | ~85MB | 中英文语音合成 |

模型下载地址：
- STT: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- TTS: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models

下载后解压到手机存储：
```
# STT 模型目录
/sdcard/Android/data/ai.openclaw.android/files/models/stt/
  ├── encoder-epoch-99-avg-1.int8.onnx
  ├── decoder-epoch-99-avg-1.onnx
  ├── joiner-epoch-99-avg-1.int8.onnx
  └── tokens.txt

# TTS 模型目录
/sdcard/Android/data/ai.openclaw.android/files/models/tts/
  ├── model.onnx
  ├── lexicon.txt
  └── tokens.txt
```

### 3. 构建 APK

```bash
cd /mnt/e/Android/OpenClaw-Android
export ANDROID_HOME=/home/guuya/Android/Sdk
./gradlew assembleDebug --no-daemon
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

### 4. 安装到真机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 架构说明

### 引擎选择逻辑

`VoiceInteractionManager` 自动选择最佳可用引擎：

```
初始化流程:
1. 尝试 SherpaSttEngine → 如果模型存在则使用
2. 否则尝试 AndroidSpeechRecognizer → 如果系统支持则使用
3. 否则 STT 不可用

TTS 同理:
1. 尝试 SherpaTtsEngine → 如果模型存在则使用  
2. 否则尝试 AndroidTTSEngine（系统 TTS）
```

### 运行时引擎切换

```kotlin
val manager = VoiceInteractionManager(context)

// 查询当前引擎
println(manager.activeSttEngine)  // "sherpa-onnx" 或 "android"
println(manager.activeTtsEngine)  // "sherpa-onnx" 或 "android"

// 手动切换
manager.switchToSherpaStt(modelPath)
manager.switchToAndroidStt()
manager.switchToSherpaTts(modelPath)
manager.switchToAndroidTts()
```

### 支持的模型类型

#### STT (OnlineRecognizer - 流式)
- **Zipformer zh-14M**: `encoder-epoch-99-avg-1.int8.onnx` + `decoder` + `joiner` + `tokens.txt`
- **Streaming Paraformer Bilingual**: `encoder.int8.onnx` + `decoder.int8.onnx` + `tokens.txt`

#### TTS (OfflineTts)
- **VITS**: `model.onnx` + `lexicon.txt` + `tokens.txt`
- **Matcha**: `model-steps-3.onnx` + `vocos-*.onnx` + `lexicon.txt` + `tokens.txt`

## 已知限制

1. **Stub AAR**: 当前项目包含编译用的 stub AAR，不包含真实的 JNI 库。功能测试需要下载真实 AAR（56MB）。
2. **模型下载**: 模型文件较大（STT ~70-240MB, TTS ~85MB），建议在 WiFi 环境下下载。
3. **存储权限**: 需要 `MANAGE_EXTERNAL_STORAGE` 权限（已在 AndroidManifest.xml 中声明）。

## 文件清单

```
app/src/main/java/ai/openclaw/android/voice/
├── stt/
│   ├── SpeechToTextEngine.kt       # STT 接口（原有）
│   ├── AndroidSpeechRecognizer.kt  # Android 系统 STT（原有，备选）
│   ├── IntentSpeechRecognizer.kt   # Intent 方式 STT（原有）
│   ├── SherpaSttEngine.kt          # ✨ 新增：Sherpa STT 引擎
│   └── SherpaSttManager.kt         # ✨ 新增：STT 生命周期管理
├── tts/
│   ├── TextToSpeechEngine.kt       # TTS 接口（原有）
│   ├── AndroidTTSEngine.kt         # Android 系统 TTS（原有，备选）
│   └── SherpaTtsEngine.kt          # ✨ 新增：Sherpa TTS 引擎
├── ui/
│   └── ModelDownloadScreen.kt      # ✨ 新增：模型下载 Compose 界面
├── VoiceInteractionManager.kt      # 修改：默认使用 Sherpa 引擎
├── VoiceSession.kt                 # 原有：状态机
└── ModelDownloadManager.kt         # ✨ 新增：模型下载管理器
```
