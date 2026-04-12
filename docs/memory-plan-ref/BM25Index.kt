package ai.openclaw.android.memory

import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.model.BM25Result
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 SQLite FTS5 的 BM25 关键词索引。
 *
 * 使用 memory_fts 虚拟表对 Memory 的 content 和 tags 做全文检索，
 * 利用 FTS5 内置 bm25() 函数计算相关性分数。
 */
class BM25Index(private val database: AppDatabase) {

    private val ftsDao get() = database.memoryFtsDao()

    /**
     * 为一条记忆建立索引。
     * 使用 INSERT OR REPLACE 保证幂等（重复调用覆盖旧值）。
     */
    suspend fun index(memoryId: Long, content: String, tags: List<String>) {
        withContext(Dispatchers.IO) {
            val tagsStr = tags.joinToString(" ")
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO memory_fts(rowid, content, tags) VALUES (?, ?, ?)",
                arrayOf(memoryId, content, tagsStr)
            )
        }
    }

    /**
     * 删除一条记忆的索引。
     */
    suspend fun removeIndex(memoryId: Long) {
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM memory_fts WHERE rowid = ?",
                arrayOf(memoryId)
            )
        }
    }

    /**
     * BM25 关键词搜索。
     *
     * @param query    搜索关键词，多个词用空格分隔（FTS5 隐式 AND）
     * @param topK     返回最多 topK 条结果
     * @param timeRange 可选时间范围 (startMs, endMs)，过滤 memories.createdAt
     * @return 按 score 降序排列的结果列表
     */
    suspend fun search(
        query: String,
        topK: Int = 10,
        timeRange: Pair<Long, Long>? = null
    ): List<BM25Result> {
        val escaped = query.escapeFts5()

        val (sql, args) = if (timeRange != null) {
            """
                SELECT fts.rowid as memoryId, -bm25(memory_fts) as score
                FROM memory_fts fts
                INNER JOIN memories m ON fts.rowid = m.id
                WHERE memory_fts MATCH ?
                AND m.createdAt BETWEEN ? AND ?
                ORDER BY score DESC
                LIMIT ?
            """ to arrayOf<Any>(escaped, timeRange.first, timeRange.second, topK)
        } else {
            """
                SELECT rowid as memoryId, -bm25(memory_fts) as score
                FROM memory_fts
                WHERE memory_fts MATCH ?
                ORDER BY score DESC
                LIMIT ?
            """ to arrayOf<Any>(escaped, topK)
        }

        return ftsDao.searchRaw(SimpleSQLiteQuery(sql, args))
    }

    /**
     * 转义 FTS5 查询中的特殊字符。
     * 将整个查询用双引号包裹，使其作为短语匹配。
     */
    private fun String.escapeFts5(): String {
        return "\"${this.replace("\"", "\"\"")}\""
    }
}
