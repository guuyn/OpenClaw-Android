# Current Status — 2026-06-28 — 测试补齐完成（选项 A）

## ✅ 本次成果

新增 **14 个测试文件，266 个测试用例，全部通过**。

### 测试覆盖矩阵

| 模块 | 文件 | 测试数 | 状态 |
|------|------|--------|------|
| **Security** | | | |
| | `security/AuditLoggerTest.kt` | 19 | ✅ |
| | `security/SecurityKeyManagerTest.kt` | 8 | ✅ |
| | `ConfigManagerTest.kt` | 26 | ✅ |
| **Model 客户端** | | | |
| | `model/OpenAIClientTest.kt` | 18 | ✅ |
| | `model/AnthropicClientTest.kt` | 19 | ✅ |
| | `model/ImageUtilsTest.kt` | 21 | ✅ |
| **Data 层** | | | |
| | `data/BM25IndexTest.kt` | 30 | ✅ |
| | `data/ConvertersTest.kt` | 28 | ✅ |
| | `data/MemoryFtsDaoTest.kt` | 8 | ✅ |
| | `trigger/dao/TriggerRuleDaoTest.kt` | 21 | ✅ |
| | `trigger/dao/TriggerLogDaoTest.kt` | 16 | ✅ |
| **Domain** | | | |
| | `domain/memory/ColdStartManagerTest.kt` | 14 | ✅ |
| | `domain/memory/DiffSyncManagerTest.kt` | 15 | ✅ |
| | `agent/AgentRegistryTest.kt` | 23 | ✅ |
| **合计** | **14 文件** | **266 测试** | **266 ✅** |

### 构建配置变更

```diff
# app/build.gradle.kts
+    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

（仅 1 行。OkHttp MockWebServer 用于 LLM 客户端 HTTP 模拟）

## 📊 总体覆盖率

| 指标 | 数值 |
|------|------|
| 主源码文件 | 194 |
| 测试前测试文件 | 47 unit + 14 instrumented + 11 compose = 72 |
| 本次新增 | 14 unit |
| 测试后测试文件 | 86 |
| 覆盖率（按文件数） | 47 → 44% |

## 🚨 测试过程中发现的生产代码 Bug（已文档化，未修复）

> 按用户策略：测试任务只写测试不改主代码（除非用户授权）。

### Bug #1: `TriggerRule.parseFilters / parseAction` — Polymorphic Serialization 失效
**位置**: `trigger/TriggerRule.kt`
**现象**: 使用了未配置 polymorphic serializer 的 `Json` 实例，导致 sealed class 反序列化失败，总是返回 `null` / `emptyList()`。
**影响**: 触发器规则的 `filters` 和 `action` 字段无法从 JSON 还原，等于功能完全失效。
**修复建议**:
```kotlin
val json = Json {
    serializersModule = SerializersModule {
        polymorphic(Filter::class) {
            subclass(PackageFilter::class)
            subclass(KeywordFilter::class)
            // ...
        }
        polymorphic(Action::class) {
            subclass(NotificationAction::class)
            // ...
        }
    }
}
```

### Bug #2: `BM25Index.tokenize` — CJK 字符被吞
**位置**: `data/BM25Index.kt`
**现象**: 当 CJK 字符紧跟英文（无空格）时会被英文 tokenizer 吞掉（因为 `Character.isLetterOrDigit()` 对 CJK 返回 true）。
**示例**: `"用Kotlin编程"` → `["kotlin编程"]`（应有 `["kotlin", "编程", "编程"]`）
**影响**: 全文检索对混合中英文召回率严重下降。
**修复建议**: 分离 CJK 与英文 tokenization 路径，或使用 HanLP/jieba 等中文分词。

### Bug #3: `AgentRegistry.getSession` — 未知 agent fallback 不一致
**位置**: `agent/AgentRegistry.kt`
**现象**: fallback 创建新 session 实例（缓存于未知 id），不与 main session 共享。
**影响**: `@unknown_agent` 触发的消息会丢失上下文。
**修复建议**: fallback 应返回 main session 或显式拒绝并提示用户。

## ⚠️ 已知遗留（Pre-existing，不在本任务范围）

### `CameraSkillTest` 3 个用例失败（仅在全量运行时）

```
CameraSkillTest.camera_record returns error without audio permission FAILED
CameraSkillTest.camera_capture returns error without camera permission FAILED
CameraSkillTest.camera_record returns error without camera permission FAILED
```

- **独立运行通过**（FROM-CACHE）
- **全量运行失败**：MockK static mocking 在多个测试类间泄漏状态（经典 MockK 测试隔离问题）
- **不在本任务范围内**：相关提交 `910b5c9 fix: CameraSkillTest - stub checkPermission + mock camera characteristics` 已尝试修复
- **建议**：为 CameraSkillTest 加 `@After` 调用 `unmockkAll()` 或在 Robolectric 测试中改用 `@Config(sdk = ...)` 隔离

## 📋 用户决策点

1. **生产 Bug 修复**: 上述 3 个 bug 是否需要单独开子代理修复？
2. **CameraSkillTest 隔离**: 是否要修复？（影响 `./gradlew test` 全量 CI）
3. **提交策略**: 14 个新测试文件 + build.gradle.kts 已 staged，待 commit + 是否 push？

## 🔄 下一步建议

- 选 B（完整补齐）：MarkdownRenderer / ToolCallCard / VoiceSession / TriggerRuleSkill 等约 8-10 个
- 修复上述 3 个生产 Bug
- 真机恢复后跑 `connectedAndroidTest`（当前阻塞）

## 📁 相关文件

- `app/build.gradle.kts` — +1 行 mockwebserver 依赖
- `app/src/test/java/ai/openclaw/android/` — 14 个新测试文件
- `app/src/test/java/ai/openclaw/android/security/` — 新建子目录
- `app/src/test/java/ai/openclaw/android/trigger/dao/` — 新建子目录
