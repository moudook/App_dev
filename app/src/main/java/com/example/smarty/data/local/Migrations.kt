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
     * Migration from version 5 to 6
     * Originally added calendar_events table (later removed in 6→7, re-added in 10→11)
     * This migration ensures a safe upgrade path for users on version 5
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create calendar_events table (will be dropped in next migration)
            // This ensures users don't lose data due to missing migration
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS calendar_events (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    startTime INTEGER NOT NULL,
                    endTime INTEGER NOT NULL,
                    isAllDay INTEGER NOT NULL DEFAULT 0,
                    color INTEGER,
                    location TEXT,
                    reminderMinutes INTEGER,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)
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

    /**
     * Migration from version 8 to 9
     * Adds AI memory and impressed log tables for persistent AI learning
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create ai_memories table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS ai_memories (
                    id TEXT PRIMARY KEY NOT NULL,
                    type TEXT NOT NULL,
                    content TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    source TEXT,
                    createdAt INTEGER NOT NULL,
                    lastUsedAt INTEGER NOT NULL,
                    usageCount INTEGER NOT NULL DEFAULT 1
                )
            """)

            // Create indices for ai_memories
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_memories_type ON ai_memories(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_memories_lastUsedAt ON ai_memories(lastUsedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_memories_confidence ON ai_memories(confidence)")

            // Create impressed_log table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS impressed_log (
                    id TEXT PRIMARY KEY NOT NULL,
                    actionType TEXT NOT NULL,
                    abstractContext TEXT NOT NULL,
                    userSignal TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
            """)

            // Create indices for impressed_log
            db.execSQL("CREATE INDEX IF NOT EXISTS index_impressed_log_actionType ON impressed_log(actionType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_impressed_log_timestamp ON impressed_log(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_impressed_log_userSignal ON impressed_log(userSignal)")
        }
    }

    /**
     * Migration from version 9 to 10
     * Adds summary fields to chat_sessions for conversation summarization
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add summary column to chat_sessions
            db.execSQL("ALTER TABLE chat_sessions ADD COLUMN summary TEXT DEFAULT NULL")

            // Add summaryGeneratedAt column to chat_sessions
            db.execSQL("ALTER TABLE chat_sessions ADD COLUMN summaryGeneratedAt INTEGER DEFAULT NULL")
        }
    }

    /**
     * Migration from version 10 to 11
     * Adds calendar_events table for scheduling and reminders
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create calendar_events table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS calendar_events (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    startTime INTEGER NOT NULL,
                    endTime INTEGER NOT NULL,
                    isAllDay INTEGER NOT NULL DEFAULT 0,
                    color INTEGER,
                    location TEXT,
                    reminderMinutes INTEGER,
                    isRecurring INTEGER NOT NULL DEFAULT 0,
                    recurrenceRule TEXT,
                    linkedNoteId TEXT,
                    isEventPrivate INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)

            // Create index for faster date range queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_startTime ON calendar_events(startTime)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_linkedNoteId ON calendar_events(linkedNoteId)")
        }
    }

    /**
     * Migration from version 11 to 12
     * Adds agent_executions and provider_usage tables for AI agent tracking
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create agent_executions table for tracking AI agent runs
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS agent_executions (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    modelId TEXT NOT NULL,
                    userPrompt TEXT NOT NULL,
                    response TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'SUCCESS',
                    errorMessage TEXT,
                    inputTokens INTEGER NOT NULL DEFAULT 0,
                    outputTokens INTEGER NOT NULL DEFAULT 0,
                    totalTokens INTEGER NOT NULL DEFAULT 0,
                    toolCallCount INTEGER NOT NULL DEFAULT 0,
                    toolsCalled TEXT NOT NULL DEFAULT '[]',
                    iterations INTEGER NOT NULL DEFAULT 1,
                    latencyMs INTEGER NOT NULL DEFAULT 0,
                    executedAt INTEGER NOT NULL,
                    keyIndex INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (sessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
            """)

            // Create indices for agent_executions
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_executions_sessionId ON agent_executions(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_executions_provider ON agent_executions(provider)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_executions_modelId ON agent_executions(modelId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_executions_executedAt ON agent_executions(executedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_executions_status ON agent_executions(status)")

            // Create provider_usage table for rate limiting and usage tracking
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS provider_usage (
                    date INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    modelId TEXT NOT NULL,
                    callCount INTEGER NOT NULL DEFAULT 0,
                    inputTokens INTEGER NOT NULL DEFAULT 0,
                    outputTokens INTEGER NOT NULL DEFAULT 0,
                    totalTokens INTEGER NOT NULL DEFAULT 0,
                    successCount INTEGER NOT NULL DEFAULT 0,
                    failureCount INTEGER NOT NULL DEFAULT 0,
                    rateLimitHits INTEGER NOT NULL DEFAULT 0,
                    toolCalls INTEGER NOT NULL DEFAULT 0,
                    avgLatencyMs INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY (date, provider, modelId)
                )
            """)

            // Create indices for provider_usage
            db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_usage_date ON provider_usage(date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_usage_provider ON provider_usage(provider)")
        }
    }

    /**
     * Migration from version 12 to 13
     * Adds tagsJson field to notes table for AI-generated tags
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add tagsJson column to notes table
            db.execSQL("ALTER TABLE notes ADD COLUMN tagsJson TEXT DEFAULT NULL")
        }
    }
}
