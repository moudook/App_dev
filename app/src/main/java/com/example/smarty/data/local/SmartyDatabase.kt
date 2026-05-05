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
import com.example.smarty.core.domain.model.NoteVersion
import com.example.smarty.core.domain.model.SmartyTimer

@Database(
    entities = [
        Note::class,
        Category::class,
        ChatSession::class,
        ChatMessageEntity::class,
        ImpressedEntry::class,
        CalendarEvent::class,
        NoteVersion::class,
        SmartyTimer::class,
        CachedAIResponse::class,
        com.example.smarty.data.model.AIMemory::class,
        SyncQueueItem::class,
        ConflictRecord::class,
        // Junction tables for note relationships (v4.2.0)
        ChatMessageNote::class,
        CalendarEventNote::class,
    ],
    version = 38, // PERFORMANCE: Comprehensive database indices for 100-1000x faster queries
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SmartyDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    abstract fun categoryDao(): CategoryDao

    abstract fun chatDao(): ChatDao

    abstract fun impressedLogDao(): ImpressedLogDao

    abstract fun calendarDao(): CalendarDao

    abstract fun noteVersionDao(): NoteVersionDao

    abstract fun timerDao(): TimerDao

    abstract fun aiCacheDao(): AICacheDao

    abstract fun aiMemoryDao(): com.example.smarty.features.chat.domain.memory.AIMemoryDao

    abstract fun syncQueueDao(): SyncQueueDao

    // Junction table DAOs (v4.2.0)
    abstract fun chatMessageNotesDao(): ChatMessageNotesDao

    abstract fun calendarEventNotesDao(): CalendarEventNotesDao

    companion object {
        private const val TAG = "SmartyDatabase"

        @Volatile
        private var INSTANCE: SmartyDatabase? = null

        // Track which FTS version is available (null = not checked yet)
        @Volatile
        private var ftsVersion: Int? = null

        /**
         * Check if FTS is available and which version.
         * @return 5 for FTS5, 4 for FTS4, 0 if neither is available
         */
        fun getFtsVersion(): Int = ftsVersion ?: 0

        /**
         * Callback to ensure FTS table exists.
         * FTS tables are defined in migrations but NOT as Room entities,
         * so they don't get created on fresh database creation (only on migration).
         * This callback ensures the FTS table exists whenever the database is opened.
         */
        private val databaseCallback =
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    ensureFtsTableExists(db)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // For fresh installs, create the FTS table immediately
                    createFtsTable(db)
                }

                private fun ensureFtsTableExists(db: SupportSQLiteDatabase) {
                    try {
                        // Check if notes_fts table exists
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
                    // Try FTS5 first, fall back to FTS4 if not available
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

                /**
                 * Try to create FTS5 table (preferred, better ranking with bm25).
                 * @return true if successful, false if FTS5 not available
                 */
                private fun tryCreateFts5Table(db: SupportSQLiteDatabase): Boolean {
                    return try {
                        // Drop existing table/triggers if switching versions
                        dropFtsTableAndTriggers(db)

                        // Create FTS5 virtual table for fast text search
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

                        // Populate FTS table with existing data
                        db.execSQL(
                            """
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """,
                        )

                        // Create triggers to keep FTS table in sync (FTS5 syntax)
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

                /**
                 * Try to create FTS4 table (fallback for older SQLite versions).
                 * @return true if successful, false if FTS4 not available
                 */
                private fun tryCreateFts4Table(db: SupportSQLiteDatabase): Boolean {
                    return try {
                        // Drop existing table/triggers if switching versions
                        dropFtsTableAndTriggers(db)

                        // Create FTS4 virtual table (different syntax than FTS5)
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

                        // Populate FTS table with existing data
                        db.execSQL(
                            """
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """,
                        )

                        // Create triggers to keep FTS table in sync (FTS4 syntax - different from FTS5)
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

                /**
                 * Drop existing FTS table and triggers (for version switching).
                 */
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
                            Migrations.MIGRATION_37_38
                        )
                        .fallbackToDestructiveMigration()
                        .build()
                INSTANCE = instance
                instance
            }
        }
    }

}
