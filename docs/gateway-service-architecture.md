# OpenClaw-Android Gateway Service 架构重构设计

**文档版本**: 1.1  
**创建日期**: 2026-04-12  
**更新日期**: 2026-04-12  
**状态**: 设计阶段（已根据 Claude Code 评审优化）

---

## 1. 背景与问题

### 1.1 当前架构问题

当前 OpenClaw-Android 存在严重的架构问题：

| 问题 | 描述 | 影响 |
|------|------|------|
| **双实例问题** | GatewayService 和 MainActivity 各自持有 LocalLLMClient、AgentSession、SkillManager 的独立实例 | 内存浪费 7GB+（3.5GB 模型 × 2） |
| **模型重复加载** | Activity 被系统回收后重新进入时，模型需要重新加载（约 10 秒） | 用户体验差，启动慢 |
| **职责混乱** | MainActivity 包含大量业务逻辑（模型初始化、Session 管理、Skill 注册） | 违背 Activity 作为纯 UI 层的原则 |
| **状态隔离** | GatewayService 的本地模型实例和 Activity 的模型实例完全独立，无法共享 | 数据不一致，Feishu 消息和 UI 聊天使用不同模型状态 |

### 1.2 重构目标

- ✅ **GatewayService 是唯一逻辑中心**：持有模型、Agent、技能的唯一实例
- ✅ **MainActivity 是纯 UI 层**：只负责展示和用户交互
- ✅ **模型持久化**：退出再进入 Activity 时不重新加载模型
- ✅ **内存优化**：单实例模型，节省 3.5GB 内存
- ✅ **保持 API 兼容**：Feishu 消息处理不受影响

---

## 2. 当前架构（AS-IS）

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  LocalLLMClient (实例 A) - 3.5GB                          │  │
│  │  AgentSession (实例 A)                                     │  │
│  │  SkillManager (实例 A)                                     │  │
│  │  MemoryManager, EmbeddingService, AppDatabase             │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  UI 逻辑 + 业务逻辑混合                                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       GatewayService                             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  GatewayManager (全部 private)                             │  │
│  │  ├─ LocalLLMClient (实例 B) - 3.5GB                       │  │
│  │  ├─ AgentSession (实例 B)                                  │  │
│  │  ├─ SkillManager (实例 B)                                  │  │
│  │  └─ FeishuClient                                           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  无法被 Activity 访问                                            │
└─────────────────────────────────────────────────────────────────┘

问题：
  ❌ 两个独立的模型实例 = 7GB 内存浪费
  ❌ Activity 销毁后模型需要重新加载（10 秒）
  ❌ GatewayManager 全部 private，无法共享
  ❌ Feishu 消息和 UI 聊天使用不同的 AgentSession
```

---

## 3. 目标架构（TO-BE）

```
┌─────────────────────────────────────────────────────────────────┐
│                       GatewayService ★                           │
│                    【唯一逻辑中心】                               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  GatewayManager (单例，持有所有核心组件)                    │  │
│  │  ├─ LocalLLMClient (唯一实例) - 3.5GB                     │  │
│  │  ├─ AgentSession (唯一实例)                                │  │
│  │  ├─ SkillManager (唯一实例)                                │  │
│  │  ├─ FeishuClient                                           │  │
│  │  ├─ AccessibilityBridge                                    │  │
│  │  ├─ MemoryManager                                          │  │
│  │  ├─ EmbeddingService                                       │  │
│  │  └─ AppDatabase (Room)                                     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ★ 提供 Binder 接口供 Activity 调用                              │
│  ★ 模型生命周期独立于 Activity                                   │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ AIDL/Binder
                              │ GatewayContract
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│                      【纯 UI 层】                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  UI 状态 (Compose)                                         │  │
│  │  用户输入 → GatewayService → 接收响应 → 展示                │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  保留在 Activity 层：                                             │
│  └─ PermissionManager（权限请求）                                │
└─────────────────────────────────────────────────────────────────┘

优势：
  ✅ 单一模型实例 = 节省 3.5GB 内存
  ✅ Activity 销毁不影响模型状态
  ✅ Feishu 消息和 UI 聊天共享同一 AgentSession
  ✅ 清晰的职责分离
