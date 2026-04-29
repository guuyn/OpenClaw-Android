# OpenClaw-Android 可测试性架构改进规划

> **文档状态**: 草案  
> **创建日期**: 2026-04-28  
> **作者**: 架构师 (Architect)  
> **适用范围**: OpenClaw-Android 全模块

---

## 1. 架构分析报告

### 1.1 当前可测试性评分

| 维度 | 评分 (1-10) | 说明 |
|------|:-----------:|------|
| 单元测试覆盖率 | **5** | 有 A2UICardModelsTest、A2UICardRendererTest 等，但仅覆盖数据模型层；业务逻辑层（AgentSession、SkillManager）有测试，但 UI 层几乎空白 |
| 依赖可替换性 | **2** | `GatewayContract` 直接绑定 `GatewayService`，无接口抽象；`ConfigManager` 是单例静态访问，无法注入 Mock |
| 状态隔离度 | **2** | `messages` 状态在 `MainScreen` Composable 的 `remember { mutableStateListOf() }` 中，无法从外部注入测试数据 |
| 错误边界 | **4** | A2UIComposeRenderer 有 `runCatching` 和 `A2UIFallbackCard` 降级，但 ChatScreen 中 `mapCardActionToMessage` 等纯函数缺少异常保护 |
| 测试入口 | **3** | 有 ADB Broadcast 注入（`INJECT_A2UI_TEST`），但依赖 shell 传参，JSON 转义问题未解决；无内置测试模式 |
| 架构分层 | **3** | `ChatViewModel.kt` 已存在且设计合理，但 `MainActivity.kt` 中的 `MainScreen` 完全绕过了它，直接用 Composable 状态 |
| **综合评分** | **3.2/10** | 严重不足，需系统性改进 |

### 1.2 主要缺陷分析

#### D1: 状态层与 UI 层耦合（严重）

**现状**: `MainActivity.kt` 中 `MainScreen` Composable 直接持有 `messages` 状态：

```kotlin
// MainActivity.kt:233
val messages = remember { mutableStateListOf<ChatMessage>() }
```

广播接收器也在同一个 Composable 的 `DisposableEffect` 中注册。这意味着：
- 无法在不启动完整 Activity 的情况下测试消息渲染
- 无法注入 Mock 消息列表验证 UI
- `ChatViewModel.kt` 已存在但未被使用（死代码）

**影响**: 测试任何 UI 行为都需要完整的运行时链。

#### D2: 无测试模式 / Mock 网关（严重）

**现状**: 没有 `TestMode` 标志、没有 `MockGateway` 实现。QA 验证 A2UI 渲染必须：
1. 发送 ADB 广播
2. 广播触发 `sendMessage()`
3. `sendMessage()` 调用 `GatewayContract.sendMessage()`
4. Gateway 调用 AI 网关 → LLM
5. LLM 返回 → 解析 → 渲染

5 层耦合，任何一层失败（网络、API Key 过期、LLM 返回格式变化）都会导致测试失败。

**影响**: 自动化测试不可靠，手动测试效率极低。

#### D3: 数据注入方式脆弱（中等）

**现状**: 三种广播意图，各有问题：

| 广播 Action | 问题 |
|---|---|
| `DEBUG_SEND_MESSAGE` | 只传纯文本，无法测试 A2UI |
| `TEST_A2UI_JSON` | JSON 作为 `getStringExtra` 传递，被 shell 转义破坏 |
| `INJECT_A2UI_TEST` | 支持文件回退，但文件路径权限问题（`/sdcard/test_a2ui.json` 需要存储权限） |

#### D4: 错误边界不完整（中等）

**现状**: 
- ✅ `A2UIComposeRenderer` 有 `runCatching` + `A2UIFallbackCard` 降级
- ❌ `ComponentRegistry.render()` 无 try-catch，组件渲染异常直接崩溃
- ❌ `A2UICardParser.parse()` 对非法 JSON 的 fallback 行为未测试
- ❌ `mapCardActionToMessage()` 是纯函数（可测试），但无单元测试覆盖

#### D5: 代码重复（低）

