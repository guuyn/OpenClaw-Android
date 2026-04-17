# Cron & Event Trigger System — Implementation Plan

> Created: 2026-04-16
> Status: Ready for execution

## Overview

Add cron scheduling and external event triggering capabilities to OpenClaw Android.
Enable skills to react to notifications, time-based triggers, and system events.

## Architecture

```
EventSource (Cron/Notification/Accessibility/System)
    ↓
EventBus (route + debounce + dedup)
    ↓
TriggerRule (filters → action)
    ↓
ActionExecutor (SkillCall / AgentQuery / NotificationReply / Script)
```

## Target Directory Structure

```
app/src/main/java/ai/openclaw/android/trigger/
├── EventBus.kt              # 事件总线 + 规则匹配 + 防抖
├── models/
│   ├── TriggerRule.kt       # Room entity + data classes
│   ├── TriggerEvent.kt      # 统一事件格式
│   └── TriggerAction.kt     # 执行动作密封类
├── scheduler/
│   └── CronScheduler.kt     # WorkManager 集成
├── dao/
│   ├── TriggerRuleDao.kt    # Room DAO
│   └── TriggerLogDao.kt     # 执行日志 DAO
└── skill/
    └── TriggerRuleSkill.kt  # Skill 工具暴露给用户

app/src/main/java/ai/openclaw/android/notification/
└── SmartNotificationListener.kt  ← 修改：接入 EventBus
```

## Dependencies

- WorkManager (already in AndroidX)
- Room (already in project)
- kotlinx-serialization (already in project)

---

## Task List

### T1: Data Models — TriggerRule Entity + Room DAO

**New file:** `app/src/main/java/ai/openclaw/android/trigger/models/TriggerRule.kt`

Create Room entity for trigger rules with:
- `@Entity(tableName = "trigger_rules")`
- Fields: id, name, enabled, source (string enum), filtersJson (String), actionJson (String), cooldownMs, scheduleCron, createdAt, updatedAt
- Companion object with JSON serialization helpers
- Filters: PackageFilter, KeywordFilter, TimeFilter, CategoryFilter (sealed class, serialized as JSON)
- Actions: SkillCall, AgentQuery, NotificationReply, CustomScript (sealed class, serialized as JSON)

**New file:** `app/src/main/java/ai/openclaw/android/trigger/models/TriggerLog.kt`

Create Room entity for execution logs:
- `@Entity(tableName = "trigger_logs", indices = [Index("ruleId")])`
- Fields: id, ruleId, eventId, executedAt, actionType, success, error?, result?

**New file:** `app/src/main/java/ai/openclaw/android/trigger/dao/TriggerRuleDao.kt`

```kotlin
@Dao
interface TriggerRuleDao {
    @Query("SELECT * FROM trigger_rules ORDER BY createdAt DESC")
    suspend fun getAll(): List<TriggerRule>
    
    @Query("SELECT * FROM trigger_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<TriggerRule>
    
    @Query("SELECT * FROM trigger_rules WHERE id = :id")
    suspend fun getById(id: String): TriggerRule?
    
    @Query("SELECT * FROM trigger_rules WHERE source = :source")
    suspend fun getBySource(source: String): List<TriggerRule>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: TriggerRule)
    
    @Delete
    suspend fun delete(rule: TriggerRule)
    
    @Query("DELETE FROM trigger_rules WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("UPDATE trigger_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
```

**New file:** `app/src/main/java/ai/openclaw/android/trigger/dao/TriggerLogDao.kt`

```kotlin
@Dao
interface TriggerLogDao {
    @Query("SELECT * FROM trigger_logs ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TriggerLog>
    
    @Query("SELECT * FROM trigger_logs WHERE ruleId = :ruleId ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getByRule(ruleId: String, limit: Int = 20): List<TriggerLog>
    
    @Insert
    suspend fun insert(log: TriggerLog)
    
    @Query("DELETE FROM trigger_logs WHERE executedAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
```

**Modify:** `app/src/main/java/ai/openclaw/android/data/local/AppDatabase.kt`
- Add `TriggerRuleDao` and `TriggerLogDao`
- Add `TriggerRule::class` and `TriggerLog::class` to entities list
- Add migration or use `fallbackToDestructiveMigration()` for dev

### T2: Event Model + EventBus

**New file:** `app/src/main/java/ai/openclaw/android/trigger/models/TriggerEvent.kt`

```kotlin
data class TriggerEvent(
    val id: String = UUID.randomUUID().toString(),
    val source: EventSource,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any?> = emptyMap(),
    val dedupKey: String? = null
)

enum class EventSource {
    CRON, NOTIFICATION, ACCESSIBILITY, SYSTEM_BROADCAST, USER_ACTION
}
```

**New file:** `app/src/main/java/ai/openclaw/android/trigger/EventBus.kt`

Core event bus with:
- `publish(event: TriggerEvent)` — match rules, check cooldown, execute actions
- `registerRule(rule: TriggerRule)` / `unregisterRule(id: String)`
- Cooldown tracking: `Map<String, Long>` with debouncing
- Dedup: track recent dedupKeys with LRU cache (max 100 entries, TTL 5min)
- Filter matching logic for each filter type
- Async execution via CoroutineScope

### T3: ActionExecutor

**New file:** `app/src/main/java/ai/openclaw/android/trigger/ActionExecutor.kt`

Execute TriggerAction types:
- `SkillCall` → call SkillManager.executeTool(skillId, toolName, params)
- `AgentQuery` → call AgentSession.handleMessage(prompt with event data)
- `NotificationReply` → use NotificationManager to reply (API 24+)
- `CustomScript` → call ScriptOrchestrator.execute(script)

Each action returns `ActionResult(success, result, error)`.

### T4: CronScheduler

**New file:** `app/src/main/java/ai/openclaw/android/trigger/scheduler/CronScheduler.kt`

- Use WorkManager PeriodicWorkRequest
- Parse cron expressions to intervals (support: */N, 0 0 * * *, etc.)
- Enqueue unique periodic work per rule
- Cancel work when rule deleted/disabled
- CronWorker class: publishes CRON event to EventBus

### T5: Database Integration

**Modify:** `AppDatabase.kt` — add entities + DAOs

**Modify:** `GatewayManager.kt` — initialize EventBus + CronScheduler on startup
- Load all enabled rules from DB
- Register cron rules with CronScheduler
- Set EventBus reference for NotificationListener

### T6: TriggerRuleSkill (User-facing tools)

**New file:** `app/src/main/java/ai/openclaw/android/trigger/skill/TriggerRuleSkill.kt`

Tools:
- `create_trigger_rule` — create a new rule
- `delete_trigger_rule` — delete by ID
- `list_trigger_rules` — list all rules
- `enable_trigger_rule` / `disable_trigger_rule` — toggle
- `test_trigger_rule` — manually trigger a rule
- `get_trigger_logs` — recent execution logs

### T7: Connect NotificationListener to EventBus

**Modify:** `SmartNotificationListener.kt`
- In `onNotificationPosted()`, publish TriggerEvent to EventBus
- Add EventBus reference (singleton or injection)

---

## Execution Order

T1 → T2 → T3 → T4 → T5 → T6 → T7

## Verification

- Build: `./gradlew assembleDebug` succeeds
- Install: APK installs without errors
- Create rule via skill: `create_trigger_rule` creates a cron rule
- Trigger: Rule executes at scheduled time or event
- Logs: Execution logged in trigger_logs table