```

---

## 4. 核心设计

### 4.1 GatewayContract 接口设计

**核心原则**：Activity 只依赖接口，不直接访问 GatewayManager 内部组件。

```kotlin
/**
 * Gateway 服务契约接口
 * Activity 只依赖此接口，不直接访问 GatewayManager 内部组件
 * 为将来改成远程 Service（真正跨进程）留了退路
 */
interface GatewayContract {
    
    // ========== 就绪状态 ==========
    fun isReady(): Boolean
    fun getModelLoadState(): LocalLLMClient.LoadState?
    fun getConnectionState(): StateFlow<GatewayManager.ConnectionState>
    
    // ========== 消息处理 ==========
    fun sendMessage(text: String): Flow<SessionEvent>
    
    // ========== 模型配置 ==========
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    
    // ========== 技能管理 ==========
    fun getAvailableSkills(): List<SkillInfo>
}

/** 模型配置（封装切换参数） */
data class ModelConfig(
    val provider: ModelProvider,
    val apiKey: String,
    val modelName: String
)

/** 技能信息（精简版，不暴露内部 Skill 对象） */
data class SkillInfo(
    val id: String,
    val name: String,
    val description: String
)
```

### 4.2 GatewayManager 实现 GatewayContract

```kotlin
class GatewayManager(private val service: GatewayService) : GatewayContract {
    
    // 内部组件保持私有
    private var modelClient: ModelClient? = null
    private var localLLMClient: LocalLLMClient? = null
    private var agentSession: AgentSession? = null
    private var skillManager: SkillManager? = null
    private var feishuClient: FeishuClient? = null
    private var accessibilityBridge: AccessibilityBridge? = null
    
    // 实现 GatewayContract
    override fun isReady(): Boolean = agentSession != null
    
    override fun getModelLoadState(): LocalLLMClient.LoadState? = 
        localLLMClient?.getState()
    
    override fun sendMessage(text: String): Flow<SessionEvent> =
        agentSession?.handleMessageStream(text) 
            ?: flow { emit(SessionEvent.Error("AgentSession not ready")) }
    
    override suspend fun reconfigureModel(config: ModelConfig): Boolean {
        // 1. 释放旧模型
        localLLMClient?.release()
        
        // 2. 更新配置
        ConfigManager.setModelProvider(config.provider.name)
        ConfigManager.setModelApiKey(config.apiKey)
        ConfigManager.setModelName(config.modelName)
        
        // 3. 重新初始化
        initializeComponents()
        
        return agentSession != null
    }
    
    override fun getAvailableSkills(): List<SkillInfo> =
        skillManager?.getLoadedSkills()?.map { (id, skill) ->
            SkillInfo(id, skill.getSkillName(), skill.getSkillDescription())
        } ?: emptyList()
    
    override fun getConnectionState(): StateFlow<GatewayManager.ConnectionState> = 
        _connectionState
    
    // 现有方法保持不变
    fun start() { ... }
    fun stop() { ... }
}
```

### 4.3 GatewayService Binder 接口

```kotlin
inner class LocalBinder : Binder() {
    fun getService(): GatewayService = this@GatewayService
    fun getGatewayContract(): GatewayContract = gatewayManager!!
}

override fun onBind(intent: Intent?): IBinder = binder
```

### 4.4 MainActivity 简化方案

**移除的业务逻辑**：
- ❌ LocalLLMClient 初始化
- ❌ AgentSession 创建和管理
- ❌ SkillManager 初始化
- ❌ 模型配置和切换逻辑
- ❌ MemoryManager/EmbeddingService/AppDatabase 初始化

**保留在 Activity 层**：
- ✅ PermissionManager（权限请求）

```kotlin
class MainActivity : ComponentActivity() {
    
    // 只依赖接口，不依赖 GatewayManager 具体类
    private var gatewayContract: GatewayContract? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as GatewayService.LocalBinder
            gatewayContract = localBinder.getGatewayContract()
            serviceBound = true
            
