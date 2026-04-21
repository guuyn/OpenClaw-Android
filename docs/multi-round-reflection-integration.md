# 多轮自我反思策略集成到 OpenClaw Android

> 基于实验结论：2 轮最优，动态提示词角色切换效果最强

## 一、集成目标

OpenClaw Android 使用本地小模型（Gemma 4 E4B/E2B），推理能力有限。通过多轮自我反思策略，可以在不增加模型参数的前提下，显著提升回答质量。

## 二、集成位置

### 核心入口：`AgentSession.kt`

当前流程：
```
用户消息 → handleMessageStream → modelClient.chatStream → SessionEvent
```

改造后流程：
```
用户消息 → ReflectionOrchestrator → 
  ├── 简单问题：直接 chatStream（1 轮）
  ├── 中等问题：chatStream → 反思 prompt → chatStream（2 轮）
  └── 复杂问题：chatStream → 检查员角色 → 批评者角色（3 轮）
```

## 三、设计方案

### 方案 A：轻量级集成（推荐先做）

在 `AgentSession` 中增加 `ReflectionStrategy` 配置：

```kotlin
// 新增文件：domain/ReflectionStrategy.kt
enum class ReflectionStrategy {
    NONE,           // 不反思（简单问题）
    SINGLE,         // 1 轮反思（中等问题）
    DOUBLE          // 2 轮角色切换（复杂问题）
}

data class ReflectionConfig(
    val strategy: ReflectionStrategy = ReflectionStrategy.SINGLE,
    val roles: List<String> = listOf("检查员", "批评者")
)
```

在 `AgentSession` 中增加反思逻辑：

```kotlin
// 在 handleMessageStream 中增加反思阶段
fun handleMessageStreamWithReflection(userMessage: String): Flow<SessionEvent> = flow {
    // 第 1 轮：正常回答
    val firstResponse = sendMessageWithHistory(userMessage)
    emit(SessionEvent.Token("[思考中...]"))
    
    when (reflectionConfig.strategy) {
        ReflectionStrategy.NONE -> {
            emit(SessionEvent.Complete(firstResponse))
        }
        ReflectionStrategy.SINGLE -> {
            // 第 2 轮：角色切换反思
            val reflectionPrompt = buildReflectionPrompt(firstResponse, "检查员")
            val refinedResponse = sendMessageWithHistory(reflectionPrompt)
            emit(SessionEvent.Complete(refinedResponse))
        }
        ReflectionStrategy.DOUBLE -> {
            // 第 2 轮：检查员
            val checkerPrompt = buildReflectionPrompt(firstResponse, "检查员")
            val checkedResponse = sendMessageWithHistory(checkerPrompt)
            
            // 第 3 轮：批评者
            val criticPrompt = buildReflectionPrompt(checkedResponse, "批评者")
            val finalResponse = sendMessageWithHistory(criticPrompt)
            emit(SessionEvent.Complete(finalResponse))
        }
    }
}
```

### 方案 B：独立 ReflectionOrchestrator（更优雅）

创建独立的反思编排器：

```kotlin
// 新增文件：domain/ReflectionOrchestrator.kt
class ReflectionOrchestrator(
    private val modelClient: ModelClient,
    private val config: ReflectionConfig
) {
    suspend fun execute(
        question: String,
        initialAnswer: String
    ): String {
        var answer = initialAnswer
        
        for (role in config.roles) {
            val prompt = when (role) {
                "检查员" -> buildCheckerPrompt(question, answer)
                "批评者" -> buildCriticPrompt(question, answer)
                "优化师" -> buildOptimizerPrompt(question, answer)
                else -> buildGenericReflectionPrompt(question, answer)
            }
            answer = sendMessage(prompt)
        }
        
        return answer
    }
}
```

## 四、反思提示词模板

