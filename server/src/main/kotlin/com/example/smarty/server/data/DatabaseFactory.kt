package com.example.smarty.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database

/**
 * Singleton factory for managing the database connection pool.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null
    private var database: Database? = null

    fun init() {
        val ds = getDataSource()
        if (ds != null) {
            database = org.jetbrains.exposed.sql.Database.connect(ds)
            runMigrations(ds)
        }
    }

    fun getDatabase(): org.jetbrains.exposed.sql.Database? = database

    /**
     * Apply schema migrations to ensure all required columns exist.
     */
    private fun runMigrations(ds: DataSource) {
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // Add user_id column to tables that might not have it yet
                    val migrations = listOf(
                        // User ID columns
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE agent_context ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        
                        // New chat_sessions columns for client compatibility
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()",
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS message_count INTEGER DEFAULT 0",
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS last_message_preview TEXT DEFAULT ''",
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT true",
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS summary TEXT",
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS summary_generated_at BIGINT",
                        
                        // New chat_messages columns for client compatibility
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS attachments_json TEXT DEFAULT '[]'",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS executed_actions_json TEXT DEFAULT '[]'",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS referenced_note_ids TEXT DEFAULT ''",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS citations_json TEXT DEFAULT '[]'",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS inline_images_json TEXT DEFAULT '[]'",
                        
                        // Create indexes
                        "CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_sessions_active ON chat_sessions(is_active)",
                        "CREATE INDEX IF NOT EXISTS idx_sessions_updated ON chat_sessions(updated_at DESC)",

                        // Notes table (server-side source of truth)
                        """CREATE TABLE IF NOT EXISTS notes (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            category TEXT,
                            is_archived BOOLEAN DEFAULT FALSE,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id)",

                        // Timers/Alarms table (server-side source of truth)
                        """CREATE TABLE IF NOT EXISTS timers (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id TEXT NOT NULL,
                            name TEXT NOT NULL,
                            duration_ms BIGINT NOT NULL DEFAULT 0,
                            trigger_at TIMESTAMP WITH TIME ZONE,
                            is_alarm BOOLEAN DEFAULT FALSE,
                            is_active BOOLEAN DEFAULT TRUE,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id)",

                        // Calendar Events table (server-side source of truth)
                        """CREATE TABLE IF NOT EXISTS calendar_events (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            start_time BIGINT NOT NULL,
                            end_time BIGINT NOT NULL,
                            description TEXT,
                            reminder_minutes INT DEFAULT 15,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_calendar_user ON calendar_events(user_id)",

                        // Ultra-Secure Zero-Knowledge Vault (encrypted blobs only)
                        """CREATE TABLE IF NOT EXISTS user_vaults (
                            user_id VARCHAR(128) PRIMARY KEY,
                            encrypted_blob TEXT NOT NULL,
                            version INT DEFAULT 1,
                            updated_at BIGINT NOT NULL
                        )""",

                        // Agent Traces Table (for debugging/observability) - matches PostgresTracer.kt
                        """CREATE TABLE IF NOT EXISTS agent_traces (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id UUID,
                            user_id TEXT NOT NULL,
                            step_type TEXT NOT NULL,
                            content TEXT,
                            metadata JSONB,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_traces_session ON agent_traces(session_id)",
                        "CREATE INDEX IF NOT EXISTS idx_traces_user ON agent_traces(user_id)",

                        // Agent Checkpoints Table (for resumable agents) - matches AgentPersistenceManager.kt
                        """CREATE TABLE IF NOT EXISTS agent_checkpoints (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id UUID NOT NULL,
                            user_id TEXT NOT NULL,
                            state_json JSONB NOT NULL,
                            last_node TEXT,
                            version INTEGER DEFAULT 1,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_checkpoints_session ON agent_checkpoints(session_id)",
                        "CREATE INDEX IF NOT EXISTS idx_checkpoints_user ON agent_checkpoints(user_id)",

                        // Daily Digests Table
                        """CREATE TABLE IF NOT EXISTS daily_digests (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id TEXT NOT NULL,
                            digest_date DATE NOT NULL,
                            digest_type TEXT NOT NULL DEFAULT 'daily',
                            summary TEXT NOT NULL,
                            key_insights JSONB,
                            action_items JSONB,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            UNIQUE(user_id, digest_date, digest_type)
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_digests_user ON daily_digests(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_digests_date ON daily_digests(digest_date)",

                        // Digest Preferences Table
                        """CREATE TABLE IF NOT EXISTS digest_preferences (
                            user_id TEXT PRIMARY KEY,
                            daily_enabled BOOLEAN DEFAULT TRUE,
                            daily_time TIME DEFAULT '07:00:00',
                            weekly_enabled BOOLEAN DEFAULT TRUE,
                            weekly_day INT DEFAULT 0,
                            weekly_time TIME DEFAULT '08:00:00',
                            push_notification BOOLEAN DEFAULT TRUE,
                            calendar_logging BOOLEAN DEFAULT TRUE,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",

                        // FCM Tokens Table
                        """CREATE TABLE IF NOT EXISTS user_fcm_tokens (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id TEXT NOT NULL,
                            token TEXT NOT NULL UNIQUE,
                            device_name TEXT,
                            device_id TEXT,
                            last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON user_fcm_tokens(user_id)"
                    )
                    for (sql in migrations) {
                        try {
                            stmt.execute(sql)
                        } catch (e: Exception) {
                            logger.warn("Migration statement failed (may already exist): ${e.message}")
                        }
                    }
                }
            }
            logger.info("Database migrations applied successfully")
        } catch (e: Exception) {
            logger.error("Failed to run database migrations", e)
        }
    }

    @Synchronized
    fun getDataSource(): DataSource? {
        if (dataSource == null) {
            val dbUrl = System.getenv("DB_URL")
            val dbUser = System.getenv("DB_USER")
            val dbPassword = System.getenv("DB_PASSWORD")

            if (dbUrl.isNullOrBlank()) {
                logger.warn("DB_URL environment variable not set. Database operations disabled.")
                return null
            }

            val config = HikariConfig().apply {
                jdbcUrl = dbUrl
                username = dbUser
                password = dbPassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 4 // Keep low for Supabase free tier limits
                minimumIdle = 1
                idleTimeout = 30000
                connectionTimeout = 10000
                leakDetectionThreshold = 30000 // Increased from 2s to 30s for migrations
            }

            dataSource = try {
                HikariDataSource(config)
            } catch (e: Exception) {
                logger.error("Failed to initialize DataSource", e)
                null
            }
        }
        return dataSource
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }
}