            // GatewayService 已包含 MemoryManager/EmbeddingService/Database
            // Activity 只需绑定并使用 GatewayContract 接口
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayContract = null
            serviceBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 绑定 GatewayService（模型 + 记忆 + 技能 全在 Service 里）
        Intent(this, GatewayService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        setContent { MainScreen() }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
```

---

## 5. 数据流设计

### 5.1 用户输入 → Service 处理 → UI 展示

```
用户输入
    │
    ▼
┌─────────────────────────────────────┐
│  MainActivity                       │
│  ┌─────────────────────────────┐    │
│  │ 1. 用户输入 "查询天气"       │    │
│  │ 2. 调用 gatewayContract      │    │
│  │    .sendMessage("查询天气")  │    │
│  └──────────────┬──────────────┘    │
└─────────────────┼───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│  GatewayService                     │
│  ┌─────────────────────────────┐    │
│  │ 3. GatewayManager.sendMessage│    │
│  │ 4. AgentSession 处理消息    │    │
│  │ 5. 调用 WeatherSkill        │    │
│  │ 6. 返回流式响应             │    │
│  └──────────────┬──────────────┘    │
└─────────────────┼───────────────────┘
                  │
                  │ Flow<SessionEvent>
                  │ - Token("正在")
                  │ - Token("查询")
                  │ - Token("北京")
                  │ - Token("天气")
                  │ - Complete("北京今天晴...")
                  │
                  ▼
┌─────────────────────────────────────┐
│  MainActivity                       │
│  ┌─────────────────────────────┐    │
│  │ 7. 收集 Flow 事件            │    │
│  │ 8. 更新 UI 状态              │    │
│  │ 9. Compose 重新渲染          │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

### 5.2 Feishu 消息处理（保持不变）

```
Feishu 消息
    │
    ▼
┌─────────────────────────────────────┐
│  FeishuClient (GatewayService 内)   │
│  ┌─────────────────────────────┐    │
│  │ 1. 接收 im.message.receive  │    │
│  │ 2. 调用 agentSession        │    │
│  │    .handleMessage(...)      │    │
│  └──────────────┬──────────────┘    │
└─────────────────┼───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│  AgentSession (与 UI 共享同一实例)   │
│  ┌─────────────────────────────┐    │
│  │ 3. 处理消息，调用技能        │    │
│  │ 4. 返回响应到 Feishu         │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘

优势：
  ✅ Feishu 消息和 UI 聊天共享同一 AgentSession
  ✅ 对话历史、Memory 上下文完全同步
  ✅ 无需额外改动 Feishu 处理逻辑
```

---

## 6. 生命周期管理

### 6.1 Service 与 Activity 绑定关系

```
┌──────────────────────────────────────────────────────────────┐
│  生命周期事件                                                 │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  1. 应用启动 → GatewayService.start()                        │
│     └─ Service 创建，模型开始加载（如果需要）                  │
│                                                               │
│  2. MainActivity.onCreate()                                  │
│     └─ bindService(BIND_AUTO_CREATE)                         │
│     └─ onServiceConnected: 获取 GatewayContract 接口          │
│                                                               │
│  3. 用户退出 Activity（按 Home）                              │
│     └─ Activity 可能被系统回收                               │
│     └─ unbindService() 但 Service 继续运行                    │
│     └─ 模型保持加载状态 ✓                                    │
│                                                               │
│  4. 用户重新进入 Activity                                     │
│     └─ bindService()                                         │
│     └─ 模型已加载，直接使用 ✓                                │
│                                                               │
│  5. 用户明确停止服务（设置页 Stop）                           │
│     └─ GatewayService.stop()                                 │
│     └─ 释放模型资源，Service 停止                            │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 模型保持策略

```kotlin
class GatewayService : Service() {
    
    private var gatewayManager: GatewayManager? = null
    
    override fun onCreate() {
        super.onCreate()
        // 模型在 GatewayManager.start() 时初始化
        gatewayManager = GatewayManager(this)
    }
    
    override fun onDestroy() {
        // 仅在 Service 销毁时释放模型
        gatewayManager?.stop()  // 调用 localLLMClient.release()
        super.onDestroy()
    }
    
    // Activity unbind 不影响 Service 生命周期
    // 模型保持加载状态
}
```

### 6.3 配置变更处理（切换 provider/model）

```kotlin
// MainActivity 设置页
onSaveConfig = {
    scope.launch {
        val success = gatewayContract?.reconfigureModel(
            ModelConfig(
                provider = ModelProvider.valueOf(modelProvider),
                apiKey = modelApiKey,
                modelName = modelName
            )
        )
        
        if (success == true) {
            // 配置成功，Gateway 内部已重新初始化模型
            // UI 只需刷新状态
        } else {
            // 显示错误
        }
    }
}

// GatewayManager 内部实现（实现 GatewayContract.reconfigureModel）
override suspend fun reconfigureModel(config: ModelConfig): Boolean {
    // 1. 释放旧模型
    localLLMClient?.release()
    
    // 2. 更新配置
    ConfigManager.setModelProvider(config.provider.name)
    ConfigManager.setModelApiKey(config.apiKey)
    ConfigManager.setModelName(config.modelName)
    
    // 3. 初始化新模型
    initializeComponents()
    
    // 4. 重建 AgentSession
    return agentSession != null
}
```

---

## 7. 改动清单

### 7.1 文件修改列表

| 文件 | 改动类型 | 改动内容 |
|------|----------|----------|
| `GatewayContract.kt` | **新增** | 定义 GatewayContract 接口、ModelConfig、SkillInfo |
| `GatewayManager.kt` | **修改** | 实现 GatewayContract，内部组件私有 |
| `GatewayService.kt` | **修改** | LocalBinder 改为返回 GatewayContract |
| `MainActivity.kt` | **重构** | 移除所有业务逻辑，改为 bindService 使用 GatewayContract |
| `AgentSession.kt` | **无改动** | 已有 setter |
| `LocalLLMClient.kt` | **无改动** | 保持现有接口 |
| `ConfigManager.kt` | **无改动** | 保持现有接口 |

### 7.2 GatewayContract.kt（新增文件）

```kotlin
package ai.openclaw.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelProvider

/**
 * Gateway 服务契约接口
 * Activity 只依赖此接口，不直接访问 GatewayManager 内部组件
 * 为将来改成远程 Service（真正跨进程）留了退路
 */
interface GatewayContract {
    fun isReady(): Boolean
    fun getModelLoadState(): LocalLLMClient.LoadState?
    fun getConnectionState(): StateFlow<GatewayManager.ConnectionState>
    fun sendMessage(text: String): Flow<SessionEvent>
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    fun getAvailableSkills(): List<SkillInfo>
}

data class ModelConfig(
    val provider: ModelProvider,
    val apiKey: String,
    val modelName: String
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String
)
```

### 7.3 GatewayManager.kt 具体改动

```kotlin
// ===== 改动前 =====
class GatewayManager(private val service: GatewayService) {
    private var modelClient: ModelClient? = null
    private var agentSession: AgentSession? = null
    // ... 全部 private，无公共 API
}

// ===== 改动后 =====
class GatewayManager(private val service: GatewayService) : GatewayContract {
    // 组件保持私有
    private var modelClient: ModelClient? = null
    private var agentSession: AgentSession? = null
    private var skillManager: SkillManager? = null
    private var localLLMClient: LocalLLMClient? = null
    
    // 实现 GatewayContract
    override fun isReady(): Boolean = agentSession != null
    override fun getModelLoadState(): LocalLLMClient.LoadState? = localLLMClient?.getState()
    override fun sendMessage(text: String): Flow<SessionEvent> = ...
    override suspend fun reconfigureModel(config: ModelConfig): Boolean = ...
    override fun getAvailableSkills(): List<SkillInfo> = ...
    override fun getConnectionState(): StateFlow<GatewayManager.ConnectionState> = _connectionState
}
```

### 7.4 MainActivity.kt 具体改动

**移除的代码块**（约 150 行）：
- ❌ 模型初始化逻辑（LaunchedEffect 中 100+ 行）
- ❌ 配置保存时的模型重建逻辑（50+ 行）
- ❌ rewireMemoryAndSession 函数
- ❌ MemoryManager/EmbeddingService/AppDatabase 初始化

**新增的代码块**：
```kotlin
// ✅ 新增：Service 绑定
private var gatewayContract: GatewayContract? = null
private var serviceBound = false

private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val localBinder = binder as GatewayService.LocalBinder
        gatewayContract = localBinder.getGatewayContract()
        serviceBound = true
    }
    
