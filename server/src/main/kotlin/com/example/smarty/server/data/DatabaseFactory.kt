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

                        // v6: chat_folders table
                        """CREATE TABLE IF NOT EXISTS chat_folders (
                            id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name       TEXT NOT NULL,
                            color      TEXT DEFAULT '#6200EE',
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: notes table - matches DATABASE_SCHEMA_v6.0.0_UNIFIED_PRODUCTION.sql
                        """CREATE TABLE IF NOT EXISTS notes (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            category_id     UUID REFERENCES note_categories(id) ON DELETE SET NULL,
                            stack_id        UUID REFERENCES note_stacks(id) ON DELETE SET NULL,
                            parent_note_id  UUID REFERENCES notes(id) ON DELETE SET NULL,
                            title           TEXT NOT NULL DEFAULT '',
                            content         TEXT NOT NULL DEFAULT '',
                            is_archived     BOOLEAN NOT NULL DEFAULT false,
                            is_pinned       BOOLEAN NOT NULL DEFAULT false,
                            is_favorite     BOOLEAN NOT NULL DEFAULT false,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at      TIMESTAMPTZ
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

                        // v6: note_categories table
                        """CREATE TABLE IF NOT EXISTS note_categories (
                            id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name        TEXT NOT NULL,
                            color       TEXT DEFAULT '#6200EE',
                            icon        TEXT DEFAULT 'folder',
                            parent_id   UUID REFERENCES note_categories(id) ON DELETE SET NULL,
                            sort_order  INTEGER NOT NULL DEFAULT 0,
                            note_count  INTEGER NOT NULL DEFAULT 0,
                            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: note_stacks table
                        """CREATE TABLE IF NOT EXISTS note_stacks (
                            id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name        TEXT NOT NULL,
                            description TEXT,
                            color       TEXT DEFAULT '#03DAC6',
                            icon        TEXT DEFAULT 'stack',
                            parent_id   UUID REFERENCES note_stacks(id) ON DELETE SET NULL,
                            note_count  INTEGER NOT NULL DEFAULT 0,
                            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: chat_sessions (full v6.0.0 schema)
                        """CREATE TABLE IF NOT EXISTS chat_sessions (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            folder_id       UUID REFERENCES chat_folders(id) ON DELETE SET NULL,
                            title           TEXT,
                            is_active       BOOLEAN NOT NULL DEFAULT true,
                            is_archived     BOOLEAN NOT NULL DEFAULT false,
                            is_pinned       BOOLEAN NOT NULL DEFAULT false,
                            model_used      TEXT,
                            temperature     NUMERIC(3,2) DEFAULT 0.7 CHECK (temperature BETWEEN 0 AND 2),
                            max_tokens      INTEGER DEFAULT 4096 CHECK (max_tokens > 0),
                            system_prompt   TEXT,
                            token_count     INTEGER NOT NULL DEFAULT 0,
                            message_count   INTEGER NOT NULL DEFAULT 0,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            expires_at      TIMESTAMPTZ
                        )""",

                        // v6: chat_messages (full v6.0.0 schema)
                        """CREATE TABLE IF NOT EXISTS chat_messages (
                            id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id        UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
                            user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            parent_message_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,
                            role              TEXT NOT NULL CHECK (role IN ('user','assistant','system','tool')),
                            content           TEXT NOT NULL,
                            content_hash      TEXT,
                            thinking          TEXT,
                            tool_calls        JSONB,
                            tool_call_id      TEXT,
                            token_count       INTEGER DEFAULT 0,
                            is_edited         BOOLEAN NOT NULL DEFAULT false,
                            is_starred        BOOLEAN NOT NULL DEFAULT false,
                            metadata          JSONB NOT NULL DEFAULT '{}',
                            created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // Chat message to notes junction table
                        """CREATE TABLE IF NOT EXISTS chat_message_notes (
                            message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
                            note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                            PRIMARY KEY (message_id, note_id)
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