**现状**: `parseAgentResponse()` 函数在 `MainActivity.kt` 和 `ChatViewModel.kt` 中各有一份几乎相同的实现。

---

### 1.3 目标架构

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                          │
│  ┌─────────────────┐    ┌───────────────────────────┐   │
│  │   MainScreen     │───▶│    ChatViewModel           │   │
│  │  (Composable)    │    │  StateFlow<List<Msg>>      │   │
│  │  只负责 UI 渲染   │    │  StateFlow<Boolean>        │   │
│  └─────────────────┘    │  StateFlow<Deliverable?>    │   │
│                         └──────────┬──────────────────┘   │
│                                    │                      │
│  ┌─────────────────┐    ┌──────────▼──────────────────┐   │
│  │  TestMode       │───▶│    MessageGateway (接口)     │   │
│  │  (Settings 开关) │    │  ┌──────────┬───────────┐   │   │
│  └─────────────────┘    │  │ RealImpl │ MockImpl   │   │   │
│                         │  │ (AI网关) │ (离线数据) │   │   │
│                         │  └──────────┴───────────┘   │   │
│                         └─────────────────────────────┘   │
│                                                           │
│  ┌───────────────────────────────────────────────────┐   │
│  │  ADB BroadcastReceiver (独立模块)                   │   │
│  │  - 读取 /data/data/pkg/files/test_input.json       │   │
│  │  - 直接注入 ViewModel 的 testInject() 方法         │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

**核心原则**:
1. **状态上收**: 所有聊天状态由 `ChatViewModel` 管理，Composable 只消费 StateFlow
2. **网关抽象**: `MessageGateway` 接口，`RealGateway` 和 `MockGateway` 两种实现
3. **测试模式**: Settings 页开关，开启后使用 MockGateway + 内置测试数据
4. **ADB 改进**: 通过文件 + ViewModel 直接注入，绕过 AI 网关

---

## 2. 阶梯演进计划

### 阶段一：短期（1-2 周）— 快速见效

> **目标**: 不改动核心架构，用最小成本解决最痛的测试问题

#### 任务 1.1: 错误边界保护

**内容**:
- 在 `ComponentRegistry.render()` 外层包裹 `try-catch`，渲染失败时显示降级卡片
- 为 `A2UICardParser.parse()` 添加对 malformed JSON 的防御性处理
- 确保任何组件渲染异常都不会导致整个消息列表崩溃

**文件变更**:
- `ComponentRegistry.kt` — `render()` 方法加 try-catch
- `A2UICardModels.kt` — `parse()` 方法增强异常处理

**验收标准**:
- [ ] 传入非法 JSON 时，消息气泡显示降级卡片而非崩溃
- [ ] 单个组件渲染失败不影响列表中其他消息
- [ ] 已有 0 个崩溃回归

**成本**: 0.5 天  
**ROI**: 高 — 直接解决 QA 验证时的崩溃问题

---

#### 任务 1.2: 测试模式开关

**内容**:
- 在 `SettingsScreen` 添加 "测试模式" 开关（默认关闭）
- 开启后，`MainScreen` 使用内置 Mock 消息列表而非实时通信
- 内置 5 种典型测试场景的 Mock 数据：
  1. 纯文本回复
  2. 天气卡片（v2 格式）
  3. A2UI 标准协议（v0.10 JSONL）
  4. 混合内容（文本 + 卡片 + RichContent）
  5. 错误卡片

**文件变更**:
- `SettingsScreen.kt` — 添加测试模式开关
- `MainActivity.kt` — 根据测试模式切换消息源
- 新增 `MockDataProvider.kt` — 内置测试数据

**验收标准**:
- [ ] Settings 页有 "测试模式" 开关
- [ ] 开启后自动加载 Mock 消息列表
- [ ] 关闭后恢复正常通信
- [ ] 无需网络 / API Key 即可验证 UI

**成本**: 1 天  
**ROI**: 极高 — QA 可离线验证所有 UI 场景

---

#### 任务 1.3: ADB 测试入口优化

