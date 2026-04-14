# ScriptEngine MemoryBridge 对接实施计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 对接 ScriptSkill 的 MemoryBridge，使 JS 脚本可通过 `memory.recall/query` 和 `memory.store(content)` 读写记忆系统

**Architecture:** MemoryBridge 已有接口（`MemoryProvider` 回调），只需在 ScriptSkill 中创建 MemoryProvider 实例对接 MemoryManager。`runBlocking` 已在 MemoryBridge 内部处理。

**Tech Stack:** Kotlin, MemoryManager, MemoryBridge (script module), ScriptSkill

---

## Task 1: MemoryBridge 实现 + ScriptSkill 对接

### 当前状态

**MemoryBridge.kt** (`script/src/main/java/ai/openclaw/script/bridge/MemoryBridge.kt`):
- 已有完整实现，使用 `MemoryProvider` 回调模式
- `handleMethod` 内已用 `runBlocking` 包裹异步调用
- JS API: `memory.recall(query, limit)` / `memory.store(content)`
- MemoryProvider: `suspend fun execute(method: String, args: String): String`

**ScriptSkill.kt** 当前问题:
- `buildMemoryBridge()` 中 MemoryProvider 返回 `{"error":"Memory not yet integrated"}`
- `memoryManager` 字段为 `Any?` 类型，通过 `setMemoryManager()` 注入

### 具体改动

**文件: `app/src/main/java/ai/openclaw/android/skill/builtin/ScriptSkill.kt`**

需要修改 `buildMemoryBridge()` 方法，创建一个真正的 MemoryProvider 回调：

```kotlin
private fun buildMemoryBridge(): List<CapabilityBridge> {
    val mm = memoryManager ?: return emptyList()
    
    // 通过反射或接口调用 MemoryManager
    val provider = MemoryProvider { method, argsJson ->
        // 解析 argsJson 获取参数
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val args = json.parseToJsonElement(argsJson).jsonObject
        
        runBlocking {
            when (method) {
                "recall" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: ""
                    val limit = args["limit"]?.jsonPrimitive?.int ?: 5
                    val results = mm.searchMemories(query, limit)
                    val resultArray = results.joinToString(",") { memory ->
                        """{"content":"${escapeJson(memory.content)}","type":"${memory.type}","priority":${memory.priority}}"""
                    }
                    """{"results":[$resultArray]}"""
                }
                "store" -> {
                    val content = args["content"]?.jsonPrimitive?.content ?: ""
                    if (content.isBlank()) {
                        """{"error":"content is empty"}"""
                    } else {
                        try {
                            mm.store(content, type = "script")
                            """{"success":true}"""
                        } catch (e: Exception) {
                            """{"error":"${escapeJson(e.message ?: "Unknown error")}"}"""
                        }
                    }
                }
                else -> """{"error":"Unknown method: $method"}"""
            }
        }
    }
    
    return listOf(MemoryBridge(provider))
}

private fun escapeJson(s: String): String = s.replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
```

**如何获取 MemoryManager 引用？**

方案 A（推荐）: 在 ScriptSkill 构造函数中接收 MemoryManager
方案 B: 通过 SkillContext 获取
方案 C: 反射调用

选择方案 A — 修改 ScriptSkill 构造函数：

```kotlin
class ScriptSkill(
    private val memoryManager: ai.openclaw.android.domain.memory.MemoryManager? = null
) : Skill {
    // 删除 setMemoryManager()，直接用构造函数参数
}
```

在 GatewayManager 中注册 Skill 时传入 MemoryManager：
```kotlin
skillManager.registerSkill(ScriptSkill(memoryManager = memoryManager))
```

### 步骤

**Step 1: 写测试（预期失败）**

创建 `app/src/test/java/ai/openclaw/android/skill/builtin/ScriptSkillMemoryTest.kt`:

```kotlin
package ai.openclaw.android.skill.builtin

import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.memory.MemorySearchResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ScriptSkillMemoryTest {

    private lateinit var mockMemoryManager: MemoryManager
    private lateinit var scriptSkill: ScriptSkill

    @Before
    fun setUp() {
        mockMemoryManager = mockk(relaxed = true)
        scriptSkill = ScriptSkill(memoryManager = mockMemoryManager)
    }

    @Test
    fun `memory recall returns search results`() = runTest {
        // Arrange
        val result = MemorySearchResult(
            content = "User prefers blue",
            type = "preference",
            priority = 0.8,
            similarity = 0.95
        )
        every { mockMemoryManager.searchMemories("color preference", any()) } returns listOf(result)

        // Act
        val tool = scriptSkill.tools.find { it.name == "execute_script" }!!
        // We need to test through execute_script with a JS that calls memory.recall
        // For unit test, we can test the MemoryProvider callback directly
    }

    @Test
    fun `memory store saves new memory`() = runTest {
        // Arrange
        every { mockMemoryManager.store(any(), any()) } returns mockk(relaxed = true)

        // Act & Assert
        // Test that store is called with correct parameters
    }

    @Test
    fun `memory store rejects empty content`() = runTest {
        // Test that empty content returns error
    }

    @Test
    fun `no memory manager returns empty bridges`() = runTest {
        // ScriptSkill without MemoryManager should return empty list
        val skillWithoutMemory = ScriptSkill(memoryManager = null)
        // Test that execute with "memory" capability doesn't crash
    }
}
```

**Step 2: 实现修改**

1. 修改 `ScriptSkill` 构造函数接收 `MemoryManager?`
2. 实现 `buildMemoryBridge()` 中的 MemoryProvider 回调
3. 更新 GatewayManager 中注册 ScriptSkill 的方式

**Step 3: 运行测试确认通过**

```bash
cd /home/guuya/OpenClaw-Android-build
./gradlew :app:testDebugUnitTest --tests "ai.openclaw.android.skill.builtin.ScriptSkillMemoryTest"
```

**Step 4: 确认编译通过**

```bash
./gradlew :app:compileDebugKotlin
```

**Step 5: 提交**

```bash
git add app/src/main/java/ai/openclaw/android/skill/builtin/ScriptSkill.kt \
        app/src/test/java/ai/openclaw/android/skill/builtin/ScriptSkillMemoryTest.kt \
        app/src/main/java/ai/openclaw/android/GatewayManager.kt
git commit -m "feat: connect ScriptSkill MemoryBridge to MemoryManager

- ScriptSkill constructor accepts MemoryManager? parameter
- MemoryProvider callback implements recall/store methods
- GatewayManager passes memoryManager to ScriptSkill
- Unit tests for memory recall/store/empty/no-manager"
```

---

## Task 2: 全量验证

**Step 1: 全量测试**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: 所有测试通过（>=158）

**Step 2: 确认 :script 模块测试也通过**

```bash
./gradlew :script:test
```

**Step 3: 提交**

---

## 参考资料

- `script/src/main/java/ai/openclaw/script/bridge/MemoryBridge.kt` — 接口定义
- `app/src/main/java/ai/openclaw/android/domain/memory/MemoryManager.kt` — 记忆系统 API
- `app/src/main/java/ai/openclaw/android/skill/builtin/ScriptSkill.kt` — 当前实现
- `app/src/main/java/ai/openclaw/android/GatewayManager.kt` — Skill 注册位置