### 检查员角色
```kotlin
fun buildCheckerPrompt(question: String, answer: String): String = """
你是一个严格的逻辑检查员。请检查以下回答：

问题：$question
回答：$answer

重点检查：
1. 推理步骤是否严密？
2. 有没有逻辑跳跃？
3. 结论是否从前提中合理推出？
4. 有没有遗漏重要条件？

如果发现错误，请指出并修正。如果没有问题，请说明"检查通过"。
"""
```

### 批评者角色
```kotlin
fun buildCriticPrompt(question: String, answer: String): String = """
你是一个挑剔的批评者。你的任务是找出以下回答中的所有问题：

问题：$question
回答：$answer

不要客气，找出每一个可能的：
- 漏洞
- 遗漏
- 不准确之处
- 可以更好的地方

即使回答看起来很好，也要思考"有没有更好的方式"。
"""
```

### 优化师角色
```kotlin
fun buildOptimizerPrompt(question: String, answer: String): String = """
你是一个优化专家。以下回答基本正确，但请优化它：

问题：$question
回答：$answer

优化方向：
1. 表达是否更清晰？
2. 结构是否更合理？
3. 有没有遗漏的边界情况？
4. 能否给出更完整的答案？
"""
```

## 五、自动策略选择

根据问题类型自动选择反思策略：

```kotlin
fun selectReflectionStrategy(question: String): ReflectionStrategy {
    return when {
        // 简单问题：不需要反思
        question.matchesSimplePattern() -> ReflectionStrategy.NONE
        
        // 中等问题：1 轮反思
        question.matchesMediumPattern() -> ReflectionStrategy.SINGLE
        
        // 复杂问题：2 轮角色切换
        else -> ReflectionStrategy.DOUBLE
    }
}

private fun String.matchesSimplePattern(): Boolean {
    return length < 20 || containsAny("是什么", "是谁", "在哪里")
}

private fun String.matchesMediumPattern(): Boolean {
    return containsAny("如何", "怎么", "为什么", "解释")
}
```

## 六、SessionEvent 扩展

增加反思阶段的事件类型：

```kotlin
sealed class SessionEvent {
    // ... 现有事件
    
    // 新增：反思阶段事件
    data class ReflectionStart(val role: String) : SessionEvent()
    data class ReflectionComplete(val role: String) : SessionEvent()
}
```

## 七、性能考虑

### 延迟影响
- 每轮反思增加 ~3-5 秒（取决于模型大小和设备性能）
- 2 轮反思总计增加 ~6-10 秒
- 建议在后台线程执行，不阻塞 UI

### 内存影响
- 每轮反思需要保存完整对话历史
- Gemma 4 E4B 的 token budget 是 ~15872 tokens
- 建议限制反思轮次不超过 3 轮

### 降级策略
- 如果模型加载失败，跳过反思
- 如果反思超时，使用第一轮答案
- 如果内存不足，降级为 NONE 策略

## 八、实施步骤

1. **Phase 1**：创建 `ReflectionStrategy` 和 `ReflectionConfig`
2. **Phase 2**：在 `AgentSession` 中增加反思逻辑
3. **Phase 3**：实现自动策略选择
4. **Phase 4**：扩展 `SessionEvent` 支持反思状态显示
5. **Phase 5**：真机验证和优化

## 九、实验数据参考

基于之前的实验结果：
- 固定提示词第 3 轮变化率 25%
- 角色切换第 3 轮变化率 75%
- 最优轮次：2 轮（初始 +1 次反思）
- 变化率<10% 可认为收敛

## 十、与 OpenMythos 的关联

OpenMythos 的核心思想是**循环深度 Transformer**：
- 固定权重循环调用（类似我们的多轮反思）
- 动态 MoE 路由（类似我们的角色切换）
- LTI 稳定性约束（类似我们的降级策略）

我们的实现验证了 OpenMythos 的理论：**推理深度 = 推理时间，不是参数数量**。

---

*创建时间：2026-04-21*
*基于实验：multi-round-reflection-001*