**内容**:
- 修复 `INJECT_A2UI_TEST` 广播的文件读取问题
- 使用应用私有目录（`/data/data/ai.openclaw.android/files/`）替代 `/sdcard/`
- 提供配套的 shell 脚本，自动处理 JSON 转义

**文件变更**:
- `MainActivity.kt` — 改进广播接收器的文件路径
- 新增 `scripts/inject_a2ui_test.sh` — 一键注入测试数据

**ADB 使用方式**:
```bash
# 方式1: 通过应用私有目录（推荐）
adb shell 'echo "{\"type\":\"weather\",...}" > /data/data/ai.openclaw.android/files/test_input.json'
adb shell 'am broadcast -a ai.openclaw.android.INJECT_A2UI_TEST --es a2ui_file "/data/data/ai.openclaw.android/files/test_input.json"'

# 方式2: 通过 push + 广播（更简单）
adb push test_card.json /data/data/ai.openclaw.android/files/test_input.json
adb shell 'am broadcast -a ai.openclaw.android.INJECT_A2UI_TEST'
```

**验收标准**:
- [ ] JSON 文件正确读取，无转义问题
- [ ] 注入的消息直接显示在聊天列表中
- [ ] 配套脚本可在 Linux/macOS 上运行

**成本**: 0.5 天  
**ROI**: 高 — 解决当前最频繁的测试痛点

---

### 阶段二：中期（1-2 月）— 架构改进

> **目标**: 重构状态管理，引入网关抽象，实现可测试架构

#### 任务 2.1: 状态层分离（ViewModel + State）

**内容**:
- 将 `MainScreen` 中的 `messages`、`isLoading`、`lastDeliverable` 等状态迁移到 `ChatViewModel`
- `ChatViewModel` 已存在但未被使用，需要适配 `MainScreen`
- Composable 通过 `collectAsStateWithLifecycle()` 消费状态
- 广播接收器从 Composable 移到 Activity 层，直接调用 ViewModel

**架构对比**:

```
Before:                          After:
┌──────────────┐                ┌──────────────┐
│  MainScreen   │                │  MainScreen   │
│  (UI + State) │                │  (UI Only)    │
│               │                │       │       │
│  messages ────┼───❌ 耦合       │       │ StateFlow
│  isLoading ───┤                │       ▼       │
│  broadcast ───┤                │  ChatViewModel│
└──────────────┘                │  (State + Biz)│
                                └───────┬───────┘
                                        │
                                ┌───────▼───────┐
                                │ MessageGateway│
                                └───────────────┘
```

**文件变更**:
- `ChatViewModel.kt` — 完善状态管理，添加 `testInject()` 方法
- `MainActivity.kt` — `MainScreen` 改为消费 ViewModel 状态
- `MainScreen.kt` — 从 MainActivity 中拆出（可选，降低文件复杂度）

**验收标准**:
- [ ] `MainScreen` 不再直接持有 `messages` 状态
- [ ] 所有聊天状态通过 `ChatViewModel` 管理
- [ ] 广播注入直接调用 `viewModel.testInject()`
- [ ] 现有功能无回归

**成本**: 3-5 天  
**ROI**: 极高 — 架构基础改进，后续所有测试改进的前提

---

#### 任务 2.2: Mock 网关实现

**内容**:
- 定义 `MessageGateway` 接口：
  ```kotlin
  interface MessageGateway {
      fun sendMessage(text: String, images: List<ImageContent>?): Flow<SessionEvent>
  }
  ```
- `RealGateway` — 当前 `GatewayContract` 的包装实现
- `MockGateway` — 返回预设的 Mock 响应，支持配置：
  - 固定文本回复
  - 固定 A2UI 卡片回复
  - 延迟模拟（测试 loading 状态）
  - 错误模拟（测试错误边界）

**文件变更**:
- 新增 `MessageGateway.kt` — 接口定义
- 新增 `MockGateway.kt` — Mock 实现
- `ChatViewModel.kt` — 接受 `MessageGateway` 依赖注入