    override fun onServiceDisconnected(name: ComponentName?) {
        gatewayContract = null
        serviceBound = false
    }
}

// ✅ 新增：简化后的 sendMessage
val sendMessage: (String) -> Unit = { text ->
    scope.launch {
        val contract = gatewayContract
        if (contract == null || !contract.isReady()) {
            messages.add(ChatMessage(role = "assistant", content = "服务未就绪"))
            return@launch
        }
        try {
            contract.sendMessage(text).collect { event ->
                when (event) {
                    is SessionEvent.Token -> { /* 更新 UI */ }
                    is SessionEvent.Complete -> { /* 完成 */ }
                    is SessionEvent.Error -> { /* 错误处理 */ }
                }
            }
        } catch (e: Exception) {
            messages.add(ChatMessage(role = "assistant", content = "错误: ${e.message}"))
        }
    }
}

// ✅ 新增：简化后的配置保存
onSaveConfig = {
    scope.launch {
        val success = gatewayContract?.reconfigureModel(
            ModelConfig(
                provider = ModelProvider.valueOf(modelProvider),
                apiKey = modelApiKey,
                modelName = modelName
            )
        )
        // 处理结果
    }
}
```

---

## 8. 测试策略

### 8.1 单元测试

```kotlin
// GatewayManagerTest.kt
@Test
fun `GatewayManager implements GatewayContract`() {
    val manager = GatewayManager(mockService)
    manager.start()
    
    assertTrue(manager.isReady())
    assertNotNull(manager.getAvailableSkills())
}

