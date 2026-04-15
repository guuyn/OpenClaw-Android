# Multi-Agent Routing — Implementation Plan

> **Phase**: 1 (keyword routing + config-driven agents)
> **Date**: 2026-04-15
> **Design**: `docs/plans/2026-04-15-multi-agent-routing-design.md`

---

## Task 1: AgentConfig data model + agents.json

**Test:** `AgentConfigTest.kt` — 验证 JSON 序列化/反序列化
**Files to create:**
- `app/src/main/java/ai/openclaw/android/data/model/AgentConfig.kt`
- `app/src/main/assets/agents.json`
- `app/src/test/java/ai/openclaw/android/data/model/AgentConfigTest.kt`

**AgentConfig 结构:**
```kotlin
data class AgentConfig(
    val id: String,
    val name: String,
    val model: String = "bailian/qwen3.5-plus",
    val systemPrompt: String? = null,
    val tools: List<String> = listOf("all"),
    val keywords: List<String> = emptyList(),
    val isDefault: Boolean = false
)

data class AgentRegistry(
    val agents: List<AgentConfig>
)
```

**agents.json 默认内容:** 3 个 Agent（main / coder / security）

---

## Task 2: AgentConfigManager

**Test:** `AgentConfigManagerTest.kt` — 验证从 assets 加载，默认配置正确
**Files to create:**
- `app/src/main/java/ai/openclaw/android/domain/agent/AgentConfigManager.kt`
- `app/src/test/java/ai/openclaw/android/domain/agent/AgentConfigManagerTest.kt`

**API:**
```kotlin
class AgentConfigManager(context: Context) {
    fun loadFromAssets(): List<AgentConfig>
    fun getAgentById(id: String): AgentConfig?
    fun getDefaultAgent(): AgentConfig
    fun getKeywords(): Map<String, List<String>>
}
```

---

## Task 3: AgentRouter

**Test:** `AgentRouterTest.kt` — 验证关键词匹配、默认路由、@ 语法
**Files to create:**
- `app/src/main/java/ai/openclaw/android/domain/agent/AgentRouter.kt`
- `app/src/test/java/ai/openclaw/android/domain/agent/AgentRouterTest.kt`

**API:**
```kotlin
class AgentRouter(private val agents: List<AgentConfig>) {
    fun route(message: String): String  // returns agentId
}
```

**路由规则:**
1. 匹配 `@agentId` 语法 → 直接路由
2. 遍历所有 Agent 的 keywords，命中 → 返回该 agentId
3. 无命中 → 返回默认 Agent

---

## Task 4: AgentSession factory support

**Test:** 复用现有 AgentSession 测试 + 新增 factory 测试
**Files to modify:**
- `app/src/main/java/ai/openclaw/android/agent/AgentSession.kt`
- `app/src/main/java/ai/openclaw/android/model/ModelClient.kt`（可能需要 factory）

**变更:**
- AgentSession 新增 secondary constructor 接受 `AgentConfig` + `ModelClient` factory
- 支持自定义 systemPrompt（合并到 BASE_SYSTEM_PROMPT）
- 支持工具过滤（只暴露 tools 列表中匹配的工具）

---

## Task 5: AgentSessionManager

**Test:** `AgentSessionManagerTest.kt` — 验证创建、缓存、复用
**Files to create:**
- `app/src/main/java/ai/openclaw/android/domain/agent/AgentSessionManager.kt`
- `app/src/test/java/ai/openclaw/android/domain/agent/AgentSessionManagerTest.kt`

**API:**
```kotlin
class AgentSessionManager(
    private val configManager: AgentConfigManager,
    private val skillManager: SkillManager,
    private val accessibilityBridge: AccessibilityBridge?,
    private val modelClientFactory: (AgentConfig) -> ModelClient,
    private val maxCachedSessions: Int = 3
) {
    fun getOrCreate(agentId: String): AgentSession
    fun evict(agentId: String)
    fun getActiveAgentIds(): List<String>
}
```

---

## Task 6: GatewayManager integration

**Test:** 集成测试 — 验证端到端消息路由
**Files to modify:**
- `app/src/main/java/ai/openclaw/android/GatewayManager.kt`
- `app/src/main/java/ai/openclaw/android/GatewayContract.kt`

**变更:**
- GatewayManager 初始化 AgentConfigManager + AgentRouter + AgentSessionManager
- `sendMessage()` 先调用 Router 路由，再获取对应 AgentSession
- GatewayContract 增加 `getAvailableAgents()` 方法
- 保留 `reconfigureModel()` 向后兼容

---

## Task 7: End-to-end verification

**验证:**
1. 182 个现有测试全部通过
2. 新增测试全部通过
3. 构建 APK 并安装
4. 真机验证关键词路由

---

## 执行顺序

```
Task 1 (AgentConfig) → Task 2 (ConfigManager) → Task 3 (Router)
     ↓
Task 4 (AgentSession factory) → Task 5 (SessionManager)
     ↓
Task 6 (GatewayManager) → Task 7 (E2E verification)
```

Task 1-3 可串行执行，每个完成后独立测试。
Task 4-5 依赖 Task 1 的模型。
Task 6 依赖 Task 2-5。
Task 7 最后。
