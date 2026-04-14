# OpenClaw-Android 全局项目文档

> **最后更新**: 2026-04-13  
> **项目路径**: `/home/guuya/OpenClaw-Android-build/`  
> **当前状态**: 功能完善阶段（核心功能可用，持续优化中）

---

## 📖 目录

1. [项目概览](#1-项目概览)
2. [技术栈](#2-技术栈)
3. [架构设计](#3-架构设计)
4. [核心模块](#4-核心模块)
5. [技能系统](#5-技能系统)
6. [记忆系统](#6-记忆系统)
7. [LLM 接入](#7-llm-接入)
8. [数据层](#8-数据层)
9. [语音交互](#9-语音交互)
10. [A2UI 协议](#10-a2ui-协议)
11. [交互设计](#11-交互设计)
12. [ScriptEngine](#12-scriptengine)
13. [Gateway Service 架构](#13-gateway-service-架构)
14. [构建与测试](#14-构建与测试)
15. [安全](#15-安全)
16. [待完成项](#16-待完成项)
17. [发展规划](#17-发展规划)
18. [文档索引](#18-文档索引)

---

## 1. 项目概览

**OpenClaw-Android** 是一个 AI Agent Android 应用，将 LLM 智能与设备级自动化能力（Accessibility Service）结合，支持多 LLM 提供商（云端+本地）、技能插件系统、语音交互、持久记忆系统，通过 A2UI 协议实现富 UI 渲染。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| **多轮对话** | AgentSession 工具调用循环（max 5轮），流式响应 |
| **本地推理** | 设备端运行 Gemma 4 E4B（~18-22 tok/s），完全离线 |
| **技能系统** | 11个内置技能（天气/搜索/翻译/提醒/定位/通讯录/短信/日历/应用启动/系统设置） |
| **持久记忆** | Room + 向量检索 + 遗忘曲线 + 自动提取 |
| **设备控制** | Accessibility Service 实现屏幕读取、点击、输入、手势 |
| **语音交互** | Android 原生 STT/TTS，全语音对话 |
| **A2UI 富渲染** | 天气卡片、定位地图、提醒列表等富 UI |
| **飞书集成** | 飞书消息收发（骨架代码阶段） |

### 1.2 关键指标

| 指标 | 数值 |
|------|------|
| Kotlin 源文件 | 79 个 |
| 总代码行数 | ~11,247 行 |
| 内置技能 | 11 个 |
| LLM 提供商 | 4 个（Bailian / OpenAI / Anthropic / Local） |
| 单元测试 | 11 个 |
| 仪器测试 | 6 个 |
| APK 大小 | 46MB（Debug） / ~160MB（含模型） |

### 1.3 竞品对比

| 能力 | OpenClaw | ChatGPT | Claude | Gemini |
|------|----------|---------|--------|--------|
| **多模型支持** | ✅ 4 种 | GPT系列 | Claude | Gemini |
| **本地推理** | ✅ Gemma 4 E4B | ❌ | ❌ | ✅ Nano |
| **设备控制** | ✅ Accessibility | ❌ | ❌ | ✅ Pixel |
| **通知管理** | ✅ 监听+分类 | ❌ | ❌ | ❌ |
| **离线能力** | ✅ 本地模型 | ❌ | ❌ | ✅ Nano |
| **多模态输入** | ❌ | ✅ | ✅ | ✅ |

**差异化优势**：
1. 设备级自动化（AI 驱动的设备操控，竞品 App 均不具备）
2. 本地 LLM 推理（隐私敏感场景优势）
3. 开放技能架构（插件式扩展）
4. 通知智能处理（ML 分类 + LLM 理解）
5. 多模型切换灵活性

---

## 2. 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| **语言** | Kotlin | 2.3.0 |
| **编译目标** | Java 17 | compileSdk 35, minSdk 29 |
| **UI 框架** | Jetpack Compose + Material3 | BOM 2025.03.00 |
| **构建** | Android Gradle Plugin | 8.7.3 |
| **网络** | OkHttp + SSE | 4.12.0 |
| **序列化** | Kotlinx Serialization | 1.8.0 |
| **数据库** | Room + KSP | 2.7.2 |
| **本地推理** | LiteRT-LM (Gemma 4 E4B) | 0.10.0 |
| **ML 推理** | TensorFlow Lite | 2.16.1 |
| **嵌入模型** | MiniLM-L6-v2 (TFLite/ONNX) | 384 维 |
| **ONNX** | ONNX Runtime（备用嵌入） | 1.21.0 |
| **安全** | AndroidX Security Crypto | 1.1.0-alpha06 |
| **协程** | kotlinx-coroutines | 1.10.1 |
| **依赖注入** | Koin | 手动注入 |
| **加密数据库** | SQLCipher（可选） | 4.10.0 |
| **JS 引擎** | Rhino（原型）→ QuickJS（计划） | 1.7.15 |
| **NDK** | 27.0.12077973 | arm64-v8a, armeabi-v7a |

---

## 3. 架构设计

### 3.1 整体数据流

```
User Input (Text / Voice)
    │
    ▼
MainActivity (Compose UI, Tab Navigation)
    │
    ▼
AgentSession (对话编排 + 工具循环, max 5 rounds)
    │
    ├── ModelClient (LLM 调用, 同步/流式)
    │   ├── BailianClient    (阿里百炼, OpenAI 兼容)
    │   ├── OpenAIClient     (OpenAI Chat Completions)
    │   ├── AnthropicClient  (Anthropic Messages API)
    │   └── LocalLLMClient   (本地 Gemma 4 E4B, ~18-22 tok/s)
    │
    ├── SkillManager → Skill.executeTool()
    │   └── 11 个内置技能 + ScriptEngine 动态脚本
    │
    ├── HybridSessionManager (消息持久化 + 压缩)
    │   ├── SessionCompressor (LLM 摘要压缩)
    │   └── MemoryManager (向量记忆 + 遗忘曲线)
    │
    └── A2UI Renderer (富 UI 渲染)
```

### 3.2 分层架构

```
┌─────────────────────────────────────────────┐
│  Presentation Layer                         │
│  MainActivity.kt, ChatScreen.kt,            │
│  NotificationScreen.kt, SettingsScreen,     │
│  ui/theme/                                  │
├─────────────────────────────────────────────┤
│  Agent Layer                                │
│  AgentSession.kt, SkillManager.kt,          │
│  11 Skill implementations,                  │
│  ScriptEngine (:script module)              │
├─────────────────────────────────────────────┤
│  Domain Layer                               │
│  domain/session/ - HybridSessionManager,    │
│                    SessionCompressor,       │
│                    TokenCounter             │
│  domain/memory/ - MemoryManager,            │
│                    HybridSearchEngine,      │
│                    SensoryBuffer,           │
│                    MemoryExtractor          │
│  domain/memory/ - ColdStartManager,         │
│                    UserProfileBuilderWorker, │
│                    MemoryMaintenanceWorker  │
│  model/ - ModelClient 接口 + 4个实现        │
├─────────────────────────────────────────────┤
│  Data Layer                                 │
│  data/local/ - AppDatabase, DAOs,           │
│                BM25Index, Converters        │
│  data/model/ - Entity 定义                  │
│  ConfigManager, PermissionManager           │
├─────────────────────────────────────────────┤
│  Infrastructure                             │
│  ml/ - EmbeddingService (ONNX/TFLite/Simple)│
│  voice/ - STT/TTS engines, VoiceSession     │
│  feishu/ - 飞书网关集成                     │
│  notification/ - 通知监听与 ML 分类          │
│  security/ - SecurityKeyManager, AuditLogger│
│  accessibility/ - AccessibilityBridge       │
└─────────────────────────────────────────────┘
```

### 3.3 依赖注入链路

```
AppDatabase → TfLiteEmbeddingService → MemoryManager
       → HybridSessionManager → AgentSession
       → SkillManager → 11 内置技能
       → LocalLLMClient (可选)
```

---

## 4. 核心模块

### 4.1 AgentSession

**职责**: 对话编排中心，管理消息历史、工具调用循环、流式处理。

**关键特性**:
- **工具调用循环**: 最多 5 轮，支持链式工具调用
- **流式响应**: `Flow<SessionEvent>` 实时发射 Token/Complete/Error
- **Token 感知**: 自动估算 ~1.3 token/CJK 字符，~0.25 token/ASCII
- **历史截断**: 超限时自动截断旧消息
- **工具来源**: Accessibility 工具 + Skill 工具
- **A2UI 兜底**: `ensureA2UIInResponse()` 确保 LLM 输出富 UI 标记

**文件**: `app/src/main/java/ai/openclaw/android/agent/AgentSession.kt` (391 行)

### 4.2 GatewayService & GatewayManager

**GatewayService**: 前台服务，保持 Assistant 后台运行
**GatewayManager**: 编排所有核心组件（模型、技能、飞书、无障碍）

**当前问题**: GatewayService 和 MainActivity 各自持有独立实例（内存浪费 7GB+）
**重构计划**: 引入 `GatewayContract` 接口，GatewayService 作为唯一逻辑中心

**详见**: [Gateway Service 架构](docs/gateway-service-architecture.md)

### 4.3 AccessibilityBridge

**功能**: 将 AI 工具调用转换为无障碍操作

| 工具 | 功能 |
|------|------|
| `click` | 点击屏幕元素 |
| `click_by_id` | 按 ID 点击 |
| `long_click` | 长按 |
| `input_text` | 文本输入 |
| `swipe` | 滑动手势 |
| `press_back` / `press_home` | 返回/主页 |
| `read_screen` | 读取屏幕内容 |
| `screenshot` | 截图 |
| `find_elements` | 查找元素 |

### 4.4 SmartNotificationListener

**功能**: 监听、分类（URGENT/IMPORTANT/NOISE）和存储通知

- **ML 分类**: `NotificationMLClassifier`（当前返回 null，回退到规则引擎）
- **通知屏幕**: Compose UI 列表展示
- **计划**: 集成 TFLite 分类模型

---

## 5. 技能系统

### 5.1 内置技能清单 (11 个)

| 技能 | 工具 | 数据源 | API Key |
|------|------|--------|---------|
| **WeatherSkill** | `get_weather` | wttr.in + Open-Meteo（回退） | ❌ |
| **MultiSearchSkill** | `search` | SearXNG 元搜索引擎 | ❌ |
| **TranslateSkill** | `translate` | MyMemory API | ❌ |
| **ReminderSkill** | `set_reminder`, `list_reminders`, `cancel_reminder` | Android AlarmManager | ❌ |
| **CalendarSkill** | `list_events`, `add_event` | Android Calendar Provider | ❌ |
| **LocationSkill** | `get_location`, `get_address`, `search_places` | GPS + Nominatim | ❌ |
| **ContactSkill** | `search_contacts`, `get_contact`, `call_contact` | Android Contacts Provider | ❌ |
| **SMSSkill** | `send_sms`, `read_sms`, `get_unread_sms` | Android SMS API | ❌ |
| **AppLauncherSkill** | `open`, `list_apps` | PackageManager | ❌ |
| **SettingsSkill** | `open_settings`, `toggle_bluetooth`, `volume` | 系统服务 | ❌ |
| **ScriptSkill** | `execute_script` | ScriptEngine | ❌ |

### 5.2 Skill Schema v1.0

技能配置化 JSON Schema，支持 10 种执行器类型：

| # | type | 用途 | 状态 |
|---|------|------|------|
| 1 | http | 网络请求 | ✅ 即用 |
| 2 | intent | 启动 Activity | ✅ 即用 |
| 3 | content_resolver | 数据库查询 | ✅ 即用 |
| 4 | location | 定位 | ✅ 即用 |
| 5 | alarm | 闹钟/提醒 | ✅ 即用 |
| 6 | sms | 发短信 | ✅ 即用 |
| 7 | file | 文件读写 | ✅ 即用 |
| 8 | sensor | 硬件传感器 | 🔮 预留 |
| 9 | shell | Shell 命令 | 🔮 预留 |
| 10 | accessibility | 无障碍操作 | 🔮 预留 |

**设计原则**:
- `{{variable}}` 模板语法，动态参数替换
- `type` 决定执行器，执行引擎分发
- 自描述，自带 instructions 注入 system prompt
- 安全沙盒：文件操作限制在 allowed_dirs，HTTP 限制域名

**详见**: [Skill Schema v1.0](docs/skill-schema-v1.md)（完整文档在飞书）

### 5.3 技能权限

| 技能 | 所需权限 |
|------|---------|
| Calendar | `READ_CALENDAR`, `WRITE_CALENDAR` |
| Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| Contact | `READ_CONTACTS` |
| SMS | `SEND_SMS`, `READ_SMS` |
| 其他 | 无需特殊权限 |

---

## 6. 记忆系统

### 6.1 三层记忆模型

```
┌─────────────────────────────────────────────────────────┐
│                    记忆系统 (Memory OS)                  │
├─────────────────────────────────────────────────────────┤
│ 1. 感知记忆层   │ 即时对话上下文、当前屏幕内容、语音片段  │
│    (Sensory)    │ 生命周期：单次会话，驻留内存            │
│                 │ 容量：最近 50 条交互或 5 分钟             │
├─────────────────────────────────────────────────────────┤
│ 2. 摘要记忆层   │ 用户画像、习惯、关键事件压缩向量         │
│    (Semantic)   │ 生命周期：持久化，本地加密存储           │
│                 │ 存储：Room Database + 向量索引          │
├─────────────────────────────────────────────────────────┤
│ 3. 过程记忆层   │ 任务状态机、多轮操作序列                 │
│    (Episodic)   │ 生命周期：任务期间，可选同步             │
│                 │ 同步：Hub API 差分传输                  │
└─────────────────────────────────────────────────────────┘
```

### 6.2 核心组件

| 组件 | 功能 | 状态 |
|------|------|------|
| **SensoryBuffer** | 环形缓冲区 50 条 / 5 分钟 | ✅ 已完成 |
| **HybridSearchEngine** | BM25 + 向量双路召回 + 时间衰减 | ✅ 已完成 |
| **BM25Index** | 关键词索引 + 时间分桶 + LRU 缓存 | ✅ 已完成 |
| **EmbeddingModel** | ONNX Runtime 优先 + TfLite/Simple 降级 | ✅ 已完成 |
| **MemoryManager** | CRUD + 冲突检测 + 遗忘曲线 | ✅ 已完成 |
| **MemoryMaintenanceWorker** | 24h 周期清理，WorkManager 调度 | ✅ 已完成 |
| **SecurityKeyManager** | KeyStore + EncryptedSharedPreferences | ✅ 已完成 |
| **DiffSyncManager** | 双向 push/pull + version 冲突解决 | ✅ 已完成 |
| **AuditLogger** | SHA-256 哈希链防篡改 | ✅ 已完成 |
| **ColdStartManager** | 72h 渐进式冷启动 | ✅ 已完成 |
| **UserProfileBuilderWorker** | 后台构建用户画像 | ✅ 已完成 |

### 6.3 记忆提取触发机制

| 触发方式 | 说明 |
|---------|------|
| **关键词** | 检测 "记住这个" 等关键词 |
| **空闲延迟** | 30 秒无操作后自动提取 |
| **LLM 提取** | 使用本地 LLM 从对话中提取 |
| **Fallback** | 关键词启发式提取（降级方案） |

### 6.4 性能优化（已完成）

| 优化项 | 实现 |
|--------|------|
| **遗忘曲线** | `0.4×recency + 0.4×frequency + 0.2×priority`，自动清理低价值记忆，保留≥20条 |
| **搜索限制** | 默认最近90天记忆，最多200条向量；无结果时兜底全量搜索 |
| **LRU 缓存** | EmbeddingService 添加 `LruCache<String, FloatArray>(100)` |
| **记忆去重** | 存储时与近期记忆比较余弦相似度，>0.95 跳过 |
| **冲突检测** | 三区间策略：>0.95 跳过 / 0.80~0.95 保留更完整 / <0.80 正常存储 |

---

## 7. LLM 接入

### 7.1 提供商支持

| 客户端 | API 协议 | 流式 | 工具调用 | 状态 |
|--------|---------|------|---------|------|
| **BailianClient** | OpenAI 兼容 (阿里百炼) | SSE | ✅ | ✅ 已实现 |
| **OpenAIClient** | OpenAI Chat Completions | SSE | ✅ | ✅ 已实现 |
| **AnthropicClient** | Anthropic Messages API | SSE | ✅ | ✅ 已实现 |
| **LocalLLMClient** | LiteRT-LM 本地推理 | Flow | ✅ | ✅ 已实现 |

### 7.2 本地模型

| 参数 | 值 |
|------|------|
| **模型** | Gemma 4 E4B (4B 参数, ~3.5GB) |
| **格式** | `.litertlm` (LiteRT-LM 格式) |
| **位置** | `/sdcard/Download/gemma-4-E4B-it.litertlm` 或 `filesDir/models/` |
| **推理速度** | ~18 tok/s (CPU) / ~22 tok/s (GPU) |
| **Backend 优先级** | NPU → GPU → CPU（依硬件自动选择） |
| **Honor/Huawei** | 跳过 NPU（兼容性问题），GPU → CPU |
| **Token 预算** | E4B: 15872 tokens / E2B: 7680 tokens |

### 7.3 模型配置

用户可通过 Settings 页面切换：
- 提供商选择（Bailian / OpenAI / Anthropic / Local）
- API Key 配置（加密存储）
- 模型名称选择

---

## 8. 数据层

### 8.1 Room 数据库

`AppDatabase` (单例, 线程安全) 包含三组 DAO：

| 领域 | DAO | Entity |
|------|-----|--------|
| **会话** | SessionDao | SessionEntity |
| **消息** | MessageDao | MessageEntity, SummaryEntity |
| **记忆** | MemoryDao, MemoryVectorDao | MemoryEntity, MemoryVectorEntity |

### 8.2 配置管理

`ConfigManager` 使用双重存储：
- **EncryptedSharedPreferences**: API 密钥等敏感数据
- **普通 SharedPreferences**: 非敏感配置

### 8.3 记忆实体

```kotlin
data class MemoryEntity(
    val content: String,              // 记忆内容
    val memoryType: MemoryType,       // PREFERENCE / FACT / DECISION / TASK / PROJECT
    val priority: Int,                // 1-5
    val source: String?,              // "auto" / "manual" / conversation_id
    val tags: List<String>,           // 标签
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0          // 访问次数（遗忘策略）
)
```

---

## 9. 语音交互

```
VoiceInteractionManager (统一语音接口)
    ├── VoiceSession (状态机: Idle → Listening → Processing → Speaking)
    ├── STT: AndroidSpeechRecognizer (原生 Android)
    └── TTS: AndroidTTSEngine (原生 Android)
```

**状态机**:
```
Idle ──(开始)──→ Listening ──(检测到语音结束)──→ Processing ──(获取结果)──→ Speaking ──(播放完)──→ Idle
```

---

## 10. A2UI 协议

**格式**: `[A2UI]{"type": "...", "data": {...}}[/A2UI]`

**支持类型**: `weather`, `location`, `reminder`, `translation`, `search`, `generic`, `confirmation`

**富文本保障机制**:
1. **系统提示词**: MANDATORY 标记 + few-shot 示例
2. **自动生成**: `generateA2UIFromResult()` 将工具结果转为 A2UI JSON
3. **兜底注入**: `ensureA2UIInResponse()` LLM 不输出 A2UI 时自动注入

**渲染器**: `:android_compose` 子模块，Compose 原生实现

---

## 11. 交互设计

### 11.1 核心原则

**"Intent 优先 + Accessibility 兜底 + 用户确认" 的渐进路线**

| 原则 | 说明 |
|------|------|
| AI 做 90% | 理解意图、构建参数、打开页面、填入内容 |
| 用户做 10% | 最后确认/发送，保障安全 |
| 三层降级 | URL Scheme → Intent → AccessibilityService |

### 11.2 三级能力矩阵

| Tier | 能力 | 覆盖 | 权限 |
|------|------|------|------|
| **Tier 1** | URL Scheme / App Links | 60%+ | 无需特殊权限 |
| **Tier 2** | App 官方 API / SDK | 20% | 视 API 而定 |
| **Tier 3** | AccessibilityService | 20% 兜底 | 无障碍权限 |

### 11.3 安全控制

| 风险等级 | 操作类型 | 策略 |
|---------|---------|------|
| 低 | 查询、浏览、打开页面 | 自动执行（auto） |
| 中 | 发送消息、添加日程 | 用户确认（hybrid） |
| 高 | 支付、转账、删除数据 | 始终手动（manual） |

**详见**: [应用交互设计](docs/app-interaction-design.md)

---

## 12. ScriptEngine

**定位**: 运行时动态生成的 Tool。LLM 生成 JS 脚本，沙箱执行后返回结果。

**架构**:
```
LLM 生成 JS 脚本
    │
    ▼
ScriptOrchestrator
    ├── ScriptValidator ──→ 静态校验
    ├── ScriptEngine ──→ Rhino JS 引擎（原型）
    │     └── CapabilityBridge
    │           ├── FileBridge → fs.readFile/writeFile/list/exists
    │           ├── HttpBridge → http.get/post
    │           └── MemoryBridge → memory.recall/store
    └── SandboxPolicy ──→ 超时/内存/路径限制
```

**安全**:
- 禁止 `import`、`require`、`eval`
- 禁止访问 `java.`、`android.`、`Packages`
- 脚本长度上限 50KB
- 文件操作限制在沙箱目录
- 执行超时 10s

**JS 引擎**: Rhino（原型，~2MB）→ QuickJS JNI（计划，~500KB，10x 性能提升）

**测试覆盖**: 62 个用例（Validator 22 + Engine 16 + FileBridge 16 + Result 6 + Policy 2）

**详见**: [ScriptEngine 设计](docs/script-engine.md)

---

## 13. Gateway Service 架构

### 当前问题
- GatewayService 和 MainActivity 各自持有独立实例（内存浪费 7GB+）
- Activity 被回收后模型需重新加载（约 10 秒）
- MainActivity 包含大量业务逻辑（963 行）

### 重构方案
- **GatewayContract** 接口：Activity 只依赖接口，不直接访问内部组件
- **GatewayService** 作为唯一逻辑中心，持有所有核心组件
- **MainActivity** 改为纯 UI 层，通过 Binder 调用 GatewayContract

**改动量**:
| 文件 | 改动 |
|------|------|
| `GatewayContract.kt` | 新增 |
| `GatewayManager.kt` | 实现 GatewayContract |
| `GatewayService.kt` | LocalBinder 返回 GatewayContract |
| `MainActivity.kt` | 移除 ~150 行业务逻辑 |

**预计工期**: 4.5 天

**详见**: [Gateway Service 架构](docs/gateway-service-architecture.md)

---

## 14. 构建与测试

### 14.1 构建命令

```bash
# 设置 JAVA_HOME（需要 JBR / Java 21）
export JAVA_HOME="E:/Program Files/Android/Android Studio/jbr"

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests "ai.openclaw.android.model.ModelModelsTest"

# 仪器测试（需设备/模拟器）
./gradlew connectedAndroidTest

# 安装 Debug APK
./gradlew installDebug

# 清理构建
./gradlew clean
```

### 14.2 测试覆盖

| 类型 | 数量 | 覆盖范围 |
|------|------|---------|
| **单元测试** | 11 个类 | 序列化、Token 计数、技能逻辑、记忆 CRUD、向量搜索、会话压缩 |
| **仪器测试** | 6 个类 | Agent 流式行为、数据库 DAO、会话集成、记忆 DAO、嵌入服务 |

**未覆盖**: LLM 客户端（需 mock HTTP）、UI 层、权限管理、语音交互、通知分类、飞书集成

### 14.3 构建变体

| 变体 | 代码压缩 | 资源压缩 | ProGuard | 签名 |
|------|---------|---------|----------|------|
| Debug | ❌ | ❌ | ❌ | Debug |
| Release | ✅ | ✅ | ✅ | local.properties 配置 |

---

## 15. 安全

| 安全措施 | 实现 |
|---------|------|
| **API Key 存储** | EncryptedSharedPreferences (AndroidX Security Crypto) |
| **网络安全** | Network Security Config 强制 HTTPS |
| **运行时权限** | 执行前检查技能权限 |
| **无硬编码密钥** | 源码中不包含密钥（Release 构建） |
| **记忆加密** | SQLCipher + Android Keystore |
| **审计日志** | SHA-256 哈希链防篡改 |
| **差分同步** | 匿名化向量索引 + 加密内容 |
| **脚本沙箱** | Rhino 安全对象 + 路径隔离 |

---

## 16. 待完成项

### P0 - 稳定性

| # | 问题 | 状态 |
|---|------|------|
| 1 | `vocab.txt` 缺失导致 TfLiteEmbeddingService 初始化失败 | ✅ 已解决 |
| 2 | LiteRT-LM native 库加载失败 | ✅ 已解决 (maxNumTokens 调整) |
| 3 | 数据库无 Migration 策略 | ✅ 已解决 (v2→v3) |
| 4 | 记忆系统 P0 优化 | ✅ 已完成 |

### P1 - 架构

| # | 任务 | 状态 |
|---|------|------|
| 1 | 拆分 MainActivity (963行) | ❌ 未开始 |
| 2 | 拆分 ChatScreen (663行) | ❌ 未开始 |
| 3 | Gateway Service 重构（单实例） | ❌ 未开始 |
| 4 | 会话压缩质量提升 | ⚠️ 基础实现 |
| 5 | 补充测试覆盖 | ⚠️ 部分完成 |

### P2 - 质量

| # | 任务 | 状态 |
|---|------|------|
| 1 | UI 优化（Markdown/代码高亮/深色模式） | ❌ 未开始 |
| 2 | 性能优化（异步模型加载/语音组件复用） | ⚠️ 部分完成 |
| 3 | 安全加固（移除硬编码 Key/速率限制） | ❌ 未开始 |
| 4 | ML 通知分类 | ❌ 未实现 |
| 5 | 飞书集成 | ⚠️ 骨架代码 |
| 6 | 国际化 | ❌ 未开始 |
| 7 | 图片/语音多模态输入 | ❌ 未开始 |
| 8 | CI/CD 流水线 | ❌ 未开始 |

**详见**: [TODO-LIST](docs/TODO-LIST.md)

---

## 17. 发展规划

### M1 — 基础稳固 (3 周)

> 修复所有 P0 缺陷，确保核心功能可用

- 向量搜索 sqlite-vec 集成
- OpenAI/Anthropic 客户端完善
- Release 签名 + CI/CD
- 本地模型路径优化
- 崩溃报告接入

### M2 — 体验提升 (4 周)

> 缩小与竞品的用户体验差距

- ChatScreen 重构（Markdown + 代码高亮）
- 多会话管理 UI
- 图片附件 + 语音输入
- ML 通知分类
- 飞书集成核心功能
- 国际化基础框架

### M3 — 差异化竞争 (4 周)

> 发挥设备级 AI 优势

- 无障碍自动化增强
- 智能体编排（多技能自动编排）
- 相机实时理解
- 新技能（文件管理/应用控制/剪贴板）
- 性能优化（冷启动 < 2s）

### 时间线

```
2026-04-08 ─── 项目现状 (master)
    │
    ├── M1: 基础稳固 (3 周) ─── 2026-04-29
    │
    ├── M2: 体验提升 (4 周) ─── 2026-05-27
    │
    └── M3: 差异化竞争 (4 周) ─── 2026-06-24
```

---

## 18. 文档索引

| 文档 | 路径 | 说明 |
|------|------|------|
| **全局项目文档** | `PROJECT.md` | 本文档 |
| **README** | `README.md` | 项目简介与快速开始 |
| **构建指南** | `CLAUDE.md` | 编译、测试命令、技术栈 |
| **测试指南** | `TEST-GUIDE.md` | 测试使用说明 |
| **差距分析** | `docs/GAP_ANALYSIS.md` | 核心差距分析 + 竞品对比 + 里程碑 |
| **待完成项** | `docs/TODO-LIST.md` | P0/P1/P2 任务清单 + 已完成项 |
| **工程分析** | `docs/project-analysis.md` | 基础架构分析（英文） |
| **工程分析(新)** | `docs/project-analysis-2026-04-09.md` | 完整工程分析（79 文件, 11247 行） |
| **AgentSession 优化** | `docs/agent-session-optimization.md` | 流式响应、工具调用、System Prompt 优化 |
| **记忆优化** | `docs/memory-optimization-plan.md` | 五阶段记忆系统优化（全部完成） |
| **Gateway 架构** | `docs/gateway-service-architecture.md` | 服务重构设计（GatewayContract 接口） |
| **交互设计** | `docs/app-interaction-design.md` | 三级能力矩阵 + Hybrid 执行模式 |
| **ScriptEngine** | `docs/script-engine.md` | 动态 JS 脚本执行模块 |
| **Skill Schema** | `docs/skill-schema-v1.md` | 技能配置 Schema v1.0 |
| **记忆系统设计** | `docs/plans/2026-04-06-memory-system-design.md` | 原始记忆系统设计 |
| **会话机制** | `docs/plans/2026-04-06-session-mechanism.md` | 会话持久化 + 压缩实现计划 |

---

> **维护说明**: 本文档由 docs/ 目录下所有 MD 文件汇总生成，是 OpenClaw-Android 项目的全局视图。详细设计请参考各子文档。
