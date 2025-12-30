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
import com.example.smarty.data.model.ProviderUsage
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
        ProviderUsage::class,       // Provider usage for rate limiting
        NoteVersion::class          // Note version history for git-like versioning
    ],
    version = 21,  // v15: isPinned, v16: reminders, v17: note_versions, v18: FTS5 search, v19: isPinned indices, v20: citationsJson, v21: composite indices
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CogniDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chatDao(): ChatDao
    abstract fun aiMemoryDao(): AIMemoryDao
    abstract fun impressedLogDao(): ImpressedLogDao
    abstract fun calendarDao(): CalendarDao
    abstract fun agentExecutionDao(): AgentExecutionDao
    abstract fun noteVersionDao(): NoteVersionDao

    companion object {
        private const val TAG = "CogniDatabase"

        @Volatile
        private var INSTANCE: CogniDatabase? = null

        /**
         * Callback to ensure FTS5 table exists.
         * FTS tables are defined in migrations but NOT as Room entities,
         * so they don't get created on fresh database creation (only on migration).
         * This callback ensures the FTS table exists whenever the database is opened.
         */
        private val databaseCallback = object : RoomDatabase.Callback() {
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

            private fun createFtsTable(db: SupportSQLiteDatabase) {
                try {
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

                    // Create triggers to keep FTS table in sync
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

                    Log.i(TAG, "FTS table created/verified successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create FTS table", e)
                }
            }
        }

        fun getDatabase(context: Context): CogniDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CogniDatabase::class.java,
                    "cogni_database"
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
                        Migrations.MIGRATION_20_21   // Performance: composite indices
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
