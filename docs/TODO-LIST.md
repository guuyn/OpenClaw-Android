# OpenClaw-Android 待完成项清单

**最后更新**: 2026-04-14

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
| 2 | ~~A2UI 解析逻辑独立~~ | ✅ **已完成** | A2UICardParser 独立类，ChatScreen 纯 UI |
| 3 | 多 Agent 配置系统 | **✅ 已完成 2026-04-16** | AgentRegistry + AgentPromptLoader + AgentConfig + AgentManagementSkill（7 个工具），真机验证通过 |
| 4 | 多 Agent 消息路由 | 未开始 | 收到消息后如何路由到对应 Agent（关键词匹配、内容分类），配置层已完成，路由层待做 |
| 4 | 会话压缩质量提升 | 基础实现 | SessionCompressor 已实现，摘要缓存、跨会话记忆关联待做 |
| 5 | 补充测试覆盖 | 部分完成 | **158 测试全部通过**；LLM 客户端 mock、Compose UI 测试缺失 |
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
| 8 | ~~A2UI 卡片系统 v2~~ | ✅ **已完成** | 14 种卡片 + 解析器 + 回调机制 + 3 个技能适配 + 兜底机制 |
| 9 | ScriptEngine 生产化 | **原型阶段** | Rhino 原型 + 9 文件 + 60 测试；待 QuickJS JNI + 安全沙箱完善 |

---

## ✅ 已完成项（截至 2026-04-14）

| 功能 | 完成时间 | 备注 |
|------|---------|------|
| **A2UI 卡片系统 v2（9 个任务全部完成）** | 2026-04-14 | 14 种卡片 + 解析器 + 回调 + 3 技能适配 + 兜底 |
| Gateway Service 架构重构 | 2026-04-12 | MainActivity 纯 UI 层，GatewayManager 唯一实例 |
| 记忆优化方案五阶段全部完成 | 2026-04-12 | SensoryBuffer / BM25+向量 / 冲突检测 / 加密 / 冷启动 |
| Koin DI 框架集成 | 2026-04-12 | `di/AppModule.kt` |
| ViewModel 拆分 | 2026-04-12 | ChatViewModel + SettingsViewModel |
| :script 模块集成 | 2026-04-13 | 9 个文件，Rhino 原型 + 60 测试 |
| ScriptSkill 技能 | 2026-04-13 | JS 脚本沙箱执行 |
| Release 签名配置 | 已完成 | build.gradle.kts 已配置 |
| CI/CD pipeline | 已完成 | `.github/workflows/android.yml` |
| 记忆系统 P0 优化 | 2026-04-11 | 遗忘曲线、搜索限制、LRU缓存、去重 |
| A2UI 富文本修复 + 兜底 | 2026-04-10 | 修复转圈 bug |
| LocalLLMClient 并发修复 | 2026-04-10 | Mutex 互斥锁 |
| LocalLLMClient Token 修复 | 2026-04-10 | truncateMessages，maxTokens 2048→16384 |
| 语音交互模块 | 2026-04-08 | VoiceSession + STT/TTS |
| AppLauncherSkill + SettingsSkill | 2026-04-08 | 应用启动、系统设置控制 |
| 12 个内置技能 | 2026-04-13 | 天气/搜索/翻译/提醒/日程/定位/通讯录/短信/启动器/设置/文件/脚本 |
| APK 大小优化 | 2026-04-06 | 149MB → 46MB |

---

## 📊 代码现状

| 指标 | 值 |
|------|-----|
| Kotlin 源文件 | 106 个（app）+ 9 个（:script） |
| 内置技能 | 12 个（其中 3 个已适配 v2 卡片） |
| ViewModel | 2 个 |
| LLM Provider | 4 个 |
| 单元测试 | **158 个（全部通过 ✅）** |
| A2UI 卡片类型 | **14 种** |

---

## 相关文档

- [2026-04-14-a2ui-card-system-v2.md](./plans/2026-04-14-a2ui-card-system-v2.md) - A2UI v2 实施计划 ✅已完成
- [a2ui-card-system-v2.md](./a2ui-card-system-v2.md) - A2UI 卡片系统设计
- [gateway-service-architecture.md](./gateway-service-architecture.md) - Gateway 重构设计 ✅已落地
- [script-engine.md](./script-engine.md) - ScriptEngine 设计
- [CURRENT-STATUS-2026-04-14.md](./CURRENT-STATUS-2026-04-14.md) - 当前代码状态快照
