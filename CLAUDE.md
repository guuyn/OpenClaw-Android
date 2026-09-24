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
User (text + optional images) → ChatScreen → AgentSession (conversation manager + tool loop)
                               → ModelClient (LLM provider, multimodal vision support)
                               → SkillManager → Skill → Tool execution
```

### Key Components

- **`AgentSession`** — Central conversation orchestrator. Manages message history, runs tool-calling loops (max 15 rounds, `MAX_TOOL_ROUNDS`), supports both sync (`handleMessage`) and streaming (`handleMessageStream`) modes. Tools come from two sources: accessibility tools and skill tools.

- **`ModelClient`** interface — Abstract LLM client with implementations for Bailian (阿里百炼), OpenAI, Anthropic, and LOCAL (on-device Gemma). All providers support streaming via `Flow<ChatEvent>`. **All providers support multimodal vision input** (images in messages), with provider-specific formats.

- **`SkillManager`** — Plugin registry. Skills are registered at init, each exposing `ToolDefinition`s. Tool names are namespaced as `{skillId}_{toolName}`. Skills requiring Android context receive it at registration.

- **`ConfigManager`** — Encrypted shared preferences for API keys and settings. Uses `androidx.security:security-crypto`.

- **`PermissionManager`** — Runtime permission handling including special permissions (overlay, accessibility, notification listener).

- **`GatewayService`** — Foreground service maintaining Feishu gateway connection.

- **`SmartNotificationListener`** — Notification listener service with ML-based classification. Exposes two **independent** states: `isNotificationListenerEnabled(context)` (reads `Settings.Secure.enabled_notification_listeners` = permission granted) and `isConnected` StateFlow (service actually bound by the system). On some OEM ROMs (Honor/MagicOS `iaware` blocks the bind) permission reads true while the service never starts — never infer one from the other.

### Multi-Agent System (`domain/agent/`, `agent/`)

Three agents defined in `assets/agents.json`: main (OpenClaw), coder, security. Each has its own model, system prompt, and tool whitelist.
- **`AgentConfigManager`** — Loads agent configs from `agents.json` (read-only from assets)
- **`AgentRouter`** — Routes messages to agents by @mention or keyword matching
- **`AgentSessionManager`** — Manages per-agent `AgentSession` lifecycle (LRU cache, max 3)

### Dynamic Skills (`skill/`)

LLM-generated skills with JS script execution, persisted in Room.
- **`DynamicSkill`** — Created via `fromJson()`, executes tools via `ScriptOrchestrator`
- **`DynamicSkillManager`** — Lifecycle management (30d auto-disable, 90d purge)
- **`GenerateSkillSkill`** — Provides `generate_skill` tool for LLM to create new skills
- **`ScriptSkill`** — Generic script execution skill
- **`ToolSecurityPolicy`** — Execution policy from security review: AUTO_EXECUTE / ASK_USER / DENY. `SecurityReview` derives it from the tool's risk level (`ToolRiskLevel`: READ/WRITE/DANGEROUS, defined in `SkillTool.kt`) plus the user's approval preference — DANGEROUS always asks. `SkillManager.executeTool` is the single execution entry for built-in + dynamic skills (permission gate → risk review → execute/approve/deny)
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

### Multimodal/Vision (`model/`)

Users can send images with messages (from gallery or camera, max 3 per message). Images are compressed (1200px max, JPEG 80%) and encoded as Base64.

- **`ImageContent`** — Data class: `base64` (String), `mediaType` (String, default `image/jpeg`), `description` (String?)
- **`ImageUtils`** — Image processing: `uriToBase64()`, `bitmapToBase64()`, `compressBitmap()`, `validateImages()`, `saveBitmapToTempUri()`
- **`Message.images`** — Optional `List<ImageContent>` on messages
- **Provider formats**:
  - **OpenAIClient** — OpenAI Vision format: `{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}` (also compatible with Qwen/DashScope)
  - **AnthropicClient** — Anthropic Vision format: `{"type": "image", "source": {"type": "base64", "media_type": "...", "data": "..."}}`
  - **LocalLLMClient** — LiteRT-LM SDK does NOT yet support multimodal Content API. Uses `buildVisionFallbackContent()` to append image descriptions as text. **TODO**: replace with `Content.image(bitmap)` when SDK supports it.
- **Permissions**: `CAMERA`, `READ_MEDIA_IMAGES`
- **FileProvider**: `ai.openclaw.android.fileprovider` via `res/xml/file_paths.xml`

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
- **Memory FTS** — `MemoryFtsDao`, `BM25Index` for full-text keyword search (BM25 tokenizer handles CJK bigrams; `isLetterOrDigit()` alone would swallow adjacent CJK chars — fixed in `c0d7ce0`)
- **Dynamic Skills** — `DynamicSkillDao` for LLM-generated skill persistence
- **Triggers** — `TriggerRuleDao`, `TriggerLogDao` — unified into Room by `71d7711`（`trigger_events_v2` 表及其实体已随 v8→v9 迁移删除）
- **Pre-fetch Cache** — `CachedDataDao` + `CachedDataEntity` (`cached_data` table): cache-first storage for high-frequency queries (weather etc.), 30min TTL, added in v7→v8 migration (`66014c6`, T005)

Entities are in `data/model/`, the Room database is `AppDatabase` (**schema version 9**, `exportSchema=true` → `app/schemas/`). `Converters` handle complex type serialization.

⚠️ **Migration chain is idempotent v1→v9** (see `AppDatabase.kt`): early schema versions were never exported and `fallbackToDestructiveMigration()` used to silently wipe the encrypted DB on upgrade. All `ALTER` migrations use `columnExists()` for idempotency; open failure falls back to explicit reporting (Bugly) + rebuild instead of silent data loss. **Never re-enable `fallbackToDestructiveMigration()`.**

### Domain Layer (`domain/`)

- **`domain/session/`** — `HybridSessionManager` manages conversation history with compression via `SessionCompressor`. `TokenCounter` estimates token usage. Receives optional `MemoryManager` for auto-extraction and memory injection into context.
- **`domain/agent/`** — Multi-agent routing and session management (see Multi-Agent System above)
- **`domain/memory/`** — `MemoryManager` handles memory CRUD. `HybridSearchEngine` combines BM25 (35%) + vector (55%) + recency (10%). `ColdStartManager` limits to lightweight mode for first 72h. `MemoryMaintenanceWorker` and `UserProfileBuilderWorker` are WorkManager-based periodic tasks. `DiffSyncManager` for cross-device sync.
- **`domain/model/`** — `SessionConfig` and shared domain models

### Pre-fetch Layer (`prefetch/`, T005)

Cache-first data layer: high-frequency queries (weather) are refreshed in background so most reads hit cache instead of the network.
- **`PrefetchService`** — Singleton. Weather cache read/write (`getCachedWeather`/`cacheWeather`), background refresh via open-meteo → wttr.in fallback, expired-data cleanup. 30min weather TTL. 20-city coordinate map.
- **`PrefetchWorker`** — CoroutineWorker, registered in `OpenClawApplication` as `PeriodicWorkRequest` (30min, name `prefetch_worker`). Reads city list from SharedPreferences `prefetch_cities` (default `["北京"]`), refreshes weather cache, cleans expired rows.
- **`WeatherSkill`** integration: reads `PrefetchService.instance.getCachedWeather()` first; falls back to live fetch on cache miss.
- Spec: `docs/specs/t005-prefetch-data-layer.md`

### Personal Center (`personalcenter/`)

Aggregated priority inbox: merges notifications/calendar/SMS/call-log into one importance-ranked list.
- **`PersonalCenterScreen`** / **`PersonalCenterViewModel`** — UI + aggregation pipeline: collect 4 sources → keyword filter (LLM semantic filter when available) → cross-source dedup → importance ranking → timed fallback refresh (60s poll re-reads permissions *and* re-fetches notifications).
- **Sources** (`sources/`) — `ItemSource` enum (NOTIFICATION/CALENDAR/SMS/CALL_LOG, icon + label + package-name inference); `NotificationSource`, `CalendarSource`, `SmsSource`, `CallLogSource` expose `Flow<List<CenterItem>>` via `callbackFlow` (requires respective runtime permissions). All four must **fetch on subscribe** (`trySend(fetch())`) — a purely passive `flow { collect }` cannot self-heal after a permission is granted later.
- **`CenterItem`** — unified model: importance 0.0~1.0, `dedupKey`, `mergedCount`, `priorityLevel` (urgent/today/reference), `actionType` (reply/act/info), `expiryTimestamp`.
- **Filters & scoring** — `ContentFilter` (keyword blacklist/whitelist), `SmartFilter` (LLM value judgment, callback-injected LLM, 1h per-source+title cache), `DeduplicationEngine` (cross-source merge), `ImportanceCalculator` (`baseScore × recencyWeight`), `PriorityClassifier` (LLM batch classification with rule-based fallback).
- **Permission state — two gotchas:**
  1. **Never derive permission flags from the source Flow's `.catch`.** `CalendarSource` / `SmsSource` swallow `SecurityException` internally and return `emptyList()`, so the exception never propagates and the flag keeps its initial value forever. Detect with `ContextCompat.checkSelfPermission()` — see `checkCalendarAndSmsPermissionStatus()`.
  2. **Notification listener permission is not a runtime permission.** There is no request API — the user must toggle it in Settings. Open via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, falling back to `ACTION_APPLICATION_DETAILS_SETTINGS` when the ROM has no receiver. Calendar/SMS have no direct settings action either — jump to app details and let the user open 「权限」.
- **UI permission affordances** — each stat chip shows an amber `!` + switches its tint when its source is unavailable (`denied`); tapping then goes to the grant path instead of expanding the section. `notifReady = isNotificationListenerEnabled && isConnected` — a granted-but-unbound listener must also count as unavailable. Empty list shows a specific hint (permission not granted / service not running) instead of a generic "暂无内容".
- **No persistence** — notifications live only in `SmartNotificationListener`'s in-memory StateFlow; process restart loses history.

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

Built-in skills: AppLauncher, Calendar, Camera, Contact, Device, File, FileXfer, GenerateSkill, Location, MultiSearch, Notification, Notify, Reminder, Screen, Script, Settings, Shell, SMS, Translate, Weather.

### UI Layer (`ui/`)

Jetpack Compose with Material3. Sci-Fi themed.
- **`A2UICards`** / **`A2UICardModels`** — Rich card rendering (weather, location, reminder, etc.) from `[A2UI]...[/A2UI]` markup. A2UI v2 structured card format (see `docs/specs/skill-cards-batch2.md`)
- **`MarkdownRenderer`** — Markdown to Compose rendering
- **`ModelDownloadScreen`** — On-device model management UI with SHA256 verification (ModelDownloadManager)
- **`ToolCallCard`** — Visualizes tool execution with status
- **`TypingIndicator`** / **`ShimmerEffect`** — Loading animations
- **`SettingsScreen`** — Settings UI
- **`theme/`** — Color palette, typography, theme configuration

### A2UI Protocol

Agent responses use the `[A2UI]...[/A2UI]` markup for rich UI rendering. Supported types: weather, location, reminder, translation, search, generic.

## Testing Requirements

**ALL tests MUST pass before committing code.** This is a hard requirement.

### Required Build & Test Checks

```bash
# 1. Unit tests — MUST pass
./gradlew :app:testDebugUnitTest :android_compose:testDebugUnitTest

