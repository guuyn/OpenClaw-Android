# OpenClaw-Android 工程分析报告

> 分析日期: 2026-04-09
> 分析范围: 全工程源码、构建配置、测试、架构设计

---

## 1. 项目概览

OpenClaw-Android 是一个 AI Agent Android 应用，支持多 LLM 提供商（云端+本地）、技能插件系统、语音交互、向量记忆系统，通过 A2UI 协议实现富 UI 渲染。

| 指标 | 值 |
|------|------|
| Kotlin 源文件 | 79 个 |
| 总代码行数 | 11,247 行 |
| 技能数量 | 11 个内置技能 |
| LLM 提供商 | 4 个（Bailian、OpenAI、Anthropic、Local） |
| 单元测试 | 11 个 |
| 仪器测试 | 6 个 |

---

## 2. 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.0 |
| 编译目标 | Java 17 | compileSdk 35, minSdk 29 |
| UI 框架 | Jetpack Compose + Material3 | BOM 2025.03.00 |
| 构建 | AGP | 8.7.3 |
| 网络 | OkHttp + SSE | 4.12.0 |
| 序列化 | Kotlin Serialization | 1.8.0 |
| 数据库 | Room + KSP | 2.7.2 |
| 本地推理 | LiteRT-LM (Gemma 4 E4B) | 0.10.0 |
| ML 推理 | TensorFlow Lite | 2.16.1 |
| 安全 | androidx.security-crypto | 1.1.0-alpha06 |
| 协程 | kotlinx-coroutines | 1.10.1 |

---

## 3. 架构设计

### 3.1 整体数据流

```
User Input (Text / Voice)
    |
    v
MainActivity (Compose UI, Tab Navigation)
    |
    v
AgentSession (对话编排 + 工具循环, max 5 rounds)
    |
    +---> ModelClient (LLM 调用, 同步/流式)
    |       |-- BailianClient  (阿里百炼, OpenAI 兼容)
    |       |-- OpenAIClient   (OpenAI Chat Completions)
    |       |-- AnthropicClient (Anthropic Messages API)
    |       |-- LocalLLMClient  (本地 Gemma 4 E4B, ~18-22 tok/s)
    |
    +---> SkillManager --> Skill.executeTool()
    |       |-- 11 个内置技能
    |       |-- 工具命名: {skillId}_{toolName}
    |
    +---> HybridSessionManager (消息持久化 + 压缩)
    |       |-- SessionCompressor (LLM 摘要压缩)
    |       |-- MemoryManager (向量记忆)
    |
    +---> A2UI Renderer (富 UI 渲染)
```

### 3.2 分层架构

```
┌─────────────────────────────────────────────┐
│  Presentation Layer                         │
│  MainActivity.kt, ChatScreen.kt,            │
│  NotificationScreen.kt, ui/theme/           │
├─────────────────────────────────────────────┤
│  Agent Layer                                │
│  AgentSession.kt, SkillManager.kt,          │
│  Skill implementations (11个)               │
├─────────────────────────────────────────────┤
│  Domain Layer                               │
│  domain/session/ - HybridSessionManager,    │
│                    SessionCompressor         │
│  domain/memory/ - MemoryManager,            │
│                    MemoryExtractor           │
│  model/ - ModelClient 接口 + 4个实现        │
├─────────────────────────────────────────────┤
│  Data Layer                                 │
│  data/local/ - AppDatabase, DAOs            │
│  data/model/ - Entity 定义                  │
│  ConfigManager, PermissionManager           │
├─────────────────────────────────────────────┤
│  Infrastructure                             │
│  ml/ - TfLiteEmbeddingService, BertTokenizer│
│  voice/ - STT/TTS engines                   │
│  feishu/ - 飞书网关集成                     │
│  notification/ - 通知监听与分类              │
└─────────────────────────────────────────────┘
```

### 3.3 依赖注入

当前采用手动依赖注入，核心链路在 `MainScreen` Composable 中构建：

```
AppDatabase → TfLiteEmbeddingService → MemoryManager → HybridSessionManager → AgentSession
```

---

## 4. 模块详细分析

### 4.1 核心组件

