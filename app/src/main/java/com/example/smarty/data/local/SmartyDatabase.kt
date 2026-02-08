package com.example.smarty.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smarty.data.model.AgentExecution
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessageEntity
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.ImpressedEntry
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ConnectionUsage
import com.example.smarty.data.model.SmartyTimer
import com.example.smarty.data.model.NoteVersion

@Database(
    entities = [
        Note::class,
        Category::class,
        ChatSession::class,
        ChatMessageEntity::class,
        AIMemory::class,
        ImpressedEntry::class,
        CalendarEvent::class,
        AgentExecution::class,      // AI agent execution tracking
        ConnectionUsage::class,       // Connection usage for rate limiting
        NoteVersion::class,         // Note version history for git-like versioning
        SmartyTimer::class          // Persisted timers and alarms
    ],
    version = 29,  // v29: Added index for googleEventId in calendar_events
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SmartyDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chatDao(): ChatDao
    abstract fun aiMemoryDao(): AIMemoryDao
    abstract fun impressedLogDao(): ImpressedLogDao
    abstract fun calendarDao(): CalendarDao
    abstract fun agentExecutionDao(): AgentExecutionDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun timerDao(): TimerDao

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
        private val databaseCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                ensureFtsTableExists(db)
                // One-time fix: Ensure all existing notes have isReadForMemory = 0
                // This handles the case where notes existed before the AI memory feature was added
                ensureMemoryReadFlagsReset(db)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // For fresh installs, create the FTS table immediately
                createFtsTable(db)
            }

            private fun ensureFtsTableExists(db: SupportSQLiteDatabase) {
                try {
                    // Check if notes_fts table exists
                    val cursor = db.query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='notes_fts'"
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

            /**
             * One-time fix: Reset isReadForMemory to 0 for ALL notes.
             * This ensures notes created before the AI memory feature was added
             * are properly flagged for memory learning.
             * 
             * Uses a marker in the database to track if this fix has been applied.
             * We use a simple approach: always reset on first run after update.
             */
            private fun ensureMemoryReadFlagsReset(db: SupportSQLiteDatabase) {
                try {
                    // Check if there are any AI memories stored
                    val memoryCursor = db.query("SELECT COUNT(*) FROM ai_memories")
                    val memoryCount = memoryCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    // DETAILED DIAGNOSTICS: Count notes with each flag
                    val totalCursor = db.query("SELECT COUNT(*) FROM notes")
                    val totalAllNotes = totalCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    val archivedCursor = db.query("SELECT COUNT(*) FROM notes WHERE isArchived = 1")
                    val archivedCount = archivedCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    val fullPrivacyCursor = db.query("SELECT COUNT(*) FROM notes WHERE isFullPrivacy = 1")
                    val fullPrivacyCount = fullPrivacyCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    val excludedCursor = db.query("SELECT COUNT(*) FROM notes WHERE excludeFromAiChat = 1")
                    val excludedCount = excludedCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    val markedAsReadCursor = db.query("SELECT COUNT(*) FROM notes WHERE isReadForMemory = 1")
                    val markedAsReadCount = markedAsReadCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    
                    Log.i(TAG, "=== MEMORY SYNC DIAGNOSTICS ===")
                    Log.i(TAG, "Total notes in DB: $totalAllNotes")
                    Log.i(TAG, "  - Archived: $archivedCount")
                    Log.i(TAG, "  - Full Privacy: $fullPrivacyCount")
                    Log.i(TAG, "  - Excluded from AI: $excludedCount")
                    Log.i(TAG, "  - Marked as read for memory: $markedAsReadCount")
                    Log.i(TAG, "AI Memories stored: $memoryCount")
                    
                    // Count eligible notes (non-archived, not private, not excluded)
                    val eligibleCursor = db.query("SELECT COUNT(*) FROM notes WHERE isArchived = 0 AND isFullPrivacy = 0 AND excludeFromAiChat = 0")
                    val eligibleNotes = eligibleCursor.use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    Log.i(TAG, "Eligible notes (non-archived, non-private): $eligibleNotes")
                    
                    // If NO memories exist AND there are eligible notes, reset them ALL for analysis
                    if (memoryCount == 0 && eligibleNotes > 0) {
                        Log.i(TAG, ">>> RESETTING: No memories exist, resetting $eligibleNotes notes for memory analysis")
                        // Reset ALL notes to isReadForMemory = 0
                        db.execSQL("UPDATE notes SET isReadForMemory = 0")
                        
                        // Verify the fix worked
                        val verifyCount = db.query("SELECT COUNT(*) FROM notes WHERE isReadForMemory = 0 AND isArchived = 0 AND isFullPrivacy = 0 AND excludeFromAiChat = 0")
                        val newUnreadCount = verifyCount.use {
                            if (it.moveToFirst()) it.getInt(0) else 0
                        }
                        Log.i(TAG, ">>> After reset: $newUnreadCount notes now eligible for memory sync")
                    } else if (eligibleNotes == 0 && totalAllNotes > 0) {
                        Log.w(TAG, ">>> WARNING: All $totalAllNotes notes are blocked by privacy flags or archived!")
                    }
                    Log.i(TAG, "================================")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ensureMemoryReadFlagsReset", e)
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
                    db.execSQL("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
                            title,
                            content,
                            summary,
                            content='notes',
                            content_rowid='rowid'
                        )
                    """)

                    // Populate FTS table with existing data
                    db.execSQL("""
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """)

                    // Create triggers to keep FTS table in sync (FTS5 syntax)
                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """)

                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                            INSERT INTO notes_fts(notes_fts, rowid, title, content, summary)
                            VALUES('delete', old.rowid, COALESCE(old.title, ''), COALESCE(old.content, ''), COALESCE(old.summary, ''));
                        END
                    """)

                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
                            INSERT INTO notes_fts(notes_fts, rowid, title, content, summary)
                            VALUES('delete', old.rowid, COALESCE(old.title, ''), COALESCE(old.content, ''), COALESCE(old.summary, ''));
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """)

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
                    db.execSQL("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts4(
                            title,
                            content,
                            summary,
                            content="notes"
                        )
                    """)

                    // Populate FTS table with existing data
                    db.execSQL("""
                        INSERT OR IGNORE INTO notes_fts(rowid, title, content, summary)
                        SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
                    """)

                    // Create triggers to keep FTS table in sync (FTS4 syntax - different from FTS5)
                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """)

                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                            DELETE FROM notes_fts WHERE rowid = old.rowid;
                        END
                    """)

                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
                            DELETE FROM notes_fts WHERE rowid = old.rowid;
                            INSERT INTO notes_fts(rowid, title, content, summary)
                            VALUES (new.rowid, COALESCE(new.title, ''), COALESCE(new.content, ''), COALESCE(new.summary, ''));
                        END
                    """)

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
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmartyDatabase::class.java,
                    "Smarty_database"
                )
                    .addCallback(databaseCallback)
                    .addMigrations(
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
                        Migrations.MIGRATION_18_19,  // Performance: isPinned indices
                        Migrations.MIGRATION_19_20,  // Citations storage
                        Migrations.MIGRATION_20_21,  // Performance: composite indices
                        Migrations.MIGRATION_21_22,  // Performance: processingStatus indices for queue
                        Migrations.MIGRATION_22_23,  // Feature: chunkAnalysesJson for per-page analyses
                        Migrations.MIGRATION_23_24,  // Feature: inlineImagesJson for image viewing in chat
                        Migrations.MIGRATION_24_25,  // Feature: isReadForMemory for AI memory learning
                        Migrations.MIGRATION_25_26,   // Fix: @ColumnInfo defaultValue annotation
                        Migrations.MIGRATION_26_27,   // Feature: googleEventId for calendar_events sync
                        Migrations.MIGRATION_27_28,   // Feature: SmartyTimer for persistent alarms
                        Migrations.MIGRATION_28_29    // Performance: googleEventId index for calendar sync
                    )
                    // NOTE: Removed fallbackToDestructiveMigration to preserve user data
                    // All migrations must be properly defined in Migrations.kt
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
