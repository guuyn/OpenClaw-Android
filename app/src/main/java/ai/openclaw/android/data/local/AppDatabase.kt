package ai.openclaw.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.openclaw.android.data.model.DynamicSkillEntity
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryVectorEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.security.SecurityKeyManager
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.TriggerLog
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, MemoryEntity::class, MemoryVectorEntity::class, DynamicSkillEntity::class, TriggerRule::class, TriggerLog::class],
    version = 6,
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
    abstract fun dynamicSkillDao(): DynamicSkillDao
    abstract fun triggerRuleDao(): TriggerRuleDao
    abstract fun triggerLogDao(): TriggerLogDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create dynamic_skills with ALL columns to avoid migration validation issues
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dynamic_skills (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "description TEXT NOT NULL, " +
                    "version TEXT NOT NULL, " +
                    "category TEXT NOT NULL DEFAULT 'custom', " +
                    "instructions TEXT NOT NULL, " +
                    "script TEXT NOT NULL, " +
                    "toolsJson TEXT NOT NULL, " +
                    "permissions TEXT NOT NULL DEFAULT '', " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "lastUsedAt INTEGER NOT NULL DEFAULT 0, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, " +
                    "approvalPrefsJson TEXT NOT NULL DEFAULT ''" +
                    ")"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: MIGRATION_3_4 already created dynamic_skills with all columns
                // This migration just bumps the version for Room schema validation
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create trigger_rules table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trigger_rules (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL DEFAULT 1, " +
                    "source TEXT NOT NULL, " +
                    "filtersJson TEXT NOT NULL DEFAULT '[]', " +
                    "actionJson TEXT NOT NULL, " +
                    "cooldownMs INTEGER NOT NULL DEFAULT 300000, " +
                    "scheduleCron TEXT, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0" +
                    ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_source ON trigger_rules(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_enabled ON trigger_rules(enabled)")

                // Create trigger_logs table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trigger_logs (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "ruleId TEXT NOT NULL, " +
                    "eventId TEXT NOT NULL, " +
                    "executedAt INTEGER NOT NULL DEFAULT 0, " +
                    "actionType TEXT NOT NULL, " +
                    "success INTEGER NOT NULL, " +
                    "error TEXT, " +
                    "result TEXT" +
                    ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_logs_ruleId ON trigger_logs(ruleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_logs_executedAt ON trigger_logs(executedAt)")
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade()
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
