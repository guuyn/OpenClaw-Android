package ai.openclaw.android.data.local

import ai.openclaw.android.data.model.BM25Result
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface MemoryFtsDao {
    @RawQuery
    suspend fun searchRaw(query: SupportSQLiteQuery): List<BM25Result>
}
