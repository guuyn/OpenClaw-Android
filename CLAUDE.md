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

- **Kotlin 2.3.0**, Java 17 target, compileSdk 35, minSdk 29
- **Jetpack Compose** with Material3 for all UI
- **OkHttp 4.12** with SSE for streaming LLM responses
- **Kotlin Serialization** (not Gson/Moshi) for JSON
- **Room + KSP** for local database
- **LiteRT-LM** for on-device Gemma 4 E4B inference
- **TensorFlow Lite** for notification classification

## Architecture

Single-module Android app under package `ai.openclaw.android`.

### Core Data Flow

```
User → MainActivity (Compose UI)
     → AgentSession (conversation manager + tool loop)
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

### Data Layer (`data/`)

Room database (singleton via `AppDatabase.getInstance(context)`) with three domains:
- **Sessions** — `SessionDao`, `MessageDao`, `SummaryDao` for conversation persistence
- **Memory** — `MemoryDao`, `MemoryVectorDao` for storing memories and their vector embeddings

Entities are in `data/model/`, the Room database is `AppDatabase`.

### Domain Layer (`domain/`)

- **`domain/session/`** — `HybridSessionManager` manages conversation history with compression via `SessionCompressor`. `TokenCounter` estimates token usage. Receives optional `MemoryManager` for auto-extraction and memory injection into context.
- **`domain/memory/`** — `MemoryManager` handles memory CRUD. `MemoryExtractorInterface` is implemented by `LlmMemoryExtractor` (uses on-device LLM) and `FallbackMemoryExtractor` (keyword-based heuristic). `EmbeddingService` is the abstract interface; `TfLiteEmbeddingService` (in `ml/`) implements it with MiniLM-L6-v2 for vector embeddings.

### Integration Flow

```
MainScreen creates: AppDatabase → TfLiteEmbeddingService → MemoryManager → HybridSessionManager → AgentSession
```

`AgentSession` holds references to `HybridSessionManager` (for message persistence) and a memory context provider (for injecting important memories into system prompt). Messages are persisted after each conversation turn. Memory extraction triggers on "记住这个" keywords and after 30s idle delay.

### Skill System

Skills live in `skill/builtin/` and implement the `Skill` interface. Each skill:
1. Declares tools with `SkillParam` definitions
2. Gets initialized with a `SkillContext`
3. Handles tool execution via `executeTool(toolName, params)`

To add a new skill: create a class implementing `Skill`, register it in `SkillManager.loadBuiltinSkills()`.

### A2UI Protocol

Agent responses use the `[A2UI]...[/A2UI]` markup for rich UI rendering. Supported types: weather, location, reminder, translation, search, generic.

## Testing

- **Unit tests** (`src/test/`): JVM-based using MockK. Tests cover serialization (`ModelModelsTest`), skill logic, session compression, memory system, and embedding service.
- **Instrumented tests** (`src/androidTest/`): Require device/emulator, use fake `ModelClient` implementations with `mockito-kotlin` (e.g., `AgentSessionTest` for streaming behavior).
