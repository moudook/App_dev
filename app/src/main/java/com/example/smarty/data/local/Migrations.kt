package com.example.smarty.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for Jarvis app
 */
object Migrations {
    /**
     * Migration from version 1 to 2
     * No schema changes in this version - compatibility migration
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes in this version
        }
    }

    /**
     * Migration from version 2 to 3
     * No schema changes in this version - compatibility migration
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes in this version
        }
    }

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

    /**
     * Migration from version 13 to 14
     * Adds isViewed column for read/unread state
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add isViewed column to notes table
            // Default to 1 (true) for existing notes so they don't look "New"
            db.execSQL("ALTER TABLE notes ADD COLUMN isViewed INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * Migration from version 14 to 15
     * Adds isPinned column for note pinning feature
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration from version 15 to 16
     * Adds reminderText and reminderExpiresAt columns for smart reminders
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN reminderText TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE notes ADD COLUMN reminderExpiresAt INTEGER DEFAULT NULL")
        }
    }

    /**
     * Migration from version 16 to 17
     * Creates note_versions table for git-like versioning
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS note_versions (
                    id TEXT PRIMARY KEY NOT NULL,
                    noteId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    summary TEXT,
                    versionNumber INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    changeDescription TEXT,
                    FOREIGN KEY (noteId) REFERENCES notes(id) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_note_versions_noteId ON note_versions(noteId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_note_versions_createdAt ON note_versions(createdAt)")
        }
    }

    /**
     * Migration from version 17 to 18
     * Creates FTS5 virtual table for full-text search
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create FTS5 virtual table for fast text search
            // Using external content mode pointing to notes table
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
                INSERT INTO notes_fts(rowid, title, content, summary)
                SELECT rowid, COALESCE(title, ''), COALESCE(content, ''), COALESCE(summary, '') FROM notes
            """)

            // Create triggers to keep FTS table in sync with notes table
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
        }
    }

    /**
     * Migration from version 18 to 19
     * PERFORMANCE: Adds indices for isPinned column to optimize note list queries
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create index on isPinned for single-column queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_isPinned ON notes(isPinned)")
            // Create composite index for the common query pattern: ORDER BY isPinned DESC, createdAt DESC
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_isPinned_createdAt ON notes(isPinned, createdAt)")
        }
    }

    /**
     * Migration from version 19 to 20
     * FEATURE: Add citationsJson column to chat_messages for storing web search citations
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add citationsJson column with default empty array
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN citationsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * Migration from version 20 to 21
     * PERFORMANCE: Adds composite indices for common query patterns
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index for getNotesByCategory queries (60-80% faster)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notes_categoryId_createdAt " +
                "ON notes(categoryId, createdAt DESC)"
            )

            // Index for getActiveNotes queries (common query pattern)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notes_isArchived_createdAt " +
                "ON notes(isArchived, createdAt DESC)"
            )

            // Index for type-filtered queries
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notes_type_createdAt " +
                "ON notes(type, createdAt DESC)"
            )
        }
    }

    /**
     * Migration 21 → 22: Add processingStatus indices for queue performance.
     * Sprint 3 optimization - improves queue processing queries by 60-80%.
     *
     * Queries affected:
     * - getNotesByProcessingStatus()
     * - getStuckProcessingNotes()
     * - getPendingProcessingCount()
     * - getNextPendingNote()
     * - resetStuckNotes()
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Single column index for processingStatus equality checks
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notes_processingStatus " +
                "ON notes(processingStatus)"
            )

            // Composite index for stuck note detection with timeout
            // Covers: WHERE processingStatus = 'PROCESSING' AND updatedAt < :threshold
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notes_processingStatus_updatedAt " +
                "ON notes(processingStatus, updatedAt)"
            )
        }
    }

    /**
     * Migration 22 → 23: Add chunkAnalysesJson column for per-page document analyses.
     * Allows users to toggle between final summary and per-page analyses in KnowledgeCardScreen.
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add chunkAnalysesJson column with nullable TEXT (stores JSON of List<ChunkAnalysis>)
            db.execSQL("ALTER TABLE notes ADD COLUMN chunkAnalysesJson TEXT")
        }
    }

    /**
     * Migration 23 → 24: Add inlineImagesJson column to chat_messages.
     * Stores inline images from ViewImageTool to display images in chat history.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add inlineImagesJson column with default empty array
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN inlineImagesJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * Migration 24 → 25: Add isReadForMemory column and index.
     * Tracks which notes have been analyzed for AI memory learning.
     * Prevents re-processing notes and saves resources.
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add isReadForMemory column - default to 0 (false) so existing notes get analyzed
            db.execSQL("ALTER TABLE notes ADD COLUMN isReadForMemory INTEGER NOT NULL DEFAULT 0")
            
            // Create index for efficient memory learning queries
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_isReadForMemory ON notes(isReadForMemory)")
        }
    }

    /**
     * Migration 25 → 26: Schema hash update for @ColumnInfo(defaultValue).
     * No actual schema change - the isReadForMemory column already exists with DEFAULT 0.
     * This migration exists because adding @ColumnInfo(defaultValue = "0") annotation
     * changes Room's identity hash, requiring a version bump.
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes needed - column already has correct DEFAULT from migration 24→25
            // This migration exists only to satisfy Room's schema hash verification
        }
    }
}
