# OpenClaw-Android 待完成项清单

**最后更新**: 2026-04-12

---

## P0 - 稳定性

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 1 | `vocab.txt` 缺失导致 TfLiteEmbeddingService 初始化失败 | **已解决** | 文件已放到 SD 卡外部存储路径，真机验证通过 |
| 2 | LiteRT-LM native 库加载失败 | **已解决** | maxNumTokens 调整为 16384，Gemma 4 E4B 支持 128K |
| 3 | 数据库无 Migration 策略 | **已解决** | v2→v3 迁移已实现（version 字段），需后续补充更多迁移 |
| 4 | 记忆系统 P0 优化 | **已完成** | 遗忘曲线、搜索范围限制、LRU缓存、去重 |

---

## P1 - 架构

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 1 | 拆分 MainActivity (963行) | 未开始 | 提取 ViewModel + 子 Composable |
| 2 | 拆分 ChatScreen (663行) | 未开始 | A2UI 解析逻辑独立 |
| 3 | 多 Agent 路由 | 未开始 | main/coder/security 路由机制 |
| 4 | 会话压缩质量提升 | 基础实现 | 摘要缓存、跨会话记忆关联 |
| 5 | 补充测试覆盖 | 部分完成 | LLM 客户端 mock 测试、UI 测试缺失 |

---

## P2 - 质量

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 1 | UI 优化 | 未开始 | Markdown 渲染、代码高亮、消息复制、深色模式 |
| 2 | 性能优化 | 部分完成 | 嵌入缓存已实现(LRU)；异步模型加载、语音组件复用待做 |
| 3 | 安全加固 | 未开始 | 移除硬编码 API key、速率限制、日志脱敏 |
| 4 | ML 通知分类 | 未实现 | `NotificationMLClassifier` 始终返回 null |
| 5 | 飞书集成 | 骨架代码 | 基础 HTTP 客户端，无业务逻辑 |
| 6 | 国际化 | 未开始 | UI 文案硬编码中文 |

---

## 已完成项

| 功能 | 完成时间 | 备注 |
|------|---------|------|
| **记忆优化方案五阶段全部完成** | 2026-04-12 | SensoryBuffer / BM25+向量混合检索 / 冲突检测+存储阈值 / SQLCipher+KeyStore+DiffSync+AuditLogger / 冷启动渐进式记忆 |
| 记忆系统 P0 优化 (4项) | 2026-04-11 | 遗忘曲线、搜索范围限制、LRU嵌入缓存、记忆去重 |
| A2UI 富文本渲染修复 + 兜底机制 | 2026-04-10 | 修复转圈 bug，新增 ensureA2UIInResponse 兜底 + 强提示词 |
| LocalLLMClient 并发 Session 冲突修复 | 2026-04-10 | Mutex 互斥锁，解决 "session already exists" |
| LocalLLMClient Token 超限修复 | 2026-04-10 | truncateMessages 截断历史，maxTokens 2048→16384 |
| 记忆提取引擎自动恢复 | 2026-04-10 | ensureEngineReady() 自动恢复 ERROR 状态 |
| 记忆系统 LogManager 日志接入 | 2026-04-10 | Settings 页面可查看记忆提取全链路日志 |
| A2UI rememberSaveable 崩溃修复 | 2026-04-09 | remember 替代 rememberSaveable |
| 语音交互模块 | 2026-04-08 | VoiceSession + STT/TTS |
| AppLauncherSkill + SettingsSkill | 2026-04-08 | 应用启动、系统设置控制 |
| 单元测试修复 (42 PASS) | 2026-04-08 | 全部通过 |
| 向量搜索 (暴力余弦相似度) | 2026-04-06 | FallbackEmbeddingService 兜底 |
| 会话机制 | 2026-04-06 | HybridSessionManager |
| 记忆系统 | 2026-04-06 | MemoryManager + 向量检索 |
| 本地 LLM | 2026-04-05 | Gemma 4 E4B + LiteRT-LM |
| OpenAI + Anthropic 客户端 | 2026-04-04 | GPT-4o, Claude 支持 |
| 11 个内置技能 | 2026-03-30 | 天气/搜索/翻译/提醒/日程/定位/通讯录/短信/启动器/设置 |
| APK 大小优化 | 2026-04-06 | 149MB → 46MB |

---

## 相关文档

- [project-analysis-2026-04-09.md](./project-analysis-2026-04-09.md) - 最新工程分析
- [memory-optimization-plan.md](./memory-optimization-plan.md) - 记忆系统优化方案
- [plans/2026-04-06-memory-system-design.md](./plans/2026-04-06-memory-system-design.md) - 记忆系统设计
- [plans/2026-04-06-session-mechanism-design.md](./plans/2026-04-06-session-mechanism-design.md) - 会话机制设计
