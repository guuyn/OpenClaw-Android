# OpenClaw-Android 核心差距分析报告

> 生成日期: 2026-04-08 | 分支: master | 最近提交: 495da15

---

## 目录

1. [项目现状总览](#1-项目现状总览)
2. [核心差距清单](#2-核心差距清单)
3. [竞品对比分析](#3-竞品对比分析)
4. [后续规划](#4-后续规划)
5. [技术路线](#5-技术路线)
6. [风险评估](#6-风险评估)

---

## 1. 项目现状总览

### 1.1 已实现功能矩阵

| 模块 | 功能 | 状态 | 完成度 |
|------|------|------|--------|
| **对话引擎** | AgentSession 多轮对话 + 工具调用循环 | ✅ 已完成 | 95% |
| **对话引擎** | 流式响应 (SSE/Flow) | ✅ 已完成 | 100% |
| **对话引擎** | 多工具链式调用 (max 5轮) | ✅ 已完成 | 90% |
| **LLM 接入** | 阿里百炼 (Bailian) 客户端 | ✅ 已完成 | 95% |
| **LLM 接入** | 本地 Gemma 4 E4B 推理 | ✅ 已完成 | 90% |
| **LLM 接入** | OpenAI 客户端 | ❌ 未实现 | 0% |
| **LLM 接入** | Anthropic 客户端 | ❌ 未实现 | 0% |
| **会话管理** | HybridSessionManager 持久化 | ✅ 已完成 | 85% |
| **会话管理** | SessionCompressor 压缩 | ✅ 已完成 | 80% |
| **会话管理** | TokenCounter 估算 | ✅ 已完成 | 70% |
| **记忆系统** | MemoryManager CRUD | ✅ 已完成 | 85% |
| **记忆系统** | 向量语义搜索 | ❌ 未实现 | 5% |
| **记忆系统** | LLM 记忆提取 | ⚠️ 部分实现 | 40% |
| **记忆系统** | MiniLM-L6-v2 Embedding | ⚠️ 模型缺失 | 60% |
| **技能系统** | SkillManager + 8个内置技能 | ✅ 已完成 | 90% |
| **技能系统** | 权限集成 | ✅ 已完成 | 95% |
| **无障碍** | AccessibilityBridge 自动化 | ⚠️ 基础功能 | 50% |
| **通知系统** | SmartNotificationListener | ⚠️ 部分实现 | 60% |
| **通知系统** | ML 通知分类 | ❌ 未实现 | 5% |
| **飞书集成** | FeishuClient 基础结构 | ⚠️ 骨架代码 | 20% |
| **UI** | ChatScreen 对话界面 | ✅ 已完成 | 80% |
| **UI** | NotificationScreen | ⚠️ 基础列表 | 50% |
| **UI** | Settings 多 Provider 配置 | ✅ 已完成 | 85% |
| **A2UI 协议** | 富 UI 渲染标记 | ✅ 已完成 | 80% |
| **安全** | ConfigManager 加密存储 | ✅ 已完成 | 90% |
| **安全** | PermissionManager 运行时权限 | ✅ 已完成 | 95% |
| **基础设施** | GatewayService 前台服务 | ✅ 已完成 | 85% |

### 1.2 代码量统计

```
源文件: ~50 Kotlin 文件 (app/src/main/java/)
测试文件: 15 文件 (9 单元测试 + 6 设备测试)
设计文档: 4 文件 (docs/)
技能: 8 个内置技能
数据库: 5 个 DAO, 5 个 Entity
```

### 1.3 TODO/FIXME 汇总

| 位置 | 内容 | 严重程度 |
|------|------|----------|
| `MemoryManager.kt:42` | TODO: 实现向量搜索（需要 sqlite-vec） | 🔴 P0 |
| `MemoryVectorDao.kt:20-21` | 向量搜索查询未实现 | 🔴 P0 |
| `NotificationMLClassifier.kt:17` | ML 分类始终返回 null | 🟡 P1 |
| `SmartNotificationListener.kt:155` | TODO: 本地通知发送 | 🟡 P1 |
| `assets/minilm-l6-v2.tflite.placeholder` | Embedding 模型是占位文件 | 🔴 P0 |

---

## 2. 核心差距清单

### P0 — 致命缺陷（影响核心功能）

#### P0-1: 向量语义搜索未实现

**现状**: `MemoryManager.searchMemories()` 直接返回高优先级记忆，而非基于语义相似度的搜索结果。

**影响**: 记忆系统的核心价值——跨会话语义关联——完全丧失。用户提到之前讨论过的内容时，Agent 无法回忆起相关上下文。

**位置**:
- `domain/memory/MemoryManager.kt:42-46`
- `data/local/MemoryVectorDao.kt:20-21`

**缺失**: `sqlite-vec` 扩展未集成到 Room 数据库。

---

#### P0-2: Embedding 模型缺失

**现状**: `assets/minilm-l6-v2.tflite` 是 `.placeholder` 文件，不是真正的 TFLite 模型。

**影响**: `TfLiteEmbeddingService` 无法生成向量嵌入，记忆语义搜索和通知分类均不可用。

**位置**: `app/src/main/assets/minilm-l6-v2.tflite.placeholder`

**方案**: 从 TensorFlow Hub 下载 MiniLM-L6-v2 的 TFLite 量化版本 (~45MB)，集成到 assets 或动态下载。

---

#### P0-3: OpenAI / Anthropic 客户端未实现

**现状**: `ModelClient` 接口定义了 4 种 Provider（BAILIAN, OPENAI, ANTHROPIC, LOCAL），但仅 Bailian 和 Local 有实现。

**影响**:
- 无法使用 GPT-4o、Claude 等主流模型
- 用户被锁定在单一云服务商
- 国际用户体验严重受限

**位置**: `model/ModelClient.kt:54-59` 定义了枚举，但无对应实现文件。

---

#### P0-4: 无 CI/CD 流水线

**现状**: 无 `.github/workflows/`、无 Fastlane、无任何自动化构建配置。

**影响**:
- 无自动化测试保障
- 无发布流程
- 代码质量无门禁

---

#### P0-5: 无 Release 签名配置

**现状**: `build.gradle.kts` 无 signingConfig，无 keystore 文件。

**影响**: 无法生成可发布的 Release APK。

---

### P1 — 重要缺失（影响用户体验）

#### P1-1: ML 通知分类未实现

**现状**: `NotificationMLClassifier` 始终返回 null，全部回退到规则引擎。

**影响**: 通知智能分类能力缺失，用户体验等同于普通通知管理器。

**位置**: `notification/NotificationMLClassifier.kt:17`

---

#### P1-2: 对话界面交互缺失

**现状**: ChatScreen 功能基础，缺少以下常见功能：

| 缺失功能 | 竞品对标 |
|----------|----------|
| 消息编辑/重发 | ChatGPT App ✅ |
| 图片/文件附件 | ChatGPT App ✅, Claude App ✅ |
| 语音输入 | ChatGPT App ✅, Gemini App ✅ |
| 对话分支/线程 | ChatGPT App ✅ |
| 代码块高亮 | Claude App ✅ |
| Markdown 渲染增强 | ChatGPT App ✅ |
| 多会话管理 UI | ChatGPT App ✅ |
| 对话搜索 | ChatGPT App ✅ |

---

#### P1-3: 飞书集成仅骨架

**现状**: `feishu/` 目录有基础 HTTP 客户端和模型定义，但无实际业务逻辑。

**影响**: 桌面版核心功能之一——飞书消息收发——在移动端不可用。

---

#### P1-4: 本地模型路径硬编码

**现状**: `LocalLLMClient` 模型路径硬编码为 `/sdcard/Download/gemma-4-E4B-it.litertlm`。

**影响**: 用户需手动放置模型文件，无下载引导，无校验，体验极差。

---

#### P1-5: 无崩溃报告和分析

**现状**: 无 Firebase Crashlytics、Sentry 或任何 APM 工具。

**影响**: 线上问题无法追踪，用户反馈难以复现。

---

#### P1-6: 无国际化支持

**现状**: UI 文案硬编码中文，无 `strings.xml` 多语言资源。

**影响**: 仅支持中文用户，无法扩展到国际市场。

---

### P2 — 优化建议（提升竞争力）

#### P2-1: 性能优化

| 项目 | 现状 | 建议 |
|------|------|------|
| 响应缓存 | 无 | 引入 LRU 缓存避免重复 LLM 调用 |
| 长会话管理 | 压缩后丢弃 | 支持从归档恢复完整会话 |
| 内存管理 | 无主动回收 | 长对话时主动 GC 和模型卸载 |
| 冷启动 | 全量初始化 | 延迟加载非核心模块 |

#### P2-2: 安全增强

| 项目 | 现状 | 建议 |
|------|------|------|
| API Key 显示 | 明文可见 (`VisualTransformation.None`) | 默认隐藏，点击显示 |
| 网络安全 | 无 Certificate Pinning | 关键 API 启用 SSL Pinning |
| 日志安全 | 详细 debug 日志 | Release 构建移除敏感日志 |
| Root 检测 | 无 | 添加 Root 环境安全警告 |

#### P2-3: 无障碍自动化增强

| 项目 | 现状 | 建议 |
|------|------|------|
| UI 探索 | 基础 click/swipe/input | 添加 screenshot 分析、UI 树遍历 |
| 复杂交互 | 无 | 支持条件判断、循环、等待元素 |
| 错误恢复 | 无 | 无障碍操作失败时自动重试/降级 |

#### P2-4: 技能扩展

建议增加的高价值技能：

| 技能 | 价值 | 复杂度 |
|------|------|--------|
| 相机/图片理解 | 极高 (对标 Gemini) | 高 |
| 文件管理 | 高 | 中 |
| 应用控制 | 高 (启动/关闭 App) | 低 |
| 剪贴板 | 中 | 低 |
| 电话拨打 | 中 | 低 |
| Wi-Fi/蓝牙控制 | 中 | 中 |
| 系统设置修改 | 高 | 中 |

#### P2-5: 多模态输入

| 模态 | 竞品支持 | 建议优先级 |
|------|----------|------------|
| 文本 | 全部 | ✅ 已实现 |
| 图片输入 | ChatGPT ✅, Claude ✅, Gemini ✅ | P2-高 |
| 语音输入 | ChatGPT ✅, Gemini ✅ | P2-高 |
| 实时屏幕理解 | 无 | P2-中 |
| 视频理解 | Gemini ✅ | P2-低 |

---

## 3. 竞品对比分析

### 3.1 功能对标矩阵

| 能力维度 | OpenClaw | ChatGPT | Claude | Gemini |
|----------|----------|---------|--------|--------|
| **多模型支持** | 2/4 (50%) | 1 (GPT系列) | 1 (Claude) | 1 (Gemini) |
| **本地推理** | ✅ Gemma | ❌ | ❌ | ✅ Nano |
| **流式响应** | ✅ | ✅ | ✅ | ✅ |
| **多轮对话** | ✅ 5轮工具链 | ✅ | ✅ | ✅ |
| **图片理解** | ❌ | ✅ | ✅ | ✅ |
| **语音输入** | ❌ | ✅ | ❌ | ✅ |
| **设备控制** | ✅ 无障碍 | ❌ | ❌ | ✅ (Pixel) |
| **通知管理** | ✅ 监听+分类 | ❌ | ❌ | ❌ |
| **技能/插件** | ✅ 8个 | ✅ GPTs | ✅ Artifacts | ✅ Extensions |
| **持久记忆** | ⚠️ 部分实现 | ✅ Memory | ❌ | ❌ |
| **会话管理** | ⚠️ 基础 | ✅ 完整 | ✅ 完整 | ✅ 完整 |
| **多端同步** | ❌ | ✅ | ✅ | ✅ |
| **飞书集成** | ⚠️ 骨架 | ❌ | ❌ | ❌ |
| **离线能力** | ⚠️ 本地模型 | ❌ | ❌ | ✅ Nano |

### 3.2 差异化优势

OpenClaw-Android 相对竞品的独特优势：

1. **设备级自动化**: 通过 Accessibility Service 实现 AI 驱动的设备操控，这是所有竞品 App 都不具备的
2. **本地 LLM 推理**: Gemma 4 E4B 支持离线运行，隐私敏感场景有优势
3. **开放技能架构**: 插件式技能系统，可扩展性强
4. **通知智能处理**: ML 分类 + LLM 理解的通知管理，竞品无此功能
5. **多模型切换**: 设计上支持多 Provider，比单一模型 App 灵活

### 3.3 核心短板

1. **多模态能力**: 缺少图片/语音输入，是最大体验短板
2. **记忆系统**: 向量搜索未实现，长期记忆形同虚设
3. **UI 精致度**: 对话界面交互不如竞品流畅和美观
4. **多端同步**: 无云同步，换设备即丢失所有数据

---

## 4. 后续规划

### M1 — 基础稳固 (3 周)

> 目标: 修复所有 P0 缺陷，确保核心功能可用

| # | 任务 | 优先级 | 预估 | 依赖 |
|---|------|--------|------|------|
| M1-1 | 集成 sqlite-vec 到 Room，实现向量搜索 | P0 | 5d | sqlite-vec Android 兼容性验证 |
| M1-2 | 集成 MiniLM-L6-v2 TFLite 模型 (45MB) | P0 | 2d | 模型下载 + 量化 |
| M1-3 | 实现 OpenAI 客户端 (GPT-4o/GPT-4o-mini) | P0 | 3d | OpenAI API 文档 |
| M1-4 | 实现 Anthropic 客户端 (Claude Sonnet) | P0 | 3d | Anthropic API 文档 |
| M1-5 | 配置 Release 签名 | P0 | 0.5d | Keystore 创建 |
| M1-6 | 搭建 GitHub Actions CI/CD | P0 | 2d | - |
| M1-7 | 修复本地模型路径硬编码 | P1 | 1d | - |
| M1-8 | 添加 Sentry/Flipper 崩溃报告 | P1 | 1d | - |

**M1 验收标准**:
- [ ] 向量搜索返回语义相关结果（准确率 > 70%）
- [ ] GPT-4o 和 Claude Sonnet 可正常对话
- [ ] CI 流水线自动运行测试
- [ ] Release APK 可签名安装

---

### M2 — 体验提升 (4 周)

> 目标: 缩小与竞品的用户体验差距

| # | 任务 | 优先级 | 预估 | 依赖 |
|---|------|--------|------|------|
| M2-1 | ChatScreen 重构: Markdown 渲染 + 代码高亮 | P1 | 3d | - |
| M2-2 | 多会话管理 UI (列表/搜索/删除) | P1 | 3d | - |
| M2-3 | 消息编辑/重发 | P1 | 2d | - |
| M2-4 | 图片附件支持 (拍照/相册) | P1 | 5d | 多模态 API 支持 |
| M2-5 | 语音输入 (Whisper API / 本地) | P1 | 5d | ASR 选型 |
| M2-6 | ML 通知分类实现 | P1 | 3d | 训练数据收集 |
| M2-7 | 本地模型下载引导 UI | P1 | 2d | CDN/下载服务 |
| M2-8 | 飞书集成核心功能 | P1 | 5d | 飞书 SDK |
| M2-9 | i18n 基础框架 (中文/英文) | P1 | 2d | - |
| M2-10 | API Key 安全显示 | P2 | 0.5d | - |

**M2 验收标准**:
- [ ] 对话界面支持 Markdown + 代码高亮
- [ ] 可创建/切换/搜索多个会话
- [ ] 图片+语音输入可用
- [ ] 飞书消息可收发
- [ ] 中英文切换

---

### M3 — 差异化竞争 (4 周)

> 目标: 发挥设备级 AI 优势，构建壁垒

| # | 任务 | 优先级 | 预估 | 依赖 |
|---|------|--------|------|------|
| M3-1 | 无障碍自动化增强 (UI 树分析) | P2 | 5d | Accessibility 深入研究 |
| M3-2 | 智能体编排 (多技能自动编排) | P2 | 5d | Agent 框架设计 |
| M3-3 | 相机实时理解 (多模态本地) | P2 | 5d | Gemma 多模态能力 |
| M3-4 | 新增技能: 文件管理/应用控制/剪贴板 | P2 | 3d | - |
| M3-5 | 对话云同步 (可选自建后端) | P2 | 5d | 后端服务 |
| M3-6 | Widget / 快捷指令 | P2 | 3d | - |
| M3-7 | 性能优化 (冷启动/内存/缓存) | P2 | 3d | Profiling |
| M3-8 | 多模态屏幕理解 (截图 + LLM) | P2 | 5d | 截图权限 |

**M3 验收标准**:
- [ ] 可通过自然语言操控任意 App
- [ ] 多技能自动编排完成复杂任务
- [ ] 相机实时场景理解
- [ ] 冷启动 < 2s

---

## 5. 技术路线

### 5.1 向量搜索实现方案

**方案对比**:

| 方案 | 优势 | 劣势 | 推荐度 |
|------|------|------|--------|
| **sqlite-vec** | Room 兼容，SQL 原生 | Android NDK 编译复杂 | ⭐⭐⭐⭐ |
| **ChromaDB Mobile** | 成熟向量库 | 依赖体积大 | ⭐⭐ |
| **自建暴力搜索** | 零依赖，简单 | O(n) 性能，记忆量大时慢 | ⭐⭐⭐ |
| **FAISS Mobile** | 高性能 | C++ 编译复杂 | ⭐⭐ |

**推荐方案**: 分阶段实施
1. **Phase 1** — 暴力余弦相似度搜索（记忆量 < 1000 时足够，实现成本低）
2. **Phase 2** — sqlite-vec 集成（记忆量增长后升级）

```kotlin
// Phase 1: 暴力搜索示例
fun searchBySimilarity(query: FloatArray, memories: List<Memory>, topK: Int = 5): List<Memory> {
    return memories
        .map { it to cosineSimilarity(query, it.embedding) }
        .sortedByDescending { it.second }
        .take(topK)
        .map { it.first }
}
```

### 5.2 LLM 客户端实现

**OpenAI 客户端**:

```kotlin
// 技术选型: 直接 OkHttp + SSE (与 BailianClient 架构一致)
// API: https://api.openai.com/v1/chat/completions
// 认证: Bearer Token
// 特殊处理: Streaming SSE, Tool Calling, Vision (base64 image)
```

**Anthropic 客户端**:

```kotlin
// 技术选型: 直接 OkHttp + SSE (与 BailianClient 架构一致)
// API: https://api.anthropic.com/v1/messages
// 认证: x-api-key header + anthropic-version header
// 特殊处理: Tool Use 格式不同, Content Block 流式
```

**关键差异**: Anthropic Messages API 格式与 OpenAI 不兼容，需独立序列化模型。建议复用 `ModelModels.kt` 中的通用部分，新建 `AnthropicModels.kt`。

### 5.3 多模态架构

```
用户输入 → InputRouter → 判断模态
  ├── 文本 → AgentSession (现有流程)
  ├── 图片 → ImagePreprocessor → Base64 / URL → LLM Vision API
  └── 语音 → ASR (Whisper API / 本地 Vosk) → 文本 → AgentSession

LLM 输出 → A2UI Parser → 渲染
```

**图片理解技术选型**:

| 方案 | 延迟 | 成本 | 质量 | 推荐度 |
|------|------|------|------|--------|
| GPT-4o Vision | ~3s | $0.01/图 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Claude Vision | ~4s | $0.008/图 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Qwen-VL (百炼) | ~2s | ¥0.01/图 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Gemma 多模态 | ~5s (本地) | 免费 | ⭐⭐⭐ | ⭐⭐⭐ |

**语音输入技术选型**:

| 方案 | 延迟 | 离线 | 质量 | 推荐度 |
|------|------|------|------|--------|
| OpenAI Whisper API | ~2s | ❌ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Android SpeechRecognizer | ~1s | ✅ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| Vosk (本地) | ~0.5s | ✅ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| 百炼 ASR | ~1.5s | ❌ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |

**推荐**: M2 阶段先用 Android 原生 `SpeechRecognizer`（零成本、支持离线），后续按需接入 Whisper API 提升质量。

### 5.4 CI/CD 流水线设计

```yaml
# .github/workflows/android.yml
name: Android CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'jetbrains'
          java-version: '21'
      - name: Run unit tests
        run: ./gradlew test
      - name: Run lint
        run: ./gradlew lint
      - name: Build debug APK
        run: ./gradlew assembleDebug

  release:
    needs: test
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - name: Build release APK
        run: ./gradlew assembleRelease
      - name: Upload artifacts
        uses: actions/upload-artifact@v4
```

### 5.5 Embedding 模型集成策略

**问题**: MiniLM-L6-v2 TFLite 模型 ~45MB，直接打包会增大 APK 体积。

**方案对比**:

| 方案 | APK 影响 | 用户体验 | 推荐度 |
|------|----------|----------|--------|
| 打包到 assets | +45MB | 开箱即用 | ⭐⭐⭐ |
| 首次启动下载 | +0MB | 需等待下载 | ⭐⭐⭐⭐ |
| 按需下载 | +0MB | 用到记忆功能时才下载 | ⭐⭐⭐⭐⭐ |
| 使用更小模型 (Msmarco-MiniLM-L5) | +25MB | 开箱即用 | ⭐⭐⭐ |

**推荐**: 按需下载 + 进度提示。首次使用记忆搜索时下载，缓存到内部存储。结合 P2 的模型下载引导 UI。

---

## 6. 风险评估

### 6.1 技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| sqlite-vec Android NDK 编译失败 | 中 | 高 | Phase 1 先用暴力搜索兜底 |
| Gemma 4 E4B 在低端机 OOM | 高 | 中 | 动态检测内存，建议最低配置 |
| OpenAI/Anthropic API 格式变更 | 低 | 中 | 抽象适配层，版本化 API |
| LLM Tool Calling 格式不兼容 | 高 | 高 | 统一 ToolDefinition 转换层 |
| TFLite 模型兼容性问题 | 中 | 中 | 测试主流设备，提供 CPU fallback |

### 6.2 项目风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 公共 API 限流/失效 (wttr.in, SearXNG) | 高 | 中 | 技能增加降级策略和备用 API |
| 隐私合规 (GDPR/个人信息保护法) | 中 | 高 | 隐私政策文档，数据最小化 |
| Google Play 审核风险 (无障碍权限) | 中 | 高 | 详细说明权限用途，考虑侧载分发 |
| 单人开发维护瓶颈 | 高 | 中 | 优先级严格管理，自动化测试 |

### 6.3 依赖风险

| 依赖 | 版本 | 风险等级 | 说明 |
|------|------|----------|------|
| Kotlin | 2.3.0 | 低 | 稳定版本 |
| LiteRT-LM | 0.10.0 | 中 | 较新库，API 可能变化 |
| Room | 2.7.2 | 低 | 成熟稳定 |
| OkHttp | 4.12.0 | 低 | 成熟稳定 |
| TFLite | 2.16.1 | 中 | 注意与 LiteRT 兼容性 |
| Compose BOM | 2025.03.00 | 低 | 官方推荐管理方式 |

---

## 附录 A: 与 Desktop TypeScript 版本差距

基于对项目文档的分析，以下是 Android 版相对 Desktop 版的主要差距：

| Desktop 功能 | Android 状态 | 差距 |
|-------------|-------------|------|
| 多 Provider 支持 | 仅 Bailian + Local | 缺 OpenAI/Anthropic |
| 持久化记忆 + 向量搜索 | 向量搜索未实现 | 核心缺失 |
| LLM 记忆自动提取 | 仅 Fallback 实现 | 质量差距 |
| 飞书集成 | 骨架代码 | 几乎未开始 |
| 会话压缩 | 基础实现 | 算法可优化 |
| 技能系统 | 8 个内置技能 | 功能对齐 |
| 多模态 | 不支持 | Desktop 版也可能不支持 |

---

## 附录 B: 建议的技术选型汇总

| 领域 | 推荐方案 | 备选方案 |
|------|----------|----------|
| 向量搜索 | 暴力搜索 → sqlite-vec | FAISS Mobile |
| Embedding | MiniLM-L6-v2 TFLite (按需下载) | 更小模型打包 |
| 图片理解 | Qwen-VL (百炼) → GPT-4o Vision | Gemma 多模态 |
| 语音识别 | Android SpeechRecognizer → Whisper API | Vosk 本地 |
| 崩溃报告 | Sentry (免费额度) | Firebase Crashlytics |
| CI/CD | GitHub Actions | - |
| i18n | Kotlinx I18N + strings.xml | - |

---

## 附录 C: 里程碑时间线

```
2026-04-08 ─── 项目现状 (master)
    │
    ├── M1: 基础稳固 (3 周) ─── 2026-04-29
    │   ├── 修复向量搜索 + Embedding
    │   ├── OpenAI/Anthropic 客户端
    │   └── CI/CD + 签名配置
    │
    ├── M2: 体验提升 (4 周) ─── 2026-05-27
    │   ├── ChatScreen 重构
    │   ├── 图片/语音输入
    │   ├── 飞书集成
    │   └── ML 通知分类
    │
    └── M3: 差异化竞争 (4 周) ─── 2026-06-24
        ├── 智能体编排
        ├── 无障碍增强
        ├── 多模态屏幕理解
        └── 性能优化
```

---

> 本报告基于代码静态分析生成，建议结合实际运行测试验证各功能状态。
