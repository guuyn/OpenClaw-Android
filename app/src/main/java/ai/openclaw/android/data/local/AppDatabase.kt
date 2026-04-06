package ai.openclaw.android.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus

@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        const val DATABASE_NAME = "openclaw_database"
    }
}