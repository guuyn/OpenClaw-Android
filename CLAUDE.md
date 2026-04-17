# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Requires **JBR (JetBrains Runtime) Java 21** from Android Studio. System JDK may be too old.

```bash
# Set JAVA_HOME before building (Windows / Git Bash)
export JAVA_HOME="E:/Program Files/Android/Android Studio/jbr"

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests (JVM)
./gradlew test

# Run a single unit test class
./gradlew test --tests "ai.openclaw.android.model.ModelModelsTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run a single instrumented test
./gradlew connectedAndroidTest --tests "ai.openclaw.android.agent.AgentSessionTest"

# Install debug APK to connected device
./gradlew installDebug

# Clean build
./gradlew clean
```

If `./gradlew` fails to find `GradleWrapperMain`, use this instead:
```bash
JAVA_HOME="E:/Program Files/Android/Android Studio/jbr" "$JAVA_HOME/bin/java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug
```

## Tech Stack

- **Kotlin 2.3.0**, Java 17 target, compileSdk 36, minSdk 29
- **Jetpack Compose** with Material3 for all UI
- **OkHttp 4.12** with SSE for streaming LLM responses
- **Kotlin Serialization** (not Gson/Moshi) for JSON
- **Room + KSP** for local database
- **Koin** for dependency injection
- **WorkManager** for periodic background tasks
- **LiteRT 2.1.3** (successor to TF Lite) for notification classification + embeddings
- **LiteRT-LM** for on-device Gemma 4 E4B inference
- **ONNX Runtime** as alternative inference backend
- **Rhino JS Engine** for dynamic skill script execution

## Architecture

Single-module Android app under package `ai.openclaw.android`.

### Core Data Flow

```
User → ChatViewModel → AgentSession (conversation manager + tool loop)
     → ModelClient (LLM provider)
     → SkillManager → Skill → Tool execution
```

### Key Components

- **`AgentSession`** — Central conversation orchestrator. Manages message history, runs tool-calling loops (max 5 rounds), supports both sync (`handleMessage`) and streaming (`handleMessageStream`) modes. Tools come from two sources: accessibility tools and skill tools.

- **`ModelClient`** interface — Abstract LLM client with implementations for Bailian (阿里百炼), OpenAI, Anthropic, and LOCAL (on-device Gemma). All providers support streaming via `Flow<ChatEvent>`.

- **`SkillManager`** — Plugin registry. Skills are registered at init, each exposing `ToolDefinition`s. Tool names are namespaced as `{skillId}_{toolName}`. Skills requiring Android context receive it at registration.

- **`ConfigManager`** — Encrypted shared preferences for API keys and settings. Uses `androidx.security:security-crypto`.

- **`PermissionManager`** — Runtime permission handling including special permissions (overlay, accessibility, notification listener).

- **`GatewayService`** — Foreground service maintaining Feishu gateway connection.

- **`SmartNotificationListener`** — Notification listener service with ML-based classification.

### Multi-Agent System (`domain/agent/`, `agent/`, `config/`)

Three agents defined in `assets/agents.json`: main (OpenClaw), coder, security. Each has its own model, system prompt, and tool whitelist.
- **`AgentConfigManager`** — Loads and persists agent configs from `agents.json`
- **`AgentRouter`** — Routes messages to agents by @mention or keyword matching
- **`AgentSessionManager`** — Manages per-agent `AgentSession` lifecycle
- **`AgentRegistry`** — Runtime registry of active agents and their sessions
- **`AgentManagementSkill`** — Exposes agent management as a skill (list, create, delete)

### Dynamic Skills (`skill/`)

LLM-generated skills with JS script execution, persisted in Room.
- **`DynamicSkill`** — Created via `fromJson()`, executes tools via `ScriptOrchestrator`
- **`DynamicSkillManager`** — Lifecycle management (30d auto-disable, 90d purge)
- **`GenerateSkillSkill`** — Provides `generate_skill` tool for LLM to create new skills
- **`ScriptSkill`** — Generic script execution skill
- **`ToolSecurityPolicy`** — Security review before execution: AUTO_EXECUTE / ASK_USER / DENY
- **`UserPreferenceManager`** — Persists per-tool approval decisions

### Trigger/Event System (`trigger/`)

Event-driven automation with rule matching, debouncing, and deduplication.
- **`EventBus`** — Singleton. Publishes events, matches against rules, executes actions. LRU dedup (5min TTL), per-rule cooldown.
- **`CronScheduler`** — Cron-based scheduled event publishing
- **`ActionExecutor`** — Executes actions (LLM chat, notification, etc.) for matched rules
- **`TriggerRuleSkill`** — Skill for managing trigger rules via conversation
- **Models** — `TriggerEvent`, `TriggerRule` (with filters: package, keyword, time, category), `TriggerLog`
- **DAOs** — `TriggerRuleDao`, `TriggerLogDao` in `trigger/dao/`

### Voice System (`voice/`)