**验收标准**:
- [ ] `MockGateway` 可返回预设响应
- [ ] 测试模式下使用 `MockGateway`
- [ ] 正常模式下使用 `RealGateway`
- [ ] 可通过配置切换行为

**成本**: 2-3 天  
**ROI**: 极高 — 实现离线测试 AI 响应

---

#### 任务 2.3: A2UI 渲染器独立测试

**内容**:
- 为 `A2UIComposeRenderer` 编写单元测试（JVM 层，非 UI 测试）
- 测试覆盖：
  - 标准协议解析（v0.8/v0.9/v0.10）
  - 旧格式转换
  - JSONL 分割
  - 错误降级
- 为 `ComponentRegistry` 编写单元测试
- 测试覆盖：
  - 各组件类型渲染
  - 深度嵌套（超过 MAX_RENDER_DEPTH）
  - 非法属性处理

**文件变更**:
- 新增 `A2UIComposeRendererTest.kt`（app module）
- 新增 `ComponentRegistryTest.kt`（android_compose module）

**验收标准**:
- [ ] A2UI 渲染核心逻辑单元测试覆盖率 ≥ 80%
- [ ] 所有协议版本都有测试用例
- [ ] 错误降级路径有测试覆盖

**成本**: 3-4 天  
**ROI**: 高 — A2UI 是核心功能，测试覆盖可防止回归

---

### 阶段三：长期（3-6 月）— 测试框架

> **目标**: 建立完整的测试体系，覆盖单元、集成、UI 三层

#### 任务 3.1: 单元测试覆盖

**内容**:
- 补充缺失的单元测试：
  - `mapCardActionToMessage()` — 纯函数，应有完整测试
  - `parseAgentResponse()` — 去重后统一实现 + 测试
  - `A2UIComposeRenderer` 的协议检测逻辑
  - `MessageGateway` 接口实现
- 建立测试数据工厂（Test Data Factory）
- 引入 `mockk` 替代部分手动 Mock

**验收标准**:
- [ ] 核心业务逻辑单元测试覆盖率 ≥ 70%
- [ ] 所有纯函数都有测试
- [ ] 测试数据可复用

**成本**: 5-7 天  
**ROI**: 高 — 防止回归，提升代码质量

---

#### 任务 3.2: 集成测试

**内容**:
- 端到端消息渲染流程测试：
  1. MockGateway 返回 A2UI 响应
  2. ChatViewModel 处理响应
  3. 状态更新到 StateFlow
  4. Composable 消费状态
- 使用 `MockGateway` + `TestDispatcher` 控制时序
- 验证完整链路的数据流

**验收标准**:
- [ ] 至少 5 个集成测试场景
- [ ] 覆盖正常流程 + 错误流程
- [ ] 测试可在 CI 上运行

**成本**: 4-6 天  
**ROI**: 高 — 验证端到端流程正确性

---

#### 任务 3.3: UI 测试（Compose Testing）

**内容**:
- 扩展现有的 `ChatScreenTest.kt`：
  - 消息列表渲染测试（含 A2UI 卡片）
  - 输入框交互测试
  - 发送按钮状态测试
  - Loading 状态测试
  - 错误卡片显示测试
- 新增 `A2UIRenderTest.kt` — Compose UI 测试
- 使用 `createComposeRule` + `setContent` 隔离测试

**验收标准**:
- [ ] UI 测试覆盖核心交互路径
- [ ] 测试可在 CI 上运行（无需真机）
- [ ] 测试稳定，无 flaky

**成本**: 5-7 天  
**ROI**: 中 — UI 测试维护成本高，但能捕获视觉回归

---

## 3. 实施路线图

### 3.1 优先级矩阵

```
                    高影响
                      │
          低成本       │        高成本
      ┌───────────────┼───────────────┐
      │               │               │
      │  ★ 任务 1.2   │  任务 2.1     │
      │  测试模式开关  │  状态层分离    │
      │  (1天)        │  (3-5天)      │
      │               │               │
  影  ├───────────────┼───────────────┤ 影
  响      低影响                 高影响
      │               │               │
      │  任务 1.1     │  任务 2.2     │
      │  错误边界     │  Mock 网关    │
      │  (0.5天)      │  (2-3天)      │
      │               │               │
      │  任务 1.3     │  任务 2.3     │
      │  ADB 优化     │  A2UI 测试    │
      │  (0.5天)      │  (3-4天)      │
      │               │               │
      └───────────────┼───────────────┘
                    低成本              高成本
```

