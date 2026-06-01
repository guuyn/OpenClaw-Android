package ai.openclaw.android.trigger.v2

import ai.openclaw.android.trigger.models.TriggerEvent
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.LogManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * AITriggerDecision — AI 决策模块
 *
 * 接收上下文（通知内容、时间、设备状态、用户历史），
 * 调用 LLM 判断是否应该触发，不阻塞主流程。
 *
 * 特性：
 * - 决策缓存（5分钟内相同场景不用重新问 LLM）
 * - 用户反馈学习（用户纠正 → 更新本地规则权重）
 * - 异步决策，带超时保护
 * - 降级模式（LLM 不可用时使用规则权重）
 */
class AITriggerDecision(
    private val agentSessionFactory: suspend () -> AgentSession?,
    private val triggerEngine: TriggerEngine? = null
) {
    companion object {
        private const val TAG = "AITriggerDecision"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5分钟缓存
        private const val DECISION_TIMEOUT_MS = 8000L    // 8秒超时
        private const val DEFAULT_WEIGHT_THRESHOLD = 0.5f
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 决策缓存: cacheKey -> DecisionResult
    private val decisionCache = ConcurrentHashMap<String, DecisionCacheEntry>()

    // 规则权重: ruleId -> weight (0.0 ~ 1.0)
    private val ruleWeights = ConcurrentHashMap<String, Float>()

    // 决策统计流
    private val _decisionStats = MutableStateFlow(DecisionStats())
    val decisionStats: StateFlow<DecisionStats> = _decisionStats.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 判断是否应该触发
     *
     * 流程：
     * 1. 检查缓存
     * 2. 构造上下文 Prompt
     * 3. 异步调用 LLM
     * 4. 缓存结果
     * 5. 降级：超时/失败时使用规则权重
     */
    suspend fun shouldTrigger(rule: TriggerRule, event: TriggerEvent): Boolean {
        val cacheKey = buildCacheKey(rule, event)

        // 1. 检查缓存
        decisionCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                Log.d(TAG, "Cache hit for rule ${rule.id}, cached decision: ${cached.shouldTrigger}")
                updateStats(usedCache = true)
                return cached.shouldTrigger
            }
        }

        // 2. 构造 Prompt 并调用 LLM（带超时）
        val result = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            queryLLMForDecision(rule, event)
        }

        if (result != null) {
            // 3. 缓存结果
            decisionCache[cacheKey] = DecisionCacheEntry(
                shouldTrigger = result.shouldTrigger,
                confidence = result.confidence,
                timestamp = System.currentTimeMillis(),
                reasoning = result.reasoning
            )
            updateStats(usedLLM = true, llmAgreed = result.shouldTrigger)
            Log.i(TAG, "LLM decision for ${rule.name}: trigger=${result.shouldTrigger}, confidence=${result.confidence}")
            return result.shouldTrigger
        }

        // 4. 降级：使用规则权重
        val weight = ruleWeights[rule.id] ?: DEFAULT_WEIGHT_THRESHOLD
        val fallbackDecision = weight >= DEFAULT_WEIGHT_THRESHOLD
        Log.d(TAG, "LLM unavailable, fallback to weight=$weight for ${rule.name}: trigger=$fallbackDecision")
        updateStats(usedFallback = true)
        return fallbackDecision
    }

    /**
     * 调用 LLM 获取触发决策
     */
    private suspend fun queryLLMForDecision(rule: TriggerRule, event: TriggerEvent): DecisionResult? {
        val session = agentSessionFactory()
        if (session == null) {
            Log.w(TAG, "AgentSession not available, skipping LLM decision")
            return null
        }

        val prompt = buildDecisionPrompt(rule, event)

        return try {
            val response = session.handleMessage(prompt)
            parseDecisionResponse(response)
        } catch (e: Exception) {
            Log.w(TAG, "LLM decision failed: ${e.message}")
            null
        }
    }

    /**
     * 构造决策 Prompt
     */
    private fun buildDecisionPrompt(rule: TriggerRule, event: TriggerEvent): String {
        val deviceState = triggerEngine?.getDeviceState() ?: emptyMap()
        val title = event.payload["title"] as? String ?: ""
        val text = event.payload["text"] as? String ?: ""
        val packageName = event.payload["package"] as? String ?: ""
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        return """
            You are an intelligent trigger decision engine for an Android notification assistant.
            
            ## Current Rule
            - Name: ${rule.name}
            - Description: This rule monitors ${event.source.name} events
            - Current time: ${hour}:00
            - Device state: ${deviceState.entries.joinToString(", ") { "${it.key}=${it.value}" }}
            
            ## Incoming Event
            - Source: ${event.source.name}
            - Package: $packageName
            - Title: $title
            - Content: $text
            
            ## Task
            Should this event trigger the rule's action?
            
            Consider:
            1. Is this a duplicate or low-value notification?
            2. Is the current time appropriate for interruptions?
            3. Is the device state suitable (e.g., don't interrupt during low battery)?
            4. Is the content meaningful enough to warrant action?
            
            Respond in JSON format:
            {
              "shouldTrigger": true/false,
              "confidence": 0.0-1.0,
              "reasoning": "brief explanation"
            }
        """.trimIndent()
    }

    /**
     * 解析 LLM 返回的决策
     */
    private fun parseDecisionResponse(response: String): DecisionResult? {
        return try {
            // 尝试从响应中提取 JSON
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                val parsed = json.decodeFromString<LLMDecisionResponse>(jsonStr)
                DecisionResult(
                    shouldTrigger = parsed.shouldTrigger,
                    confidence = parsed.confidence,
                    reasoning = parsed.reasoning
                )
            } else {
                // 尝试从文本中判断
                val shouldTrigger = response.contains("true", ignoreCase = true) &&
                        !response.contains("false", ignoreCase = true)
                DecisionResult(
                    shouldTrigger = shouldTrigger,
                    confidence = 0.5f,
                    reasoning = "Parsed from text response"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse LLM decision: ${e.message}")
            null
        }
    }

    /**
     * 用户反馈：当用户纠正 AI 决策时调用
     * 更新本地规则权重，实现增量学习
     */
    fun onUserFeedback(ruleId: String, expectedTrigger: Boolean, actualTrigger: Boolean) {
        val currentWeight = ruleWeights[ruleId] ?: DEFAULT_WEIGHT_THRESHOLD
        val correction = if (expectedTrigger != actualTrigger) {
            // 用户纠正：如果应该触发但没触发，增加权重；反之减少
            if (expectedTrigger) 0.1f else -0.1f
        } else {
            // 决策正确：微调权重
            if (expectedTrigger) 0.02f else -0.02f
        }

        val newWeight = (currentWeight + correction).coerceIn(0.0f, 1.0f)
        ruleWeights[ruleId] = newWeight

        Log.i(TAG, "Rule $ruleId weight updated: $currentWeight → $newWeight (user expected=$expectedTrigger)")

        // 清理相关缓存，确保下次使用新权重
        clearCacheForRule(ruleId)
    }

    /**
     * 动作执行后回调（用于收集统计数据）
     */
    fun onActionExecuted(rule: TriggerRule, event: TriggerEvent, success: Boolean) {
        if (!success) {
            // 执行失败：降低权重
            val currentWeight = ruleWeights[rule.id] ?: DEFAULT_WEIGHT_THRESHOLD
            ruleWeights[rule.id] = (currentWeight - 0.05f).coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * 获取规则当前权重
     */
    fun getRuleWeight(ruleId: String): Float = ruleWeights[ruleId] ?: DEFAULT_WEIGHT_THRESHOLD

    /**
     * 清除指定规则的缓存
     */
    fun clearCacheForRule(ruleId: String) {
        decisionCache.entries.removeAll { it.key.contains(ruleId) }
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        decisionCache.clear()
    }

    /**
     * 构造缓存 Key
     */
    private fun buildCacheKey(rule: TriggerRule, event: TriggerEvent): String {
        val title = event.payload["title"] as? String ?: ""
        val packageName = event.payload["package"] as? String ?: ""
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 2 // 按2小时粒度分组
        return "${rule.id}:${packageName}:${title.hashCode()}:h$hour"
    }

    /**
     * 更新统计数据
     */
    private fun updateStats(
        usedCache: Boolean = false,
        usedLLM: Boolean = false,
        usedFallback: Boolean = false,
        llmAgreed: Boolean = false
    ) {
        _decisionStats.value = DecisionStats(
            totalDecisions = _decisionStats.value.totalDecisions + 1,
            cacheHits = _decisionStats.value.cacheHits + if (usedCache) 1 else 0,
            llmCalls = _decisionStats.value.llmCalls + if (usedLLM) 1 else 0,
            fallbackCalls = _decisionStats.value.fallbackCalls + if (usedFallback) 1 else 0,
            llmAgreements = _decisionStats.value.llmAgreements + if (llmAgreed) 1 else 0
        )
    }
}

// ==================== 数据模型 ====================

/**
 * 决策缓存条目
 */
data class DecisionCacheEntry(
    val shouldTrigger: Boolean,
    val confidence: Float,
    val timestamp: Long,
    val reasoning: String? = null
)

/**
 * LLM 决策结果
 */
data class DecisionResult(
    val shouldTrigger: Boolean,
    val confidence: Float,
    val reasoning: String? = null
)

/**
 * LLM 决策响应（JSON 格式）
 */
@Serializable
data class LLMDecisionResponse(
    val shouldTrigger: Boolean,
    val confidence: Float,
    val reasoning: String
)

/**
 * 决策统计数据
 */
data class DecisionStats(
    val totalDecisions: Long = 0,
    val cacheHits: Long = 0,
    val llmCalls: Long = 0,
    val fallbackCalls: Long = 0,
    val llmAgreements: Long = 0
) {
    val cacheHitRate: Float
        get() = if (totalDecisions > 0) cacheHits.toFloat() / totalDecisions else 0f

    val llmCallRate: Float
        get() = if (totalDecisions > 0) llmCalls.toFloat() / totalDecisions else 0f
}
