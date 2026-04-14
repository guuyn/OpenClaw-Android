# OpenClaw-Android 待完成项清单

**最后更新**: 2026-04-14（基于代码实际状态重新校准）

---

## P0 - 稳定性（全部完成 ✅）

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 1 | `vocab.txt` 缺失导致 TfLiteEmbeddingService 初始化失败 | **已解决** | 文件已放到外部存储路径，真机验证通过 |
| 2 | LiteRT-LM native 库加载失败 | **已解决** | maxNumTokens 调整为 16384，Gemma 4 E4B 支持 128K |
| 3 | 数据库无 Migration 策略 | **已解决** | v2→v3 迁移已实现（version 字段） |
| 4 | 记忆系统 P0 优化 | **已完成** | 遗忘曲线、搜索范围限制、LRU缓存、去重 |
| 5 | Gateway Service 双实例 | **已完成** | MainActivity 0 核心组件引用，GatewayManager 唯一实例 |

---

## P1 - 架构

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 1 | ~~拆分 MainActivity~~ | ✅ **已完成** | GatewayContract 接口 + ChatViewModel/SettingsViewModel |
| 2 | 拆分 ChatScreen (667行) | 部分完成 | UI 组件已拆（MessageBubble/ToolCallCard/TypingIndicator），A2UI 解析逻辑待独立 |
| 3 | 多 Agent 路由 | 未开始 | main/coder/security 路由机制 |
| 4 | 会话压缩质量提升 | 基础实现 | SessionCompressor 已实现，摘要缓存、跨会话记忆关联待做 |
| 5 | 补充测试覆盖 | 部分完成 | 11 单元测试 + 6 仪器测试；LLM 客户端 mock、UI 测试缺失 |
| 6 | ScriptSkill MemoryBridge 对接 | **未对接** | 代码已集成但返回 `{"error":"Memory not yet integrated"}` |

---

## P2 - 质量

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 1 | UI 优化 | 部分完成 | MarkdownRenderer 已有基础实现；代码高亮、消息复制、深色模式待做 |
| 2 | 性能优化 | 部分完成 | 嵌入缓存已实现(LRU)；异步模型加载、语音组件复用待做 |
| 3 | 安全加固 | 部分完成 | SQLCipher + SecurityKeyManager + AuditLogger 已有；速率限制、日志脱敏待做 |
| 4 | ML 通知分类 | 未实现 | `NotificationMLClassifier` 始终返回 null |
| 5 | 飞书集成 | 骨架代码 | 302 行基础 HTTP 客户端，无实际业务逻辑 |
| 6 | 国际化 | 未开始 | UI 文案硬编码中文 |
| 7 | 本地通知发送 | **未实现** | `SmartNotificationListener.kt:155` TODO |
| 8 | A2UI 卡片系统 v2 | **设计阶段** | 14 种卡片类型设计已完成（4/13），待编码 |
| 9 | ScriptEngine 生产化 | **原型阶段** | Rhino 原型 + 9 文件 + 60 测试；待 QuickJS JNI + 安全沙箱完善 |

---

## ✅ 已完成项（截至 2026-04-14）

| 功能 | 完成时间 | 备注 |
|------|---------|------|
| **Gateway Service 架构重构** | 2026-04-12 | MainActivity 纯 UI 层，GatewayManager 唯一实例，GatewayContract 接口 |
| **记忆优化方案五阶段全部完成** | 2026-04-12 | SensoryBuffer / BM25+向量混合检索 / 冲突检测+存储阈值 / SQLCipher+KeyStore+DiffSync+AuditLogger / 冷启动渐进式记忆 |
| **Koin DI 框架集成** | 2026-04-12 | `di/AppModule.kt` |
| **ViewModel 拆分** | 2026-04-12 | ChatViewModel + SettingsViewModel |
| **:script 模块集成** | 2026-04-13 | 9 个文件，Rhino 原型 + 60 个测试 |
| **ScriptSkill 技能** | 2026-04-13 | JS 脚本沙箱执行，fs/http 桥接 |
| **天气技能 Script 化** | 2026-04-13 | WeatherSkill 替换为脚本沙箱实现 |
| **Release 签名配置** | 已完成 | build.gradle.kts 已配置 |
| **CI/CD pipeline** | 已完成 | `.github/workflows/android.yml` |
| **记忆系统 P0 优化** | 2026-04-11 | 遗忘曲线、搜索范围限制、LRU嵌入缓存、记忆去重 |
| **A2UI 富文本修复 + 兜底** | 2026-04-10 | 修复转圈 bug，ensureA2UIInResponse 兜底 |
| **LocalLLMClient 并发修复** | 2026-04-10 | Mutex 互斥锁 |
| **LocalLLMClient Token 修复** | 2026-04-10 | truncateMessages，maxTokens 2048→16384 |
| **记忆提取引擎自动恢复** | 2026-04-10 | ensureEngineReady() |
| **记忆 LogManager 日志** | 2026-04-10 | Settings 页面可查看全链路日志 |
| **A2UI rememberSaveable 崩溃修复** | 2026-04-09 | remember 替代 rememberSaveable |
| **语音交互模块** | 2026-04-08 | VoiceSession + STT/TTS |
| **AppLauncherSkill + SettingsSkill** | 2026-04-08 | 应用启动、系统设置控制 |
| **UI 组件拆分** | 2026-04-13 | MessageBubble、ToolCallCard、TypingIndicator |
| **安全模块** | 2026-04-12 | SecurityKeyManager、AuditLogger |
| **LogManager 全链路日志** | 2026-04-12 | LogManager 集中日志 |
| **向量搜索** | 2026-04-06 | FallbackEmbeddingService 兜底 |
| **会话机制** | 2026-04-06 | HybridSessionManager |
| **记忆系统** | 2026-04-06 | MemoryManager + 向量检索 |
| **本地 LLM** | 2026-04-05 | Gemma 4 E4B + LiteRT-LM |
| **OpenAI + Anthropic 客户端** | 已完成 | GPT-4o (246行), Claude (487行) |
| **12 个内置技能** | 2026-04-13 | 天气/搜索/翻译/提醒/日程/定位/通讯录/短信/启动器/设置/文件/脚本 |
| **APK 大小优化** | 2026-04-06 | 149MB → 46MB |

---

## 📊 代码现状

| 指标 | 值 |
|------|-----|
| Kotlin 源文件 | 106 个（app）+ 9 个（:script） |
| 内置技能 | 12 个 |
| ViewModel | 2 个（ChatViewModel, SettingsViewModel） |
| LLM Provider | 4 个（Bailian, OpenAI, Anthropic, Local） |
| 单元测试 | 11 个 |
| 仪器测试 | 6 个 |
| 剩余 TODO | 2 个（通知本地发送、MemoryBridge 对接） |

---

## 相关文档

- [project-analysis-2026-04-09.md](./project-analysis-2026-04-09.md) - 工程分析（4/9，需更新）
- [gateway-service-architecture.md](./gateway-service-architecture.md) - Gateway 重构设计 ✅已落地
- [a2ui-card-system-v2.md](./a2ui-card-system-v2.md) - A2UI 卡片系统设计（4/13）
- [script-engine.md](./script-engine.md) - ScriptEngine 设计（4/13）
- [memory-optimization-plan.md](./memory-optimization-plan.md) - 记忆系统优化方案 ✅已完成
