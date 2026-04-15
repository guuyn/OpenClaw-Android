# 多 Agent 路由系统设计

> **创建时间**: 2026-04-15  
> **状态**: 设计确认 ✅  
> **方案**: 配置驱动 + LLM 动态路由

---

## 一、核心设计

### 1.1 配置驱动

Agent 定义存储在 `assets/agents.json`，格式与 OpenClaw `openclaw.json` 一致：

```json
{
  "agents": [
    {
      "id": "main",
      "name": "OpenClaw",
      "model": "bailian/qwen3.5-plus",
      "systemPrompt": "你是一个 Android 设备上的 AI 助手...",
      "tools": ["all"],
      "isDefault": true
    },
    {
      "id": "coder",
      "name": "Coder",
      "model": "bailian/qwen3.5-coder",
      "systemPrompt": "你是一个 Android 开发助手，精通 Kotlin、Compose、Gradle...",
      "tools": ["script", "search", "file"],
      "keywords": ["代码", "Java", "Kotlin", "build", "PR", "commit", "gradle", "bug"]
    },
    {
      "id": "security",
      "name": "Security",
      "model": "bailian/qwen3.5-plus",
      "systemPrompt": "你是一个 Android 安全审计专家...",
      "tools": ["search", "audit"],
      "keywords": ["安全", "漏洞", "审计", "权限", "加密", "SQL injection"]
    }
  ]
}
```

### 1.2 路由流程

```
用户消息
  ↓
[AgentRouter.route()]
  ├─ 关键词匹配（快速路径）
  │   └─ 命中 → 返回 Agent ID
  └─ 未命中 → 返回默认 Agent (main)
  ↓
[AgentSessionManager.getOrCreate(agentId)]
  ├─ 已存在 → 复用
  └─ 不存在 → 从配置创建
  ↓
[AgentSession.handleMessageStream()]
```

### 1.3 关键决策

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 路由方式 | 关键词 + LLM 扩展 | Phase 1 用关键词（零成本），后续可加 LLM 路由 |
| Agent 创建 | 按需延迟创建 | 不需要预热所有 Agent |
| 模型切换 | 每个 Agent 独立 ModelClient | Coder 用 qwen3.5-coder，其他用 qwen3.5-plus |
| 工具过滤 | 基于 tool 名称前缀 | SkillManager 已支持 `skillId_toolName` 格式 |
| 上下文隔离 | 独立 AgentSession 实例 | 每个 Agent 有独立 history |
| 配置持久化 | assets 只读 + 运行时可覆盖 | 默认配置在 assets，用户可运行时增删 |

---

## 二、架构组件

### 2.1 新增文件

| 文件 | 职责 |
|------|------|
| `data/model/AgentConfig.kt` | Agent 配置数据模型（序列化） |
| `domain/agent/AgentConfigManager.kt` | 加载/保存 agents.json |
| `domain/agent/AgentRouter.kt` | 消息路由（关键词匹配） |
| `domain/agent/AgentSessionManager.kt` | 多 AgentSession 生命周期管理 |

### 2.2 修改文件

| 文件 | 变更 |
|------|------|
| `agent/AgentSession.kt` | 新增 factory constructor，接受 AgentConfig |
| `GatewayManager.kt` | 替换单一 AgentSession → AgentSessionManager，接入 Router |
| `GatewayContract.kt` | 接口增加 `getAvailableAgents()` 方法 |

### 2.3 不修改

| 文件 | 原因 |
|------|------|
| `skill/SkillManager.kt` | 工具过滤由 AgentSession 层处理 |
| `MainActivity.kt` | UI 层无感知 |
| `ChatViewModel.kt` | 通过 GatewayContract 间接调用 |

---

## 三、数据流

### 3.1 启动时

```
GatewayManager.start()
  → AgentConfigManager.loadFromAssets()  // 读取 agents.json
  → AgentRouter(agentConfigs)            // 初始化路由器
  → AgentSessionManager(factory)         // 初始化会话管理器
  → sessionManager.getOrCreate("main")   // 创建默认 Agent
```

### 3.2 处理消息时

```
ChatViewModel.sendMessage(text)
  → GatewayContract.sendMessage(text)
    → AgentRouter.route(text)            // 返回 agentId
    → sessionManager.getOrCreate(agentId) // 获取/创建 AgentSession
    → agentSession.handleMessageStream(text)
```

### 3.3 创建新 Agent

```
用户: "创建一个翻译 Agent"
  → Main Agent 解析意图
  → 调用 AgentConfigManager.createAgent(newConfig)
  → 配置持久化
  → 下次路由自动生效
```

---

## 四、Phase 1 范围

**本期只做关键词路由，不实现 LLM 路由。**

| 包含 | 不包含 |
|------|--------|
| AgentConfig 数据模型 + JSON 加载 | LLM 动态路由 |
| 关键词匹配路由 | Agent 间通信/委托 |
| 多 AgentSession 创建 + 上下文隔离 | 运行时 Agent 创建 UI |
| 工具过滤（基于工具前缀） | 跨 Agent 共享记忆 |
| 默认 3 个 Agent 配置 | 动态创建 Agent 功能 |

**Phase 2 再做：**
- LLM 动态路由（调用模型判断意图）
- Agent 间通信（Main → Coder → 返回结果）
- 跨 Agent 记忆共享
- 用户界面管理 Agent

---

## 五、风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 多 ModelClient 增加内存 | 每个 Agent 持有 OkHttpClient | 延迟创建 + LRU 缓存，最多保留 3 个 |
| 关键词误匹配 | 用户消息被错误路由 | 支持 @ 语法强制指定（如 @coder） |
| 上下文切换丢失 | 用户在不同 Agent 间切换时丢失上下文 | 每个 Agent 独立 history，切换时提示 |
