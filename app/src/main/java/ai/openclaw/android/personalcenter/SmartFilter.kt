package ai.openclaw.android.personalcenter

import ai.openclaw.android.personalcenter.models.CenterItem
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 智能语义过滤层 — LLM 判断内容价值
 *
 * 架构：
 * - 使用 callback 注入 LLM 调用能力（避免依赖 GatewayManager 单例）
 * - 缓存：相同来源+标题不重复送 LLM（1小时）
 * - 降级：LLM 不可用时退回纯关键词模式
 */
object SmartFilter {

    private val TAG = "SmartFilter"
    private val LLM_CACHE = ConcurrentHashMap<String, CacheEntry>()
    private val CACHE_TTL_MS = 3_600_000L // 1小时

    /** LLM 调用回调，由外部注入 */
    var llmEvaluator: LlmEvaluator? = null

    fun interface LlmEvaluator {
        /**
         * 批量评估内容价值
         * @param items 待评估的条目
         * @return Map<id, value> value 0~1
         */
        suspend fun evaluate(items: List<CenterItem>): Map<String, Float>
    }

    // 纯关键词模式作为 LLM 降级
    private val JUNK_KEYWORDS = setOf(
        // 广告/营销/推广
        "广告", "推广", "营销", "优惠券", "红包", "抽奖", "签到", "砍价",
        "限时特惠", "限时秒杀", "新品首发", "清仓", "打折",
        "拼多多", "拼多多", "淘宝", "天猫", "京东",
        // 垃圾应用
        "清理大师", "加速大师", "WiFi 万能", "电池管理", "万能钥匙",
        // 游戏/娱乐推送
        "每日签到", "每日任务", "登录奖励", "连续登录",
        // 英文
        "ad", "promotion", "spam", "unsubscribe", "limited offer", "flash sale"
    )

    // 白名单（即使命中关键词也不过滤）
    private val WHITELIST_KEYWORDS = setOf(
        "验证码", "verification", "otp", "code",
        "银行", "bank", "账户", "交易", "扣款", "到账",
        "来电", "通话", "电话", "call", "missed",
        "政务", "政府", "公安", "医院", "保险"
    )

    data class CacheEntry(
        val value: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 批量过滤 — 主入口
     */
    suspend fun filterBatch(items: List<CenterItem>): List<CenterItem> {
        if (items.isEmpty()) return emptyList()

        val filtered = mutableListOf<CenterItem>()
        val pending = mutableListOf<CenterItem>()

        for (item in items) {
            val cached = getCache(item)
            if (cached != null) {
                if (cached >= 0.3f) filtered.add(item)
            } else {
                pending.add(item)
            }
        }

        // 有 LLM 就用 LLM，否则用关键词降级
        if (pending.isNotEmpty() && llmEvaluator != null) {
            try {
                val results = llmEvaluator!!.evaluate(pending)
                for (item in pending) {
                    val value = results[item.id] ?: 0.5f
                    setCache(item, value)
                    if (value >= 0.3f) filtered.add(item)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LLM evaluation failed, fallback: ${e.message}")
                // 降级：关键词模式放行的都保留
                for (item in pending) {
                    if (!isJunkByKeyword(item)) {
                        filtered.add(item)
                        setCache(item, 0.5f)
                    }
                }
            }
        } else {
            // 无 LLM，关键词降级
            for (item in pending) {
                if (!isJunkByKeyword(item)) {
                    filtered.add(item)
                    setCache(item, 0.5f)
                }
            }
        }

        return filtered
    }

    /**
     * 关键词快速判断
     */
    private fun isJunkByKeyword(item: CenterItem): Boolean {
        val text = (item.title + " " + item.body).lowercase()

        // 白名单优先放行
        if (WHITELIST_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
            return false
        }

        return JUNK_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    // ========== 缓存管理 ==========

    private fun getCache(item: CenterItem): Float? {
        val key = buildCacheKey(item)
        val entry = LLM_CACHE[key]
        return if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            entry.value
        } else {
            LLM_CACHE.remove(key)
            null
        }
    }

    private fun setCache(item: CenterItem, value: Float) {
        val key = buildCacheKey(item)
        LLM_CACHE[key] = CacheEntry(value)
    }

    private fun buildCacheKey(item: CenterItem): String {
        // 包含 body 前 50 字符的 hash，避免相同标题但内容不同的缓存冲突
        val bodySnippet = item.body.take(50).hashCode()
        return "${item.source}_${item.title.hashCode()}_${bodySnippet}"
    }

    fun clearCache() {
        LLM_CACHE.clear()
    }
}