| 组件 | 文件 | 行数 | 职责 |
|------|------|------|------|
| MainActivity | MainActivity.kt | 963 | 入口 Activity，Tab 导航，权限管理，组件初始化 |
| ChatScreen | ChatScreen.kt | 663 | 聊天 UI，A2UI 协议解析与渲染，语音集成 |
| AgentSession | AgentSession.kt | 391 | 对话编排，工具循环（max 5轮），流式处理 |
| HybridSessionManager | domain/session/ | 341 | 会话持久化，压缩，记忆触发 |
| LocalLLMClient | model/ | 445 | 本地 Gemma 4 E4B 推理，工具调用支持 |

### 4.2 技能系统

技能实现 `Skill` 接口，在 `SkillManager.loadBuiltinSkills()` 中注册。

| 技能 | 工具 | 依赖 |
|------|------|------|
| WeatherSkill | `get_weather` | wttr.in API |
| TranslateSkill | `translate` | MyMemory API |
| MultiSearchSkill | `search` | SearXNG 实例 |
| ContactSkill | `search_contacts`, `get_contact`, `call_contact` | Contacts Provider |
| SMSSkill | `send_sms`, `read_sms`, `get_unread_sms` | SmsManager |
| CalendarSkill | `list_events`, `add_event` | Calendar Provider |
| LocationSkill | `get_location`, `get_address`, `search_places` | GPS + Nominatim |
| ReminderSkill | `set_reminder`, `list_reminders`, `cancel_reminder` | AlarmManager |
| AppLauncherSkill | `open`, `list_apps` | PackageManager |
| SettingsSkill | `open_settings`, `toggle_bluetooth`, `volume` | 系统服务 |

### 4.3 LLM 客户端

| 客户端 | API 协议 | 流式支持 | 工具调用 |
|--------|---------|---------|---------|
| BailianClient | OpenAI 兼容 | SSE | 支持 |
| OpenAIClient | OpenAI Chat Completions | SSE | 支持 |
| AnthropicClient | Anthropic Messages | SSE | 支持 |
| LocalLLMClient | LiteRT-LM 本地推理 | Flow | 支持 |

### 4.4 记忆系统

```
MemoryManager (CRUD + 向量搜索 + 遗忘曲线 + 去重)
    ├── EmbeddingService (抽象接口, LRU 缓存 100 条)
    │   ├── TfLiteEmbeddingService (MiniLM-L6-v2, 384维)
    │   └── SimpleEmbeddingService (SHA-256 词袋, 兜底)
    ├── MemoryExtractorInterface
    │   ├── LlmMemoryExtractor (本地 LLM 提取)
    │   └── FallbackMemoryExtractor (关键词启发式)
    └── MemoryDao + MemoryVectorDao (Room 持久化)
```

触发机制：
- 关键词触发：检测 "记住这个"
- 空闲延迟：30秒无操作后自动提取

可靠性保障：
- `Mutex` 互斥锁：保证 LocalLLMClient 同一时刻只有一个 Conversation，避免并发冲突
- `ensureEngineReady()`：记忆提取前自动恢复 ERROR 状态的引擎
- `truncateMessages()`：截断历史消息防止 token 超限
- `LogManager` 全链路日志：Settings 页面可查看记忆提取完整日志

性能优化（P0 已实现）：
- **遗忘曲线**：`cleanup()` 基于 `0.4×recency + 0.4×frequency + 0.2×priority` 计算遗忘分数，自动清理低价值记忆，始终保留≥20条
- **搜索范围限制**：默认搜索最近90天记忆，最多200条向量；无结果时兜底全量搜索
- **LRU 嵌入缓存**：TfLite/Simple 两个 EmbeddingService 均添加 `LruCache<String, FloatArray>(100)`，避免重复计算
- **记忆去重**：`store()` 时与近期记忆比较余弦相似度，>0.95 跳过存储

### 4.5 语音模块

```
VoiceInteractionManager (统一语音接口)
    ├── VoiceSession (状态机: Idle → Listening → Processing → Speaking)
    ├── STT: AndroidSpeechRecognizer (原生 Android)
    └── TTS: AndroidTTSEngine (原生 Android)
```

### 4.6 A2UI 渲染

A2UI 协议使用 `[A2UI]...[/A2UI]` 标记，支持消息类型：createSurface、updateComponents、updateDataModel、deleteSurface。渲染器为 `:android_compose` 子模块。

富文本保障机制：
- 系统提示词：MANDATORY 标记 + 每种工具类型的 few-shot 示例
- `generateA2UIFromResult()`：自动将工具结果（"key: value" 格式）转为 `[A2UI]` JSON
- `ensureA2UIInResponse()` 兜底：LLM 不输出 A2UI 时自动注入，确保用户始终看到富文本卡片

