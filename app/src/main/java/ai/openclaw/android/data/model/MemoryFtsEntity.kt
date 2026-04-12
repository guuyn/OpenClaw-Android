package ai.openclaw.android.data.model

/**
 * FTS5 虚拟表行映射。
 * 对应 SQL: CREATE VIRTUAL TABLE memory_fts USING fts5(content, tags)
 *
 * 注意：FTS5 虚拟表不由 Room 注解管理，DDL 在 AppDatabase.Callback 中执行。
 */
data class MemoryFtsEntity(
    val rowid: Long,
    val content: String,
    val tags: String
)
