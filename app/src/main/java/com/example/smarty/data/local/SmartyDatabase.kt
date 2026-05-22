package com.example.smarty.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.ChatMessageEntity
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.ImpressedEntry
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.data.local.NoteStackEntity
import com.example.smarty.data.local.StackEntity
import com.example.smarty.data.local.SyncQueueItem
import com.example.smarty.data.local.ConflictRecord
import com.example.smarty.data.local.UserEntity
import com.example.smarty.data.local.SyncStateEntity
import com.example.smarty.data.local.TagEntity
import com.example.smarty.data.local.ChatFolderEntity
import com.example.smarty.data.local.TaskEntity
import com.example.smarty.data.local.ReasoningTraceEntity
import com.example.smarty.data.local.ReasoningSummaryEntity
import com.example.smarty.data.local.AgentCheckpointEntity
import com.example.smarty.data.local.SearchHistoryEntity
import com.example.smarty.data.local.UserFcmTokenEntity
import com.example.smarty.data.local.DailyDigestEntity
import com.example.smarty.data.local.SharedItemEntity
import com.example.smarty.data.local.NoteTaskEntity
import com.example.smarty.data.model.AIMemory
import com.example.smarty.features.chat.domain.memory.AIMemoryDao
import com.example.smarty.core.domain.model.AgentStepEntity
import com.example.smarty.data.local.dao.AgentStepDao