@Test
fun `sendMessage returns error flow when not ready`() = runTest {
    val manager = GatewayManager(mockService)
    val events = manager.sendMessage("test").toList()
    
    assertEquals(1, events.size)
    assertTrue(events[0] is SessionEvent.Error)
}
```

### 8.2 集成测试

```kotlin
// GatewayServiceIntegrationTest.kt
@Test
fun `Activity bind does not reload model`() {
    // 1. 启动 Service
    GatewayService.start(context)
    
    // 2. 首次绑定 Activity
    val activity1 = scenario.launch<MainActivity>()
    val firstContract = activity1.gatewayContract
    
    // 3. 销毁 Activity
    activity1.finish()
    
    // 4. 重新绑定 Activity
    val activity2 = scenario.launch<MainActivity>()
    val secondContract = activity2.gatewayContract
    
    // 5. 验证是同一个 GatewayManager 实例（未重新加载）
    assertSame(firstContract, secondContract)
}
```

### 8.3 手动测试清单

| 测试场景 | 预期结果 | 状态 |
|----------|----------|------|
| 启动应用，进入聊天页 | 模型加载一次，聊天正常 | ☐ |
| 按 Home 退出，重新进入 | 模型不重新加载，历史保留 | ☐ |
| 设置页切换模型（云→本地） | 旧模型释放，新模型加载 | ☐ |
| 设置页切换模型（本地→云） | 本地模型释放，云模型配置 | ☐ |
| Feishu 发送消息 | 正常响应，与 UI 共享上下文 | ☐ |
| UI 发送消息后 Feishu 提问 | Feishu 能引用 UI 对话历史 | ☐ |
| 系统回收 Activity 后重新进入 | 模型保持加载，无 10 秒等待 | ☐ |
| 停止 Service 后重启 | 模型重新加载 | ☐ |

---

## 9. 风险点与注意事项

### 9.1 潜在风险

| 风险 | 描述 | 缓解措施 |
|------|------|----------|
| **内存泄漏** | GatewayService 持有 Activity 引用 | 使用 ApplicationContext，避免持有 Activity 上下文 |
| **Binder 事务失败** | 大对象跨进程传递可能导致 TransactionTooLargeException | 只传递必要数据，使用 Flow 流式传输 |
| **并发问题** | Activity 和 Feishu 同时调用 AgentSession | AgentSession 已有 sessionMutex 保护 |
| **Service 被系统杀死** | 内存压力下 Service 可能被回收 | 使用 START_STICKY，在 onStartCommand 恢复状态 |

### 9.2 注意事项

1. **MemoryManager/EmbeddingService/AppDatabase 已移到 GatewayService** — 使用 applicationContext，不依赖 Activity 生命周期
2. **PermissionManager 保留在 Activity 层** — 权限请求需要 Activity 上下文
3. **Feishu 消息处理保持不变** — handleFeishuEvent() 逻辑不需要改动
4. **模型文件路径** — 本地模型路径 /sdcard/Download/gemma-4-E4B-it.litertlm，确保 Service 有文件访问权限

---

## 10. 实施计划

### Phase 1: GatewayContract 接口定义（0.5 天）
- [ ] 创建 GatewayContract.kt
- [ ] 定义 ModelConfig、SkillInfo 数据类
- [ ] GatewayManager 实现 GatewayContract

### Phase 2: GatewayService 调整（0.5 天）
- [ ] LocalBinder 改为返回 GatewayContract
- [ ] 将 MemoryManager/EmbeddingService/AppDatabase 移到 Service

### Phase 3: MainActivity 重构（2 天）
- [ ] 移除所有业务逻辑（约 150 行）
- [ ] 添加 Service 绑定代码
- [ ] 简化 sendMessage 和配置保存
- [ ] 只保留 PermissionManager

### Phase 4: 集成测试（1 天）
- [ ] 编写集成测试
- [ ] 手动测试清单验证
- [ ] 性能测试（模型加载次数）

### Phase 5: 文档与清理（0.5 天）
- [ ] 更新代码注释
- [ ] 清理未使用的导入和变量
- [ ] Code Review

**总计**: 4.5 天

---

## 11. 附录

### 11.1 关键接口定义汇总

```kotlin
// GatewayService.LocalBinder
inner class LocalBinder : Binder() {
    fun getService(): GatewayService = this@GatewayService
    fun getGatewayContract(): GatewayContract = gatewayManager!!
}

