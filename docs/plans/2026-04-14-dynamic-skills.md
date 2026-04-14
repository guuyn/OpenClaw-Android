# 动态技能生成 — 设计文档

> 创建日期: 2026-04-14  
> 状态: Brainstorming 阶段  
> 用户确认: 模块级粒度 + 幂等操作免审批 + 永久存在

---

## 1. 设计原则

| 原则 | 说明 |
|------|------|
| **模块级** | 一个技能 = 完整的 JS 模块，可包含多个函数/工具，给 LLM 足够发挥空间 |
| **幂等操作免审批** | HTTP GET、文件读取等读操作直接执行 |
| **写操作按需确认** | 首次询问用户，记住决策，后续按用户偏好自动处理 |
| **永久存储** | 技能持久化到 Room 数据库，App 重启后自动恢复 |
| **用户可控** | 支持手动删除/禁用，支持定时清理长期未使用的技能 |

---

## 2. 架构设计

### 2.1 模块级技能结构

一个动态技能是一个完整的 JS 模块：

```javascript
// 技能: bitcoin_price
// 功能: 查询比特币价格、设置价格提醒、查看历史记录

const BASE_URL = 'https://api.coingecko.com/api/v3';

function get_price(params) {
    // 幂等操作 - 无需审批
    var resp = http.get(BASE_URL + '/simple/price?ids=bitcoin&vs_currencies=usd');
    var data = JSON.parse(resp);
    var price = data.bitcoin.usd;
    return JSON.stringify({ price: price, currency: 'USD' });
}

function set_alert(params) {
    // 非幂等操作 - 需要确认
    var price = params.price;
    var direction = params.direction; // 'above' or 'below'
    return JSON.stringify({ 
        action: 'set_alert', 
        price: price, 
        direction: direction 
    });
}

function get_history(params) {
    // 幂等操作 - 无需审批
    return JSON.stringify({ history: [] });
}
```

### 2.2 技能定义 JSON（LLM 生成）

```json
{
  "id": "bitcoin_price",
  "name": "比特币价格查询",
  "description": "查询比特币实时价格、设置价格提醒",
  "version": "1.0.0",
  "category": "finance",
  "instructions": "当用户询问比特币、加密货币价格时使用此技能。支持查询实时价格和设置价格提醒。",
  "script": "// 完整的 JS 模块源码...",
  "tools": [
    {
      "name": "get_price",
      "description": "获取比特币当前价格（美元）",
      "parameters": {},
      "entryPoint": "get_price",
      "idempotent": true
    },
    {
      "name": "set_alert",
      "description": "设置价格提醒",
      "parameters": {
        "price": {"type": "number", "description": "目标价格", "required": true},
        "direction": {"type": "string", "description": "above 或 below", "required": true}
      },
      "entryPoint": "set_alert",
      "idempotent": false
    },
    {
      "name": "get_history",
      "description": "查看价格历史记录",
      "parameters": {},
      "entryPoint": "get_history",
      "idempotent": true
    }
  ],
  "permissions": "",
  "createdAt": "2026-04-14T22:30:00Z"
}
```

### 2.3 整体架构

```
用户: "帮我生成一个查询比特币价格的技能"
  │
  ▼
AgentSession (LLM)
  │ 调用 generate_skill 工具
  │ 生成完整的模块级 JS + JSON 定义
  ▼
GenerateSkillTool
  │ 1. 解析 JSON → DynamicSkill
  │ 2. 安全审查:
  │    - 幂等工具 → 直接注册
  │    - 非幂等工具 → 标记为需要确认
  │ 3. DynamicSkillManager.register()
  │    ├── SkillManager 注册运行时技能
  │    ├── Room 数据库持久化
  │    └── AgentSession.refreshTools()
  ▼
后续对话:
  AgentSession 自动发现新技能的所有工具
  用户: "比特币现在多少钱？"
  ▼
  AgentSession → bitcoin_price_get_price → 直接执行（幂等）
  
  用户: "比特币涨到10万美元时提醒我"
  ▼
  AgentSession → bitcoin_price_set_alert
  ▼
  首次: 弹出确认 "该操作将设置价格提醒，是否继续？"
  └── 用户同意 → 记录偏好 → 执行
  └── 用户拒绝 → 取消
  
  后续: 根据用户偏好自动处理
```

### 2.4 安全审查机制