@Database(
    entities = [
        // ── Core entities ──
        Note::class,
        Category::class,
        ChatSession::class,
        ChatMessageEntity::class,
        AgentStepEntity::class,
        ImpressedEntry::class,
        CalendarEvent::class,
        SmartyTimer::class,
        CachedAIResponse::class,
        AIMemory::class,
        SyncQueueItem::class,
        ConflictRecord::class,
        // Junction tables for note relationships (v4.2.0)
        ChatMessageNote::class,
        CalendarEventNote::class,

        // ── Consolidated entities (from SmartDatabase) ──
        UserEntity::class,
        SyncStateEntity::class,
        TagEntity::class,
        ChatFolderEntity::class,
        TaskEntity::class,
        ReasoningTraceEntity::class,
        ReasoningSummaryEntity::class,
        AgentCheckpointEntity::class,
        SearchHistoryEntity::class,
        UserFcmTokenEntity::class,
        DailyDigestEntity::class,
        SharedItemEntity::class,
        NoteTagEntity::class,
        NoteTaskEntity::class,
        NoteVersionEntity::class, // for SmartDatabaseDao (note_versions_ext)
        // Stacks (Phase 1B)
        StackEntity::class,
        NoteStackEntity::class,
        com.example.smarty.data.local.entity.TimelineEventEntity::class,
    ],
    version = 43,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SmartyDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chatDao(): ChatDao
    abstract fun agentStepDao(): AgentStepDao
    abstract fun timelineEventDao(): com.example.smarty.data.local.dao.TimelineEventDao
    abstract fun impressedLogDao(): ImpressedLogDao
    abstract fun calendarDao(): CalendarDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun timerDao(): TimerDao
    abstract fun aiCacheDao(): AICacheDao
    abstract fun aiMemoryDao(): AIMemoryDao
    abstract fun syncQueueDao(): SyncQueueDao
    // Junction table DAOs (v4.2.0)
    abstract fun chatMessageNotesDao(): ChatMessageNotesDao
    abstract fun calendarEventNotesDao(): CalendarEventNotesDao

    // ── Consolidated DAOs (from SmartDatabaseDao) ──
    abstract fun smartDao(): SmartDatabaseDao

    companion object {
        private const val TAG = "SmartyDatabase"

        @Volatile
        private var INSTANCE: SmartyDatabase? = null

        // Track which FTS version is available (null = not checked yet)
        @Volatile
        private var ftsVersion: Int? = null

        fun getFtsVersion(): Int = ftsVersion ?: 0

        private val databaseCallback =
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    ensureFtsTableExists(db)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    createFtsTable(db)
                }

                private fun ensureFtsTableExists(db: SupportSQLiteDatabase) {
                    try {
                        val cursor =
                            db.query(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name='notes_fts'",
                            )
                        val exists = cursor.use { it.count > 0 }
                        if (!exists) {
                            Log.w(TAG, "FTS table missing - creating it now")
                            createFtsTable(db)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking/creating FTS table", e)
                    }
                }

                private fun createFtsTable(db: SupportSQLiteDatabase) {
                    if (tryCreateFts5Table(db)) {
                        ftsVersion = 5
                        Log.i(TAG, "FTS5 table created/verified successfully")
                    } else if (tryCreateFts4Table(db)) {
                        ftsVersion = 4
                        Log.i(TAG, "FTS4 table created/verified successfully (FTS5 not available)")
                    } else {
                        ftsVersion = 0
                        Log.w(TAG, "FTS not available - search will use LIKE queries (slower)")
                    }
                }

                private fun tryCreateFts5Table(db: SupportSQLiteDatabase): Boolean {
                    return try {
                        dropFtsTableAndTriggers(db)
                        db.execSQL(
                            """
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
                            title,
                            content,
                            summary,
                            content='notes',
                            content_rowid='rowid'
                        )
                    """,
                        )
                        db.execSQL(
                            """
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                            INSERT INTO notes_fts(notes_fts, rowid, title, content, summary)
                            VALUES('delete', old.rowid, COALESCE(old.title, ''), COALESCE(old.content, ''), COALESCE(old.summary, ''));
                        END
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
                            INSERT INTO notes_fts(notes_fts, rowid, title, content, summary)
                            VALUES('delete', old.rowid, COALESCE(old.title, ''), COALESCE(old.content, ''), COALESCE(old.summary, ''));
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """,
                        )
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "FTS5 not available: ${e.message}")
                        false
                    }
                }

                private fun tryCreateFts4Table(db: SupportSQLiteDatabase): Boolean {
                    return try {
                        dropFtsTableAndTriggers(db)
                        db.execSQL(
                            """
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts4(
                            title,
                            content,
                            summary,
                            content="notes"
                        )
                    """,
                        )
                        db.execSQL(
                            """
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                            DELETE FROM notes_fts WHERE rowid = old.rowid;
                        END
                    """,
                        )
                        db.execSQL(
                            """
                        CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
                            DELETE FROM notes_fts WHERE rowid = old.rowid;
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """,
                        )
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "FTS4 also not available: ${e.message}")
                        false
                    }
                }

                private fun dropFtsTableAndTriggers(db: SupportSQLiteDatabase) {
                    try {
                        db.execSQL("DROP TRIGGER IF EXISTS notes_fts_ai")
                        db.execSQL("DROP TRIGGER IF EXISTS notes_fts_ad")
                        db.execSQL("DROP TRIGGER IF EXISTS notes_fts_au")
                        db.execSQL("DROP TABLE IF EXISTS notes_fts")
                    } catch (e: Exception) {
                        // Ignore - table might not exist
                    }
                }
            }

        fun getDatabase(context: Context): SmartyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        SmartyDatabase::class.java,
                        "Smarty_database",
                    )
                        .addCallback(databaseCallback)
                        .addMigrations(
                            // Legacy migrations
                            Migrations.MIGRATION_1_2,
                            Migrations.MIGRATION_2_3,
                            Migrations.MIGRATION_3_4,
                            Migrations.MIGRATION_4_5,
                            Migrations.MIGRATION_5_6,
                            Migrations.MIGRATION_6_7,
                            Migrations.MIGRATION_7_8,
                            Migrations.MIGRATION_8_9,
                            Migrations.MIGRATION_9_10,
                            Migrations.MIGRATION_10_11,
                            Migrations.MIGRATION_11_12,
                            Migrations.MIGRATION_12_13,
                            Migrations.MIGRATION_13_14,
                            Migrations.MIGRATION_14_15,
                            Migrations.MIGRATION_15_16,
                            Migrations.MIGRATION_16_17,
                            Migrations.MIGRATION_17_18,
                            Migrations.MIGRATION_18_19,
                            Migrations.MIGRATION_19_20,
                            Migrations.MIGRATION_20_21,
                            Migrations.MIGRATION_21_22,
                            Migrations.MIGRATION_22_23,
                            Migrations.MIGRATION_23_24,
                            Migrations.MIGRATION_24_25,
                            Migrations.MIGRATION_25_26,
                            Migrations.MIGRATION_26_27,
                            Migrations.MIGRATION_27_28,
                            Migrations.MIGRATION_28_29,
                            Migrations.MIGRATION_29_30,
                            Migrations.MIGRATION_30_31,
                            Migrations.MIGRATION_31_32,
                            Migrations.MIGRATION_32_33,
                            Migrations.MIGRATION_33_34,
                            Migrations.MIGRATION_34_35,
                            Migrations.MIGRATION_35_36,
                            Migrations.MIGRATION_36_37,
                            Migrations.MIGRATION_37_38,
                            // Consolidated migration: adds new tables from SmartDatabase
                            Migrations.MIGRATION_38_39,
                            Migrations.MIGRATION_39_40,
                            Migrations.MIGRATION_40_41,
                            Migrations.MIGRATION_41_42,
                            Migrations.MIGRATION_42_43,
                        )
                        .fallbackToDestructiveMigration()
                        .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