// GatewayContract 接口
interface GatewayContract {
    fun isReady(): Boolean
    fun getModelLoadState(): LocalLLMClient.LoadState?
    fun sendMessage(text: String): Flow<SessionEvent>
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    fun getAvailableSkills(): List<SkillInfo>
    fun getConnectionState(): StateFlow<GatewayManager.ConnectionState>
}

// 数据类
data class ModelConfig(val provider: ModelProvider, val apiKey: String, val modelName: String)
data class SkillInfo(val id: String, val name: String, val description: String)
```

### 11.2 相关文件路径

```
/mnt/e/Android/OpenClaw-Android/
├── app/src/main/java/ai/openclaw/android/
│   ├── GatewayContract.kt           # 【新增】服务契约接口
│   ├── MainActivity.kt              # 【重构】纯 UI，通过 bindService 使用 GatewayContract
│   ├── GatewayService.kt            # 【修改】LocalBinder 返回 GatewayContract
│   ├── GatewayManager.kt            # 【修改】实现 GatewayContract
│   ├── ConfigManager.kt             # 【无改动】
│   ├── MemoryManager.kt             # 【移动】Activity → Service
│   ├── EmbeddingService.kt          # 【移动】Activity → Service
│   ├── model/LocalLLMClient.kt      # 【无改动】
│   ├── agent/AgentSession.kt        # 【无改动】已有 setter
│   └── data/local/AppDatabase.kt    # 【移动】Activity → Service
└── docs/
    └── gateway-service-architecture.md  # 本文档
```

---

## 12. 更新记录

### v1.1 (2026-04-12 更新) — Claude Code 评审后优化

**改动**: 引入 `GatewayContract` 接口，Activity 不再直接访问 GatewayManager 内部组件

**原因**: 原设计中 GatewayManager 的 getter 直接返回可变对象引用（`getAgentSession()`, `getLocalLLMClient()`），Activity 拿到引用后可以任意调用。GatewayManager 本质上只是一个"间接层"，没有真正的封装。

**改进**:
- ✅ 新增 `GatewayContract` 接口 — Activity 只依赖接口，不依赖具体类
- ✅ `GatewayManager` 实现 `GatewayContract` — 内部组件全部私有
- ✅ 新增 `ModelConfig` 数据类 — 封装切换参数
- ✅ 新增 `SkillInfo` 数据类 — 精简版技能信息，不暴露内部 Skill 对象
- ✅ 为将来改成远程 Service（真正跨进程）留了退路

**设计改进对比**:

| 改进项 | v1.0 原设计 | v1.1 优化后 |
|--------|------------|-------------|
| **接口暴露** | Activity 直接拿到 GatewayManager 内部组件 | 通过 GatewayContract 接口通信 |
| **封装性** | GatewayManager 是间接层 | GatewayManager 有明确契约 |
| **扩展性** | 无法轻易改成远程 Service | 未来可无缝切换到 AIDL/远程 Service |
| **类型安全** | 返回 `AgentSession?`, `LocalLLMClient?` 等具体类型 | 返回精简的 `SkillInfo`, `ModelConfig` 等值对象 |
| **职责分离** | Activity 可能绕过契约直接调用内部方法 | 接口强制约束，Activity 只能通过契约访问 |

### v1.0 (2026-04-12) — 初始版本
- 初始架构设计文档
- 当前架构（AS-IS）与目标架构（TO-BE）
- 核心设计、数据流、生命周期管理
- 改动清单、测试策略、风险点