---

## 5. 数据层

### 5.1 Room 数据库

`AppDatabase` (单例, 线程安全) 包含三组 DAO：

| 领域 | DAO | Entity |
|------|-----|--------|
| 会话 | SessionDao | SessionEntity |
| 消息 | MessageDao | MessageEntity, SummaryEntity |
| 记忆 | MemoryDao, MemoryVectorDao | MemoryEntity, MemoryVectorEntity |

### 5.2 配置管理

`ConfigManager` 使用双重存储策略：
- EncryptedSharedPreferences：API 密钥等敏感数据
- 普通 SharedPreferences：非敏感配置

---

## 6. 测试覆盖

### 6.1 单元测试 (src/test/, 11个)

| 测试类 | 覆盖模块 |
|--------|---------|
| ModelModelsTest | 序列化模型 |
| TokenCounterTest | Token 计数 |
| FeishuClientTest | 飞书客户端 |
| SkillManagerTest | 技能管理器 |
| MultiSearchSkillTest | 搜索技能 |
| MemorySystemTest | 记忆系统 |
| SessionCompressorTest | 会话压缩 |
| WeatherSkillTest | 天气技能 |
| VectorSearchTest | 向量搜索 |
| SimpleEmbeddingServiceTest | 简单嵌入服务 |
| MockEmbeddingService | 测试 Mock |

### 6.2 仪器测试 (src/androidTest/, 6个)

| 测试类 | 覆盖模块 |
|--------|---------|
| AgentSessionTest | Agent 流式行为 |
| SessionDaoTest | 数据库 DAO |
| SessionIntegrationTest | 会话集成 |
| MemoryDaoTest | 记忆 DAO |
| HybridSessionManagerTest | 会话管理器 |
| TfLiteEmbeddingServiceTest | TF Lite 嵌入 |

### 6.3 覆盖率评估

- **已覆盖**: 数据序列化、Token 计数、技能逻辑、记忆 CRUD、向量搜索、会话压缩
- **未覆盖**: LLM 客户端（需 mock HTTP）、UI 层、权限管理、语音交互、通知分类、飞书集成

---

## 7. 已知问题与风险

### 7.1 严重问题 (P0)

| # | 问题 | 影响 | 状态 |
|---|------|------|------|
| 1 | `rememberA2UIRenderer()` 使用 `rememberSaveable` 导致 `SavedRendererState` 无法序列化 | 启动崩溃 | **已修复** |
| 2 | `TfLiteEmbeddingService` 找不到 `vocab.txt` 资产文件 | 向量嵌入不可用 | 待修复 |
| 3 | `LiteRT-LM NativeLibraryLoader` 找不到 native 实现 | 本地推理不可用 | **已解决** (maxNumTokens 调整为 16384) |
| 4 | LiteRT-LM 单 Session 并发冲突 | 记忆提取与主聊天互斥 crash | **已修复** (Mutex) |
| 5 | A2UI 渲染死循环 (surface ID 不匹配 + 消息合并) | 卡片始终转圈 | **已修复** |
| 6 | Token 超限导致引擎 crash | 后续记忆提取全部失败 | **已修复** (truncateMessages) |

### 7.2 架构问题 (P1)

| # | 问题 | 建议 |
|---|------|------|
| 1 | `MainActivity.kt` 963行，职责过多（UI + 初始化 + 权限） | 拆分为多个 Composable + ViewModel |
| 2 | `ChatScreen.kt` 663行，混合 UI 和 A2UI 解析逻辑 | 抽取 A2UI 解析为独立类 |
| 3 | 手动依赖注入，初始化链路复杂 | 考虑 Hilt/Koin |
| 4 | 无数据库迁移策略（`fallbackToDestructiveMigration`） | 实现正式 Migration |
| 5 | ~~向量搜索为暴力遍历，O(n) 复杂度~~ | **已优化**：90天时间窗口 + 200条上限 + 兜底全量 |

### 7.3 安全问题 (P2)

| # | 问题 | 建议 |
|---|------|------|
| 1 | API 密钥加密存储但默认密钥硬编码 | 移除硬编码默认值，强制用户配置 |
| 2 | 无障碍服务权限范围大 | 添加使用场景说明和审计日志 |
| 3 | 外部 API 调用无速率限制 | 添加请求节流 |

### 7.4 性能问题 (P2)