### 3.2 阶段依赖关系

```
阶段一（短期）          阶段二（中期）          阶段三（长期）
┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│ 1.1 错误边界 │──────▶│ 2.1 状态分离 │──────▶│ 3.1 单元测试 │
│ 1.2 测试模式 │       │ 2.2 Mock网关 │       │ 3.2 集成测试 │
│ 1.3 ADB优化 │──────▶│ 2.3 A2UI测试 │──────▶│ 3.3 UI 测试  │
└─────────────┘       └─────────────┘       └─────────────┘
      │                       │                       │
      └─── 可并行实施 ────────┘    └─── 依赖阶段二 ────┘
```

**依赖说明**:
- 阶段一所有任务可并行实施，互不依赖
- 阶段二需要阶段一完成（至少 1.2 测试模式开关）
- 阶段三依赖阶段二的架构改进（ViewModel + MockGateway）

### 3.3 详细任务清单

| 阶段 | 任务 | 优先级 | 预估工时 | 前置依赖 | 验收标准 |
|:----:|------|:------:|:--------:|----------|----------|
| 一 | 1.1 错误边界保护 | P1 | 0.5d | 无 | 非法输入不崩溃 |
| 一 | 1.2 测试模式开关 | P0 | 1d | 无 | Settings 有开关，离线可测 |
| 一 | 1.3 ADB 测试入口优化 | P1 | 0.5d | 无 | JSON 注入可靠 |
| 二 | 2.1 状态层分离 | P0 | 3-5d | 1.2 | ViewModel 管理状态 |
| 二 | 2.2 Mock 网关实现 | P1 | 2-3d | 2.1 | 离线返回预设响应 |
| 二 | 2.3 A2UI 渲染器测试 | P2 | 3-4d | 2.1 | 覆盖率 ≥ 80% |
| 三 | 3.1 单元测试覆盖 | P1 | 5-7d | 2.2 | 覆盖率 ≥ 70% |
| 三 | 3.2 集成测试 | P2 | 4-6d | 2.2 | 5+ 端到端场景 |
| 三 | 3.3 UI 测试 | P3 | 5-7d | 2.1 | 核心路径覆盖 |

---

## 4. 每个阶段的成功指标

### 阶段一成功指标

| 指标 | 当前值 | 目标值 | 测量方式 |
|------|:------:|:------:|----------|
| QA 验证 A2UI 所需步骤 | 5 步（含网络） | 1 步（开关切换） | 手动计时 |
| 渲染崩溃次数/天 | ~3 次 | 0 次 | Crashlytics / logcat |
| ADB 注入成功率 | ~60%（转义问题） | ≥ 95% | 10 次测试统计 |
| 离线可测试场景数 | 0 | ≥ 5 | 场景清单 |

### 阶段二成功指标

| 指标 | 当前值 | 目标值 | 测量方式 |
|------|:------:|:------:|----------|
| 状态管理集中度 | 0%（Composable 内） | 100%（ViewModel） | 代码审查 |
| Mock 网关可用 | ❌ | ✅ | 功能验证 |
| A2UI 渲染测试覆盖率 | ~30% | ≥ 80% | JaCoCo 报告 |
| 测试执行时间（A2UI） | ~5 min（手动） | < 30 sec（自动） | 计时 |

### 阶段三成功指标

