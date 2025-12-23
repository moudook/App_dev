package com.example.smarty.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 19,  // v15: isPinned, v16: reminders, v17: note_versions, v18: FTS5 search, v19: isPinned indices
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
        @Volatile
        private var INSTANCE: CogniDatabase? = null

        fun getDatabase(context: Context): CogniDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CogniDatabase::class.java,
                    "cogni_database"
                )
                    .addMigrations(
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
                        Migrations.MIGRATION_18_19  // Performance: isPinned indices
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
