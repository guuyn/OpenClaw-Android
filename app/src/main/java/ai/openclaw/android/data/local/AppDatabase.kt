package ai.openclaw.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryVectorEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.security.SecurityKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, MemoryEntity::class, MemoryVectorEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryVectorDao(): MemoryVectorDao
    abstract fun memoryFtsDao(): MemoryFtsDao

    companion object {
        const val DATABASE_NAME = "openclaw_database"

        init {
            System.loadLibrary("sqlcipher")
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val keyManager = SecurityKeyManager(context)
            val passphrase = keyManager.getOrCreateDatabaseKey()
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL(
                            "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(content, tags)"
                        )
                    }
                })
                .build()
        }
    }
}