| 指标 | 当前值 | 目标值 | 测量方式 |
|------|:------:|:------:|----------|
| 单元测试覆盖率 | ~40% | ≥ 70% | JaCoCo 报告 |
| 集成测试场景数 | 0 | ≥ 5 | 测试清单 |
| UI 测试场景数 | ~4 | ≥ 15 | 测试清单 |
| CI 测试通过率 | N/A | ≥ 95% | CI 报告 |
| 测试 flaky 率 | N/A | < 5% | CI 统计 |

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|:----:|:----:|----------|
| 状态迁移引入回归 | 中 | 高 | 分步迁移，每步回归测试 |
| MockGateway 覆盖不全 | 中 | 中 | 从高频场景开始，逐步扩展 |
| 团队时间不足 | 高 | 中 | 阶段一优先，阶段二三可延后 |
| Compose 测试不稳定 | 中 | 中 | 先做 JVM 单元测试，UI 测试最后 |
| ADB 注入安全顾虑 | 低 | 高 | 测试模式仅 debug build 可用 |

---

## 6. 附录

### 6.1 架构对比图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        当前架构 (Before)                             │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ ADB 广播  │───▶│MainScreen│───▶│Gateway   │───▶│ AI 网关 / LLM│  │
│  │ (JSON转义)│    │(UI+State)│    │Contract  │    │  (网络请求)   │  │
│  └──────────┘    └──────────┘    └──────────┘    └──────────────┘  │
│                        │                                            │
│                        ▼                                            │
│              ┌──────────────────┐                                   │
│              │  消息列表渲染     │  ← 无法隔离测试                    │
│              │  (A2UI + 气泡)   │                                   │
│              └──────────────────┘                                   │
│                                                                     │
│  问题: 5层耦合，无测试模式，无 Mock，状态在 Composable 中              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        目标架构 (After)                              │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────────────────────────┐  │
│  │ ADB 广播  │───▶│Activity  │    │      ChatViewModel            │  │
│  │ (文件注入) │    │(入口层)   │    │  ┌────────────────────────┐  │  │
│  └──────────┘    └────┬─────┘    │  │ StateFlow<List<Msg>>    │  │  │
│                       │          │  │ StateFlow<Boolean>       │  │  │
│                       │          │  │ testInject() 方法        │  │  │
│                       │          │  └────────┬────────────────┘  │  │
│                       │          └───────────┼───────────────────┘  │
│                       │                      │                      │
│                       ▼                      ▼                      │
│              ┌──────────────────┐   ┌──────────────────────────┐   │
│              │  MainScreen      │   │  MessageGateway (接口)    │   │
│              │  (UI Only)       │   │  ┌────────┬───────────┐  │   │
│              │  消费 StateFlow  │   │  │ Real   │  Mock     │  │   │
│              └──────────────────┘   │  │ Gateway│  Gateway  │  │   │
│                                     │  └────────┴───────────┘  │   │
│              ┌──────────────────┐   └──────────┬───────────────┘   │
│              │  消息列表渲染     │              │                   │
│              │  (A2UI + 气泡)   │              ▼                   │
│              └──────────────────┘   ┌──────────────────┐          │
│                                     │  AI 网关 / LLM   │          │
│                                     └──────────────────┘          │
│                                                                     │
│  改进: 状态上收、网关抽象、测试模式、ADB 文件注入、错误边界              │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 测试数据示例（MockDataProvider）

