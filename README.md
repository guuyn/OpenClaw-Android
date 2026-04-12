# OpenClaw-Android

Android client for OpenClaw — an AI-powered mobile automation platform with on-device LLM, skill system, and multi-platform integration.

## Architecture

### Gateway Service Pattern (v2.0)

The app follows a **single-source-of-truth** architecture where `GatewayService` holds the only instance of all core components:

```
┌─────────────────────────────────────────────────────────┐
│                    GatewayService ★                      │
│              【唯一逻辑中心 / Single Logic Center】        │
│  ┌───────────────────────────────────────────────────┐  │
│  │  GatewayManager (implements GatewayContract)       │  │
│  │  ├─ LocalLLMClient (唯一实例) - 3.5GB             │  │
│  │  ├─ AgentSession (唯一实例)                        │  │
│  │  ├─ SkillManager (唯一实例)                        │  │
│  │  ├─ FeishuClient                                   │  │
│  │  ├─ MemoryManager + EmbeddingService              │  │
│  │  └─ AppDatabase (Room + SQLCipher)                │  │
│  └───────────────────────────────────────────────────┘  │
│                                                          │
│  ★ 提供 Binder 接口供 Activity 调用                       │
│  ★ 模型生命周期独立于 Activity                            │
└─────────────────────────────────────────────────────────┘
                        ▲
                        │ Binder / GatewayContract
                        ▼
┌─────────────────────────────────────────────────────────┐
│                     MainActivity                          │
│                   【纯 UI 层 / Pure UI Layer】             │
│  ┌───────────────────────────────────────────────────┐  │
│  │  Compose UI (聊天 / 通知 / 设置)                    │  │
│  │  用户输入 → GatewayService → 接收响应 → 展示        │  │
│  └───────────────────────────────────────────────────┘  │
│                                                          │
│  保留: PermissionManager（权限请求）                      │
└─────────────────────────────────────────────────────────┘
```

**Benefits:**
- ✅ 单一模型实例 — 节省 3.5GB 内存
- ✅ Activity 销毁不影响模型状态 — 零重载时间
- ✅ Feishu 消息和 UI 聊天共享同一 AgentSession
- ✅ 清晰的职责分离

## Features

### On-Device AI
- **Local LLM**: Gemma 4 (E2B/E4B) via LiteRT-LM with GPU/NPU/CPU acceleration
- **Cloud LLMs**: 阿里百炼 (Qwen), OpenAI (GPT-4o), Anthropic (Claude)
- **Tool Use**: Native function calling for skill execution

### Memory System
- **BM25 Full-Text Search**: In-memory inverted index with Chinese bigram tokenization
- **Vector Semantic Search**: 384-dim embeddings via MiniLM (TFLite) or fallback
- **Hybrid Search**: Score fusion (0.35 BM25 + 0.55 vector + 0.10 recency)
- **Cold Start Manager**: Gradual memory activation over first 72 hours
- **Encrypted Storage**: SQLCipher for database, Android Keystore for keys

### Skills (12 Built-in)
| Skill | Description |
|-------|-------------|
| Weather | Weather query with A2UI card display |
| MultiSearch | Web search across multiple engines |
| Translate | Text translation |
| Reminder | Time-based reminders with alarms |
| Calendar | Google Calendar integration |
| Location | GPS location lookup |
| Contact | Contact search and management |
| SMS | SMS sending |
| AppLauncher | Open installed applications |
| Settings | System settings access |
| File | File system operations |
| A2UI | Rich card rendering (weather, location, etc.) |

### UI
- **Jetpack Compose**: Modern declarative UI
- **A2UI Protocol**: Rich card rendering for skill results
- **Markdown Rendering**: Formatted text display
- **Voice Interaction**: STT (Android Speech Recognizer) + TTS

### Integration
- **Feishu Bot**: Real-time message handling via foreground service
- **Accessibility Bridge**: UI automation capabilities

## Project Structure

