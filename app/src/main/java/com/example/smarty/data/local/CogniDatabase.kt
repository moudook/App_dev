package com.example.smarty.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessageEntity
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.ImpressedEntry
import com.example.smarty.data.model.Note

@Database(
    entities = [
        Note::class,
        Category::class,
        ChatSession::class,
        ChatMessageEntity::class,
        AIMemory::class,
        ImpressedEntry::class,
        CalendarEvent::class
    ],
    version = 11,  // Added calendar events
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
                        Migrations.MIGRATION_10_11
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