```kotlin
object MockDataProvider {
    // 场景1: 纯文本回复
    val plainText = ChatMessage(
        role = "assistant",
        content = "你好！我是 OpenClaw，有什么可以帮你的？"
    )

    // 场景2: 天气卡片 (v2)
    val weatherCard = ChatMessage(
        role = "assistant",
        content = "西安的天气如下：\n" +
            "[A2UI]{\"type\":\"weather\",\"data\":{\"title\":\"西安天气\",\"city\":\"西安\"," +
            "\"condition\":\"多云\",\"temperature\":\"14\",\"feelsLike\":\"12\"," +
            "\"humidity\":\"45\",\"wind\":\"东南风 3级\"}," +
            "\"actions\":[{\"label\":\"设置提醒\",\"action\":\"set_reminder\",\"style\":\"Primary\"}]}" +
            "[/A2UI]"
    )

    // 场景3: A2UI 标准协议 (v0.10)
    val standardProtocol = ChatMessage(
        role = "assistant",
        content = "[A2UI]{\"version\":\"v0.10\"," +
            "\"createSurface\":{\"surfaceId\":\"test_001\",\"catalogId\":\"https://a2ui.org/specification/v0_10/standard_catalog.json\"}," +
            "\"updateComponents\":{\"surfaceId\":\"test_001\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":{\"array\":[\"header\",\"body\"]}}," +
            "{\"id\":\"header\",\"component\":\"Text\",\"text\":\"测试卡片\",\"variant\":\"h4\"}," +
            "{\"id\":\"body\",\"component\":\"Text\",\"text\":\"这是一条测试数据\",\"variant\":\"body\"}" +
            "]}}" +
            "[/A2UI]"
    )

    // 场景4: 错误卡片
    val errorCard = ChatMessage(
        role = "assistant",
        content = "[A2UI]{\"type\":\"error\",\"data\":{\"icon\":\"warning\",\"title\":\"操作失败\"," +
            "\"message\":\"无法连接到服务器\",\"suggestion\":\"请检查网络设置\"}}" +
            "[/A2UI]"
    )

    // 场景5: 混合内容
    val mixedContent = ChatMessage(
        role = "assistant",
        content = "以下是搜索结果：\n" +
            "[A2UI]{\"type\":\"search_result\",\"data\":{\"title\":\"搜索结果\",\"query\":\"OpenClaw\"," +
            "\"items\":[{\"title\":\"OpenClaw 项目\",\"url\":\"https://example.com\",\"snippet\":\"AI assistant\",\"source\":\"GitHub\"}]}}" +
            "[/A2UI]\n\n需要我帮你做更多搜索吗？"
    )

    fun getAllScenarios(): List<ChatMessage> = listOf(
        ChatMessage(role = "user", content = "你好"),
        plainText,
        ChatMessage(role = "user", content = "西安天气怎么样"),
        weatherCard,
        ChatMessage(role = "user", content = "测试 A2UI"),
        standardProtocol,
        ChatMessage(role = "user", content = "出个错看看"),
        errorCard,
        ChatMessage(role = "user", content = "搜索 OpenClaw"),
        mixedContent
    )
}
```

### 6.3 配套脚本示例

```bash
#!/bin/bash
# scripts/inject_a2ui_test.sh
# 用法: ./inject_a2ui_test.sh <json_file>
# 将 A2UI JSON 注入到 OpenClaw-Android 应用

set -e

PKG="ai.openclaw.android"
REMOTE_PATH="/data/local/tmp/test_a2ui.json"
APP_PATH="/data/data/${PKG}/files/test_input.json"

if [ -z "$1" ]; then
    echo "用法: $0 <json_file>"
    echo "示例: $0 test_weather.json"
    exit 1
fi

JSON_FILE="$1"

if [ ! -f "$JSON_FILE" ]; then
    echo "错误: 文件不存在: $JSON_FILE"
    exit 1
fi

echo "→ Pushing JSON to device..."
adb push "$JSON_FILE" "$REMOTE_PATH"

echo "→ Copying to app private directory..."
adb shell "cp $REMOTE_PATH $APP_PATH"

echo "→ Sending broadcast..."
adb shell "am broadcast -a ai.openclaw.android.INJECT_A2UI_TEST \
    --es a2ui_file '$APP_PATH'"

echo "→ Done! Check the app for the injected message."
```

---

## 7. 总结

本规划以 **务实、渐进** 为原则，分三个阶段提升 OpenClaw-Android 的可测试性：

1. **短期**（2 周）：用最小成本解决最痛的测试问题 — 错误边界、测试模式、ADB 注入
2. **中期**（1-2 月）：重构状态管理，引入网关抽象，实现可测试架构
3. **长期**（3-6 月）：建立完整的测试体系，覆盖单元、集成、UI 三层

**关键决策**:
- ✅ 优先使用已有的 `ChatViewModel`，而非从零创建
- ✅ Mock 网关从高频场景开始，逐步扩展
- ✅ 测试模式仅 debug build 可用，不影响生产
- ✅ ADB 注入使用文件而非 shell 参数，避免转义问题

**预期效果**: 可测试性评分从 **3.2/10** 提升至 **7.5+/10**。