```kotlin
enum class OperationType {
    READ,    // 读操作 - 免审批
    WRITE    // 写操作 - 按需确认
}

class SecurityReview {
    // 工具级别的安全策略
    fun reviewTool(tool: DynamicTool): ToolSecurityPolicy {
        return if (tool.isIdempotent) {
            ToolSecurityPolicy.AUTO_EXECUTE  // 自动执行
        } else {
            ToolSecurityPolicy.ASK_USER      // 首次询问用户
        }
    }
}

// 用户偏好存储
data class UserApprovalPreference(
    val toolId: String,          // "bitcoin_price_set_alert"
    val decision: ApprovalDecision,  // APPROVED / DENIED / ALWAYS_ASK
    val lastUsed: Long
)
```

### 2.5 生命周期管理

```kotlin
class SkillLifecycleManager(
    private val dynamicSkillDao: DynamicSkillDao,
    private val skillManager: SkillManager
) {
    // 30 天未使用 → 停用（不删除，保留数据）
    suspend fun disableUnusedSkills(daysThreshold: Int = 30) {
        val threshold = System.currentTimeMillis() - (daysThreshold * 24 * 60 * 60 * 1000L)
        val unusedSkills = dynamicSkillDao.getEnabledSkillsLastUsedBefore(threshold)
        for (skill in unusedSkills) {
            dynamicSkillDao.disable(skill.id)
            skillManager.unregisterSkill(skill.id)
            Log.i(TAG, "Disabled unused skill: ${skill.id} (last used ${skill.lastUsedAt})")
        }
    }
    
    // 90 天未使用 → 彻底删除
    suspend fun purgeDisabledSkills(daysThreshold: Int = 90) {
        val threshold = System.currentTimeMillis() - (daysThreshold * 24 * 60 * 60 * 1000L)
        val disabledSkills = dynamicSkillDao.getDisabledSkillsDisabledBefore(threshold)
        for (skill in disabledSkills) {
            dynamicSkillDao.delete(skill)
            Log.i(TAG, "Purged disabled skill: ${skill.id}")
        }
    }
    
    // 手动删除
    suspend fun removeSkill(id: String) {
        dynamicSkillDao.delete(id)
        skillManager.unregisterSkill(id)
    }
    
    // 手动停用
    suspend fun disableSkill(id: String) {
        dynamicSkillDao.disable(id)
        skillManager.unregisterSkill(id)
    }
    
    // 手动启用
    suspend fun enableSkill(id: String) {
        dynamicSkillDao.enable(id)
        val skill = dynamicSkillDao.getById(id)?.toDynamicSkill(orchestrator)
        skill?.let { skillManager.registerSkill(it) }
    }
    
    // 记录使用
    suspend fun recordSkillUsage(id: String) {
        dynamicSkillDao.updateLastUsed(id, System.currentTimeMillis())
    }
}
```

---

## 3. 数据模型更新

### DynamicSkillEntity

```kotlin
@Entity(tableName = "dynamic_skills")
data class DynamicSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String = "custom",     // 新增: 分类
    val instructions: String,
    val script: String,
    val toolsJson: String,               // 工具定义 JSON 数组
    val permissions: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,            // 新增: 最后使用时间
    val enabled: Boolean = true,
    val approvalPrefsJson: String = ""   // 新增: 用户审批偏好 JSON
)
```

---

## 4. 实施任务列表

| 任务 | 内容 | 预估 |
|------|------|------|
| **Task 1** | 数据模型更新（添加 category, lastUsedAt, approvalPrefsJson） | 已完成 + 更新 |
| **Task 2** | DynamicSkill 类（模块级） | 已完成 |
| **Task 3** | 安全审查 + 用户偏好管理 | 2 天 |
| **Task 4** | DynamicSkillManager（注册/持久化/生命周期） | 1.5 天 |
| **Task 5** | GenerateSkillTool（LLM 生成工具） | 1 天 |
| **Task 6** | AgentSession 工具刷新 | 0.5 天 |
| **Task 7** | 生命周期管理：30天停用 + 90天删除 + 手动启用/停用/删除 | 1.5 天 |
| **Task 8** | 集成测试 + Prompt 更新 | 1 天 |

---

## 5. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| LLM 生成恶意脚本 | 高 | ScriptEngine 沙箱隔离 + 安全审查 |
| 技能过多影响性能 | 中 | 定时清理 + 最大数量限制 |
| 用户误批准危险操作 | 高 | 首次确认弹窗明确说明操作内容 |
| JS 脚本执行慢 | 中 | QuickJS 性能 + 超时限制 |

---

请确认这个设计方案，确认后我按 superpowers 流程进入 Phase 3 执行。