# 2. Instrumented test compilation — MUST compile
./gradlew :app:compileDebugAndroidTestKotlin :android_compose:compileDebugUnitTestKotlin

# 3. Debug build — MUST succeed
./gradlew assembleDebug
```

### Test Coverage Areas

- **Unit tests** (`src/test/`): JVM-based using JUnit 4 + MockK. Cover serialization (`ModelModelsTest`), skill logic, session compression, memory system, embedding service, agent config, and domain logic.
- **Instrumented tests** (`src/androidTest/`): Require device/emulator. Cover AgentSession streaming, HybridSessionManager integration, DAO operations (incl. `AppDatabaseMigrationTest` v1→v9), UI components (EnergyBar, SettingsScreen), and ML embedding services.
- **A2UI compose module** (`android_compose/src/test/`): DataModelProcessor, NetworkTransport, A2UIService lifecycle, theme/color parsing, memory leak detection.

### Known Test Patterns & Pitfalls

- **Compose `Color`**: uses 0.0-1.0 range, NOT 0-255. `color.red.toInt()` returns 0 or 1.
- **`DynamicValue.FunctionValue`**: requires explicit type parameter `<Any>` in Kotlin 2.3.0.
- **`Flow` vs `StateFlow`**: `Flow` has no `.value` property; cast to `StateFlow` first.
- **`ModelClient.configure`**: signature includes `baseUrl` parameter — `configure(provider, apiKey, model, baseUrl)`.
- **`LocalLLMClient`**: constructor takes `Context` directly, no `getInstance()` singleton.
- **`LocalLLMClient` 上下文预算**: `maxNumTokens` 是「输入 + 输出」共用的 KV-cache 上限，必须先给生成预留。工具 schema（端侧常注入 50+ 个）和系统提示都要计入预算，否则必然超限 —— 预算分配见 `planContext()`。
- **`LocalLLMClient` 会话复用**: 主会话复用同一个 `Conversation`（持有 KV cache），只发新增消息；每轮 `createConversation()` + 重放全部历史 = 全量 prefill，是端侧卡顿的主因，不要改回去。历史被裁剪 / 换会话 / system·tools 变化会自动重建。
- **端侧 CPU 线程**: `Backend.CPU(numOfThreads)` 显式限线程（核数 − 2，2..6）；不指定时 native 会按核心数开满，推理期间整机卡顿。
- **端侧上下文窗口 ≠ 模型宣传值**: Gemma 4 E2B/E4B 官方卡标称 **128K**，但 `.litertlm` 包的物理 KV-cache 由导出时的 `cache_length` 决定，公开版本（含 litert-community 官方包）普遍是 **4096**。`EngineConfig.maxNumTokens` 只是「申请值」，超过包内容量会 init 失败或 prefill 时炸。因此 `LocalLLMClient` 按「首选值 → 4096 → 2048」逐级降级，实际生效值存在 `effectiveMaxNumTokens`，`getContextWindowTokens()` 返回它。SDK 无 API 可查询包内 cache_length（javap 确认），只能靠初始化试。**不要**再写死窗口常量。
- **`MessageDao`**: use `getMessagesBySessionIdWithLimit(sessionId, limit, offset)`, not `getBySession`.
- **`sendMessage`** signature is now `(String, List<ImageContent>)` — all call sites must pass both parameters.
- **`ChatScreen`** `sendMessage` callback uses `(String, List<ImageContent>)` — update tests with `{ _, _ -> }`.

### Adding New Tests

- Unit tests go in `src/test/java/` — same package structure as main code.
- Instrumented tests go in `src/androidTest/java/` — use `AndroidJUnit4` runner.
- Use explicit type parameters for generic types to avoid Kotlin 2.3.0 inference issues.
- Never use `assertTrue/assertFalse` on `Any?` results; cast to expected type first.