| # | 问题 | 建议 |
|---|------|------|
| 1 | TfLite 模型同步加载 | 改为异步 + 进度提示 |
| 2 | ~~无嵌入缓存机制~~ | **已实现**：LRU 缓存 100 条 |
| 3 | VoiceSession 每次重建 SpeechRecognizer | 复用实例 |

---

## 8. 构建配置

### 8.1 构建变体

| 变体 | 代码压缩 | 资源压缩 | ProGuard | 签名 |
|------|---------|---------|----------|------|
| Debug | 否 | 否 | 否 | Debug |
| Release | 是 | 是 | 是 | local.properties 配置 |

### 8.2 NDK

- NDK 版本: 27.0.12077973
- 支持 ABI: arm64-v8a, armeabi-v7a
- 不包含 x86/x86_64（不支持模拟器）

---

## 9. 文件统计

### 9.1 按模块分布

```
Root (8 files)        ~2,500 行  MainActivity + ChatScreen 占绝大部分
skill/ (15 files)     ~1,800 行  11 技能 + 框架
model/ (6 files)      ~1,200 行  4 LLM 客户端 + 接口
domain/ (10 files)    ~900 行    会话 + 记忆管理
data/ (14 files)      ~600 行    Room 数据库
voice/ (6 files)      ~500 行    STT/TTS
ml/ (4 files)         ~400 行    嵌入服务
notification/ (4)     ~350 行    通知处理
feishu/ (3)           ~300 行    飞书集成
其他                  ~2,700 行
```

### 9.2 大文件 TOP 5

| 文件 | 行数 | 建议 |
|------|------|------|
| MainActivity.kt | 963 | 拆分 |
| ChatScreen.kt | 663 | 拆分 |
| LocalLLMClient.kt | 445 | 可接受 |
| AgentSession.kt | 391 | 可接受 |
| HybridSessionManager.kt | 341 | 可接受 |

---

## 10. 改进建议优先级

### 短期（P0 - 稳定性）

1. **修复 vocab.txt 缺失** — embedding 服务无法初始化
2. ~~**排查 LiteRT-LM native 库加载失败**~~ — **已解决** (maxNumTokens 调整)
3. **添加数据库 Migration** — 避免升级丢数据
4. ~~**记忆系统 P0 优化**~~ — **已完成** (遗忘曲线、搜索限制、LRU缓存、去重)

### 中期（P1 - 架构）

4. **拆分 MainActivity** — 提取 ViewModel + 子 Composable
5. **拆分 ChatScreen** — A2UI 解析逻辑独立
6. **引入 DI 框架** — 简化依赖管理
7. **补充测试覆盖** — LLM 客户端 mock 测试、UI 测试

### 长期（P2 - 质量）

8. ~~**向量搜索优化**~~ — **已优化**：时间窗口 + 条数上限 + 兜底全量；ANN 索引待 sqlite-vec 集成
9. **API 安全加固** — 移除硬编码密钥、添加速率限制
10. ~~**性能优化**~~ — 嵌入缓存已实现；异步模型加载、语音组件复用待做

---

## 附录：项目目录结构

```
app/src/main/java/ai/openclaw/android/
├── ChatScreen.kt
├── ConfigManager.kt
├── GatewayManager.kt
├── GatewayService.kt
├── LogManager.kt
├── MainActivity.kt
├── MyAccessibilityService.kt
├── OpenClawApplication.kt
├── accessibility/
│   └── AccessibilityBridge.kt
├── agent/
│   └── AgentSession.kt
├── data/
│   ├── local/ (AppDatabase, DAOs, Converters)
│   └── model/ (Entities, Enums)
├── domain/
│   ├── memory/ (MemoryManager, EmbeddingService, Extractors)
│   ├── model/ (SessionConfig)
│   └── session/ (HybridSessionManager, SessionCompressor, TokenCounter)
├── feishu/ (FeishuClient, Models, OkHttp impl)
├── ml/ (TfLite, Simple, BertTokenizer, Factory)
├── model/ (ModelClient interface + 4 implementations)
├── notification/ (Classifier, Listener, Screen)
├── permission/
│   └── PermissionManager.kt
├── skill/
│   ├── Skill.kt, SkillContext.kt, SkillManager.kt, SkillTool.kt
│   └── builtin/ (11 skills)
├── ui/theme/
├── util/ (Prompt templates)
└── voice/ (STT, TTS, VoiceSession, Manager)
```