- **`VoiceInteractionManager`** — Unified entry point. Manages STT/TTS lifecycle. Session flow: Listening → Processing → Speaking → Idle.
- **`VoiceSession`** — State machine for voice session
- **`stt/`** — `AndroidSpeechRecognizer` implementing `SpeechToTextEngine`
- **`tts/`** — `AndroidTTSEngine` implementing `TextToSpeechEngine`

### Feishu Integration (`feishu/`)

- **`FeishuClient`** — Interface for Feishu API
- **`OkHttpFeishuClient`** — OkHttp-based implementation
- **`FeishuModels`** — Data models for Feishu messages

### Security (`security/`)

- **`AuditLogger`** — Tamper-evident audit log with SHA-256 hash chain
- **`SecurityKeyManager`** — Encryption key management

### Data Layer (`data/`)

Room database (singleton via `AppDatabase.getInstance(context)`, managed by Koin) with domains:
- **Sessions** — `SessionDao`, `MessageDao`, `SummaryDao` for conversation persistence
- **Memory** — `MemoryDao`, `MemoryVectorDao` for storing memories and their vector embeddings
- **Memory FTS** — `MemoryFtsDao`, `BM25Index` for full-text keyword search
- **Dynamic Skills** — `DynamicSkillDao` for LLM-generated skill persistence
- **Triggers** — `TriggerRuleDao`, `TriggerLogDao` for event rules and logs

Entities are in `data/model/`, the Room database is `AppDatabase`. `Converters` handle complex type serialization.

### Domain Layer (`domain/`)

- **`domain/session/`** — `HybridSessionManager` manages conversation history with compression via `SessionCompressor`. `TokenCounter` estimates token usage. Receives optional `MemoryManager` for auto-extraction and memory injection into context.
- **`domain/agent/`** — Multi-agent routing and session management (see Multi-Agent System above)
- **`domain/memory/`** — `MemoryManager` handles memory CRUD. `HybridSearchEngine` combines BM25 (35%) + vector (55%) + recency (10%). `ColdStartManager` limits to lightweight mode for first 72h. `MemoryMaintenanceWorker` and `UserProfileBuilderWorker` are WorkManager-based periodic tasks. `DiffSyncManager` for cross-device sync.
- **`domain/model/`** — `SessionConfig` and shared domain models

### Dependency Injection (`di/`)

**Koin** module in `AppModule.kt`. Singletons: `SecurityKeyManager`, `AppDatabase`, `SkillManager`, `PermissionManager`, `EmbeddingService`, `BM25Index`, `HybridSearchEngine`, `LogManager`, `ColdStartManager`. ViewModels: `ChatViewModel`, `SettingsViewModel`.

### ViewModel Layer (`viewmodel/`)

- **`ChatViewModel`** — Manages chat state, agent interactions, message flow
- **`SettingsViewModel`** — Manages settings state

### Integration Flow

```
Koin DI creates all singletons at app start. ChatViewModel receives SkillManager, AppDatabase, EmbeddingService, HybridSearchEngine.
ChatViewModel → AgentSession → ModelClient (LLM) → SkillManager → Skill → Tool execution
```

`AgentSession` holds references to `HybridSessionManager` (for message persistence) and a memory context provider (for injecting important memories into system prompt). Messages are persisted after each conversation turn. Memory extraction triggers on "记住这个" keywords and after 30s idle delay.

### Skill System

Skills live in `skill/builtin/` and implement the `Skill` interface. Static skills (coded) and dynamic skills (LLM-generated JS). Each skill:
1. Declares tools with `SkillParam` definitions
2. Gets initialized with a `SkillContext`
3. Handles tool execution via `executeTool(toolName, params)`

To add a new skill: create a class implementing `Skill`, register it in `SkillManager.loadBuiltinSkills()`.

Built-in skills: AgentManagement, AppLauncher, Calendar, Contact, File, GenerateSkill, Location, MultiSearch, Notification, Reminder, Script, Settings, Translate, Weather.

### UI Layer (`ui/`)

Jetpack Compose with Material3. Sci-Fi themed.
- **`A2UICards`** / **`A2UICardModels`** — Rich card rendering (weather, location, reminder, etc.) from `[A2UI]...[/A2UI]` markup
- **`MarkdownRenderer`** — Markdown to Compose rendering
- **`MessageBubble`** — Chat message UI component
- **`ToolCallCard`** — Visualizes tool execution with status
- **`TypingIndicator`** / **`ShimmerEffect`** — Loading animations
- **`SettingsScreen`** — Settings UI
- **`theme/`** — Color palette, typography, theme configuration

### A2UI Protocol

Agent responses use the `[A2UI]...[/A2UI]` markup for rich UI rendering. Supported types: weather, location, reminder, translation, search, generic.

## Testing

- **Unit tests** (`src/test/`): JVM-based using MockK. Tests cover serialization (`ModelModelsTest`), skill logic, session compression, memory system, and embedding service.
- **Instrumented tests** (`src/androidTest/`): Require device/emulator, use fake `ModelClient` implementations with `mockito-kotlin` (e.g., `AgentSessionTest` for streaming behavior).
