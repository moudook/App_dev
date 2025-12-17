package com.example.smarty.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessageEntity
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.Note

@Database(
    entities = [Note::class, Category::class, ChatSession::class, ChatMessageEntity::class],
    version = 8,  // Incremented for chat history tables
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CogniDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chatDao(): ChatDao

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
                        Migrations.MIGRATION_6_7,
                        Migrations.MIGRATION_7_8
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
