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

    // ============ 语义级合并 ============

    /**
     * 语义级合并 — 针对同一条事件的多条提醒
     *
     * 问题场景：同一个日历事件（如 "2026年工作计划与目标"）可能产生多条提醒，
     * 这些提醒因时间戳不同导致 dedupKey 不同，逃过了精确去重。
     *
     * 合并规则：
     * 1. 同一 source + 同一 sourceApp
     * 2. 标题经过归一化后相同（去掉数字、时间、上午/下午等变量部分）
     * 3. 时间差在 2 小时内
     *
     * 满足以上条件则合并：保留最早的一条作为主条目，mergedCount 累加，
     * body 里标注合并信息，importance 取最高值。
     */
    fun semanticMerge(items: List<CenterItem>): List<CenterItem> {
        if (items.isEmpty()) return emptyList()

        val calendarItems = items.filter { it.source == ItemSource.CALENDAR }
        val otherItems = items.filter { it.source != ItemSource.CALENDAR }

        if (calendarItems.isEmpty()) return items

        // 按 sourceApp 分组
        val byApp = calendarItems.groupBy { it.sourceApp }

        val merged = mutableListOf<CenterItem>()

        for ((app, appItems) in byApp) {
            // 每组内按归一化标题分组
            val byTitle = mutableMapOf<String, MutableList<CenterItem>>()
            for (item in appItems) {
                val normalized = normalizeTitle(item.title)
                if (normalized.isNotBlank()) {
                    byTitle.getOrPut(normalized) { mutableListOf() }.add(item)
                } else {
                    // 无法归一化的标题单独保留
                    merged.add(item)
                }
            }

            for ((_, titleGroup) in byTitle) {
                if (titleGroup.size == 1) {
                    merged.add(titleGroup[0])
                    continue
                }

                // 按时间排序
                val sorted = titleGroup.sortedBy { it.timestamp }

                // 时间窗口聚类：相邻条目时间差 <= 2 小时则合并
                val clusters = mutableListOf<MutableList<CenterItem>>()
                var currentCluster = mutableListOf(sorted[0])

                for (i in 1 until sorted.size) {
                    val timeDiff = sorted[i].timestamp - currentCluster.last().timestamp
                    if (timeDiff <= 2 * 60 * 60 * 1000L) {
                        currentCluster.add(sorted[i])
                    } else {
                        clusters.add(currentCluster)
                        currentCluster = mutableListOf(sorted[i])
                    }
                }
                clusters.add(currentCluster)

                for (cluster in clusters) {
                    if (cluster.size == 1) {
                        merged.add(cluster[0])
                    } else {
                        // 合并：保留最早的一条（importance 取最高）
                        val primary = cluster.maxByOrNull { it.importance } ?: cluster[0]
                        val combinedBody = buildString {
                            append(primary.body)
                            if (cluster.size > 1) {
                                append(" （${cluster.size}条相关提醒）")
                            }
                        }
                        merged.add(
                            primary.copy(
                                mergedCount = cluster.size,
                                body = combinedBody
                            )
                        )
                    }
                }
            }
        }

        return (otherItems + merged).sortedByDescending { it.importance }
    }

    /**
     * 标题归一化：去掉数字、时间格式、上午/下午等变量部分，保留核心文本。
     * 例如：
     *   "2026年工作计划与目标 12:35" → "年工作计划与目标"
     *   "上午9点会议提醒" → "点会议提醒"
     *   "下午3:00 项目评审" → "点项目评审"
     */
    internal fun normalizeTitle(title: String): String {
        return title
            .replace(Regex("\\d{1,2}:\\d{2}"), "")              // HH:mm 时间
            .replace(Regex("\\d{1,2}点"), "")                    // N点
            .replace(Regex("\\d{4}年"), "年")                     // YYYY年 → 年
            .replace(Regex("\\d{1,2}月\\d{1,2}日"), "")          // MM月DD日
            .replace(Regex("上午|下午|中午|晚上|凌晨"), "")       // 时段词
            .replace(Regex("\\d+"), "")                          // 剩余数字
            .replace(Regex("\\s+"), " ")                         // 多余空格
            .trim()
    }
}
