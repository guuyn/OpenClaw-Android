package ai.openclaw.android.personalcenter

import ai.openclaw.android.personalcenter.models.CenterItem
import ai.openclaw.android.personalcenter.sources.ItemSource
import java.security.MessageDigest

/**
 * 跨源去重引擎
 * 
 * 同一事件可能从多个来源到达（如日程提醒 + 通知 + 短信），需要合并展示
 */
object DeduplicationEngine {

    /**
     * 生成去重键
     * 规则：30 分钟时间窗口 + 关键词指纹
     */
    fun generateDedupKey(item: CenterItem): String {
        val timeBucket = item.timestamp / (30 * 60 * 1000L) // 30 分钟窗口
        val keywords = extractKeywords(item.title + " " + item.body)
        val fingerprint = if (keywords.isNotEmpty()) {
            md5(keywords.sorted().joinToString(":"))
        } else {
            ""
        }
        return "${timeBucket}:${fingerprint}"
    }

    /**
     * 对列表执行去重 + 合并
     * - 相同 dedupKey 的条目合并为一条
     * - 保留 importance 最高的作为主条目
     * - 累加 mergedCount
     */
    fun deduplicate(items: List<CenterItem>): List<CenterItem> {
        if (items.isEmpty()) return emptyList()

        val groups = mutableMapOf<String, MutableList<CenterItem>>()

        for (item in items) {
            val key = if (item.dedupKey.isNotBlank()) item.dedupKey else generateDedupKey(item)
            groups.getOrPut(key) { mutableListOf() }.add(item.copy(dedupKey = key))
        }

        return groups.values.map { group ->
            if (group.size == 1) {
                group[0]
            } else {
                // 排序：importance 降序
                val sorted = group.sortedByDescending { it.importance }
                val primary = sorted[0]
                primary.copy(mergedCount = group.size)
            }
        }.sortedByDescending { it.importance }
    }

    /**
     * 提取 2~4 个关键词（中文按词边界，英文按空格/标点）
     */
    internal fun extractKeywords(text: String): List<String> {
        val cleaned = text
            .replace(Regex("[^\\w\\u4e00-\\u9fff]"), " ")
            .trim()
            .lowercase()

        if (cleaned.isBlank()) return emptyList()

        val words = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }

        // 优先取有意义的长词（3+ 字符）
        val longWords = words.filter { it.length >= 3 }
        val shortWords = words.filter { it.length == 2 }

        return when {
            longWords.size >= 2 -> longWords.take(3)
            longWords.size == 1 -> listOf(longWords[0]) + shortWords.take(2)
            else -> shortWords.take(4)
        }
    }

    private fun md5(input: String): String {
        if (input.isBlank()) return ""
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(12) // 截断 12 位
    }
}
