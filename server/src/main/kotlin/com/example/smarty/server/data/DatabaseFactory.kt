package com.example.smarty.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Singleton factory for managing the database connection pool.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null

    fun init() {
        val ds = getDataSource()
        if (ds != null) {
            runMigrations(ds)
        }
    }

    /**
     * Apply schema migrations to ensure all required columns exist.
     */
    private fun runMigrations(ds: DataSource) {
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // Add user_id column to tables that might not have it yet
                    val migrations = listOf(
                        "ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE agent_context ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''",
                        "CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id)",
                        "CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id)",

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
                        )"""
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
                leakDetectionThreshold = 2000
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
