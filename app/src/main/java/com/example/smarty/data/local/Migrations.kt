package com.example.smarty.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for Cogni app
 */
object Migrations {
    /**
     * Migration from version 3 to 4
     * Adds excludeFromAiChat column for per-note AI chat exclusion
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN excludeFromAiChat INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 4 to 5
     * Adds isFullPrivacy column for full privacy mode (no AI processing at all)
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN isFullPrivacy INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 6 to 7
     * Removes calendar_events table (calendar feature removed)
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS calendar_events")
        }
    }

    /**
     * Migration from version 7 to 8
     * Adds chat_sessions and chat_messages tables for chat history persistence
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create chat_sessions table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    messageCount INTEGER NOT NULL,
                    lastMessagePreview TEXT NOT NULL,
                    isActive INTEGER NOT NULL
                )
            """)

            // Create chat_messages table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    attachmentsJson TEXT NOT NULL,
                    executedActionsJson TEXT NOT NULL,
                    referencedNoteIds TEXT NOT NULL
                )
            """)

            // Create index for faster message queries by session
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
        }
    }
}