```
app/src/main/java/ai/openclaw/android/
├── MainActivity.kt              # Pure UI layer (binds to GatewayService)
├── GatewayService.kt            # Foreground service (single logic center)
├── GatewayManager.kt            # Core component manager (implements GatewayContract)
├── GatewayContract.kt           # Clean API contract for Activity
├── ConfigManager.kt             # Encrypted configuration storage
├── LogManager.kt                # Centralized logging
│
├── agent/                       # Agent conversation logic
│   ├── AgentSession.kt          # Message handling with tool calling
│   └── SessionEvent.kt          # Streaming event types
│
├── model/                       # LLM clients
│   ├── ModelClient.kt           # Interface
│   ├── BailianClient.kt         # 阿里百炼
│   ├── OpenAIClient.kt          # OpenAI
│   ├── AnthropicClient.kt       # Anthropic
│   └── LocalLLMClient.kt        # LiteRT-LM (Gemma 4)
│
├── data/                        # Data layer
│   ├── local/                   # Room database
│   │   ├── AppDatabase.kt       # Encrypted database (SQLCipher)
│   │   ├── MemoryDao.kt         # Memory CRUD
│   │   ├── MemoryVectorDao.kt   # Vector storage
│   │   ├── MemoryFtsDao.kt      # Full-text search
│   │   └── BM25Index.kt         # In-memory BM25 index
│   └── model/                   # Entity definitions
│
├── domain/                      # Business logic
│   ├── memory/                  # Memory subsystem
│   │   ├── MemoryManager.kt     # Storage + search
│   │   ├── HybridSearchEngine.kt# BM25 + vector fusion
│   │   ├── MemoryExtractor.kt   # Auto-extraction from conversations
│   │   ├── ColdStartManager.kt  # 72h gradual activation
│   │   └── MemoryMaintenanceWorker.kt
│   └── session/                 # Session management
│       ├── HybridSessionManager.kt
│       ├── SessionCompressor.kt
│       └── TokenCounter.kt
│
├── ml/                          # Machine learning
│   ├── TfLiteEmbeddingService.kt# MiniLM TFLite embedding
│   ├── SimpleEmbeddingService.kt# Fallback hash-based embedding
│   └── EmbeddingServiceFactory.kt
│
├── skill/                       # Skill system
│   ├── SkillManager.kt
│   └── builtin/                 # 12 built-in skills
│
├── ui/                          # Compose UI components
│   ├── SettingsScreen.kt
│   ├── A2UICards.kt
│   ├── MarkdownRenderer.kt
│   └── theme/
│
├── feishu/                      # Feishu integration
├── permission/                  # Runtime permission management
├── security/                    # SQLCipher + Keystore
├── voice/                       # STT + TTS
└── notification/                # Smart notification listener
```

## Requirements

- **Android SDK**: 35 (Android 16)
- **Kotlin**: 1.9.25
- **Gradle**: 8.9
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 35

## Build

```bash
# Debug build
./gradlew :app:assembleDebug

# Clean build
./gradlew clean :app:assembleDebug
```

### Signing

All debug builds use a unified keystore for consistent signatures across development environments:

- **Keystore**: `app/debug-unified.keystore`
- **Shared location**: `E:\Android\keystores\debug.keystore` (Windows) / `/mnt/e/Android/keystores/debug.keystore` (WSL2)
- **SHA-256**: `A3:48:0C:D7:EB:37:2A:76:48:60:72:D3:D2:F2:E0:5F:45:88:62:7A:21:CD:DD:62:61:54:60:5B:80:8E:B9:45`

## Key Design Decisions

1. **GatewayService as Single Source of Truth**: All core components (LLM, Agent, Skills, Memory) live in the foreground service. Activity is pure UI.
2. **GatewayContract Interface**: Clean API boundary between Activity and Service, enabling future migration to remote/AIDL service.
3. **Hybrid Memory Search**: BM25 (keyword) + Vector (semantic) + Recency scoring for relevant context retrieval.
4. **Cold Start Pattern**: First 72 hours use lightweight memory mode to avoid overwhelming new users.
5. **Encrypted Everything**: SQLCipher for database, EncryptedSharedPreferences for config, Android Keystore for keys.

## License

MIT
