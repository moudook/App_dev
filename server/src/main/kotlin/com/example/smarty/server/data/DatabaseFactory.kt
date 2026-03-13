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
     * Updated for v4.2.0: Junction tables, foreign keys, SDE principles.
     * 
     * NOTE: Full schema should be applied via DATABASE_SCHEMA_v4.2.0_OPTIMIZED.sql
     * This migration ensures critical tables exist for server startup.
     */
private fun runMigrations(ds: DataSource) {
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // ── v6.0.0 compatibility minimal migrations ──
                    // Full schema lives in DATABASE_SCHEMA_v6.0.0_UNIFIED_PRODUCTION.sql
                    // These are only safety nets in case the full schema hasn't been applied yet.

                    val migrations = listOf(
                        // Verify users table exists (critical for v6 FKs)
                        """CREATE TABLE IF NOT EXISTS users (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            firebase_uid TEXT UNIQUE NOT NULL,
                            email TEXT,
                            display_name TEXT,
                            avatar_url TEXT,
                            is_active BOOLEAN DEFAULT true,
                            is_premium BOOLEAN DEFAULT false,
                            subscription_expires_at TIMESTAMP WITH TIME ZONE,
                            feature_flags JSONB DEFAULT '{}',
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            last_login_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",

                        // v6: notes table replaces agent_context for user memory / RAG
                        """CREATE TABLE IF NOT EXISTS notes (
                            id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            title       TEXT NOT NULL DEFAULT '',
                            content     TEXT,
                            category    TEXT DEFAULT 'general',
                            is_pinned   BOOLEAN NOT NULL DEFAULT false,
                            metadata    JSONB NOT NULL DEFAULT '{}',
                            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at  TIMESTAMPTZ
                        )""",

                        // v6: agent_traces requires step_name NOT NULL
                        """CREATE TABLE IF NOT EXISTS agent_traces (
                            id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            workflow_id   UUID,
                            session_id    UUID,
                            user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            step_name     TEXT NOT NULL,
                            step_type     TEXT,
                            content       TEXT,
                            input_data    JSONB,
                            output_data   JSONB,
                            error_message TEXT,
                            duration_ms   BIGINT,
                            token_usage   JSONB,
                            metadata      JSONB NOT NULL DEFAULT '{}',
                            created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: chat_sessions (minimal, without FKs that may not exist yet)
                        """CREATE TABLE IF NOT EXISTS chat_sessions (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            title           TEXT,
                            is_active       BOOLEAN NOT NULL DEFAULT true,
                            is_archived     BOOLEAN NOT NULL DEFAULT false,
                            model_used      TEXT,
                            token_count     INTEGER NOT NULL DEFAULT 0,
                            message_count   INTEGER NOT NULL DEFAULT 0,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: chat_messages
                        """CREATE TABLE IF NOT EXISTS chat_messages (
                            id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
                            user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role        TEXT NOT NULL CHECK (role IN ('user','assistant','system','tool')),
                            content     TEXT NOT NULL,
                            thinking    TEXT,
                            tool_calls  JSONB,
                            metadata    JSONB NOT NULL DEFAULT '{}',
                            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // Indexes for performance
                        "CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id) WHERE deleted_at IS NULL",
                        "CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id, created_at ASC)",
                        "CREATE INDEX IF NOT EXISTS idx_agent_traces_user ON agent_traces(user_id, created_at DESC)"
                    )

                    for (sql in migrations) {
                        try {
                            stmt.execute(sql)
                        } catch (e: Exception) {
                            logger.warn("Migration statement skipped (may already exist): ${e.message?.take(120)}")
                        }
                    }
                }
            }
            logger.info("Database migrations applied successfully (v6.0.0 minimal)")
        } catch (e: Exception) {
            logger.error("Failed to run database migrations", e)
        }
    }

    @Synchronized
    fun getDataSource(): DataSource? {
        if (dataSource == null) {
            var dbUrl = System.getenv("DB_URL")
            val dbUser = System.getenv("DB_USER")
            val dbPassword = System.getenv("DB_PASSWORD")

            if (dbUrl.isNullOrBlank()) {
                logger.warn("DB_URL environment variable not set. Database operations disabled.")
                logger.warn("Server will start but database-dependent features won't work.")
                logger.warn("Set DB_URL, DB_USER, DB_PASSWORD environment variables to enable database.")
                return null
            }

            // Convert postgresql:// to jdbc:postgresql:// if needed
            if (dbUrl.startsWith("postgresql://") && !dbUrl.startsWith("jdbc:")) {
                dbUrl = "jdbc:$dbUrl"
                logger.info("Converted DB_URL to JDBC format")
            }

            logger.info("Connecting to database: ${dbUrl.take(50)}...")

            val config = HikariConfig().apply {
                jdbcUrl = dbUrl
                username = dbUser
                password = dbPassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 4
                minimumIdle = 1
                idleTimeout = 120000
                connectionTimeout = 10000  // Reduced from 30s to 10s for faster startup
                maxLifetime = 600000
                leakDetectionThreshold = 60000
                addDataSourceProperty("prepareThreshold", "0")
            }

            dataSource = try {
                val ds = HikariDataSource(config)
                logger.info("Database connection established successfully")
                ds
            } catch (e: Exception) {
                logger.error("Failed to initialize DataSource: ${e.message}")
                logger.error("Server will continue without database support")
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
