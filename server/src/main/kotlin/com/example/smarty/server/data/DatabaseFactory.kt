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
                logger.info("Starting database migrations...")
                val startTime = System.currentTimeMillis()
                var migrationsApplied = 0
                
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

                        // v6: tasks table
                        """CREATE TABLE IF NOT EXISTS tasks (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            session_id      UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
                            note_id         UUID REFERENCES notes(id) ON DELETE SET NULL,
                            title           TEXT NOT NULL,
                            description     TEXT,
                            status          TEXT NOT NULL DEFAULT 'todo',
                            priority        INTEGER NOT NULL DEFAULT 2,
                            due_date        TIMESTAMPTZ,
                            completed_at    TIMESTAMPTZ,
                            sort_order      INTEGER NOT NULL DEFAULT 0,
                            is_recurring    BOOLEAN NOT NULL DEFAULT false,
                            recurrence_rule TEXT,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at      TIMESTAMPTZ
                        )""",

                        // v6: tags table
                        """CREATE TABLE IF NOT EXISTS tags (
                            id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name       TEXT NOT NULL,
                            color      TEXT DEFAULT '#6200EE',
                            usage_count INTEGER NOT NULL DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            UNIQUE(user_id, lower(name))
                        )""",

                        // v6: note_tags junction table
                        """CREATE TABLE IF NOT EXISTS note_tags (
                            note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                            tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                            PRIMARY KEY (note_id, tag_id)
                        )""",

                        // v6: notifications table
                        """CREATE TABLE IF NOT EXISTS notifications (
                            id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            type       TEXT NOT NULL,
                            title      TEXT NOT NULL,
                            body       TEXT,
                            data       JSONB NOT NULL DEFAULT '{}',
                            is_read    BOOLEAN NOT NULL DEFAULT false,
                            read_at    TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: digest_preferences table
                        """CREATE TABLE IF NOT EXISTS digest_preferences (
                            id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            enabled        BOOLEAN NOT NULL DEFAULT true,
                            frequency      TEXT NOT NULL DEFAULT 'daily',
                            delivery_hour  INTEGER NOT NULL DEFAULT 9,
                            delivery_minute INTEGER NOT NULL DEFAULT 0,
                            created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            UNIQUE(user_id)
                        )""",

                        // v6: sync_state table
                        """CREATE TABLE IF NOT EXISTS sync_state (
                            user_id      UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                            last_sync_at TIMESTAMPTZ,
                            last_pull_at TIMESTAMPTZ,
                            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
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
                            word_count      INTEGER GENERATED ALWAYS AS (char_length(content)) STORED,
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

                        // Timers table
                        """CREATE TABLE IF NOT EXISTS timers (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name TEXT NOT NULL,
                            duration_ms BIGINT NOT NULL,
                            trigger_at TIMESTAMPTZ NOT NULL,
                            is_alarm BOOLEAN NOT NULL DEFAULT false,
                            is_active BOOLEAN NOT NULL DEFAULT true,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // User FCM tokens table
                        """CREATE TABLE IF NOT EXISTS user_fcm_tokens (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            token TEXT NOT NULL,
                            device_name TEXT,
                            device_id TEXT,
                            last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // Daily digests table
                        """CREATE TABLE IF NOT EXISTS daily_digests (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            digest_date DATE NOT NULL,
                            digest_type TEXT NOT NULL,
                            content JSONB NOT NULL DEFAULT '{}',
                            notification_sent BOOLEAN NOT NULL DEFAULT false,
                            calendar_event_id UUID,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: calendar_events table
                        """CREATE TABLE IF NOT EXISTS calendar_events (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            title           TEXT NOT NULL,
                            description     TEXT,
                            start_time      TIMESTAMPTZ NOT NULL,
                            end_time        TIMESTAMPTZ,
                            is_all_day      BOOLEAN NOT NULL DEFAULT false,
                            status          TEXT NOT NULL DEFAULT 'confirmed',
                            visibility      TEXT NOT NULL DEFAULT 'private',
                            reminders       JSONB NOT NULL DEFAULT '[]',
                            attendees       JSONB NOT NULL DEFAULT '[]',
                            location        TEXT,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // v6: calendar_event_notes junction table
                        """CREATE TABLE IF NOT EXISTS calendar_event_notes (
                            event_id UUID NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
                            note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                            PRIMARY KEY (event_id, note_id)
                        )""",

                        // v6: reasoning_traces table
                        """CREATE TABLE IF NOT EXISTS reasoning_traces (
                            trace_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id      UUID NOT NULL,
                            user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            step_index      INTEGER NOT NULL,
                            step_type       TEXT,
                            title           TEXT,
                            content         TEXT,
                            input_data      JSONB,
                            output_data     JSONB,
                            error_message   TEXT,
                            duration_ms     BIGINT,
                            token_usage     JSONB,
                            metadata        JSONB NOT NULL DEFAULT '{}',
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // Agent checkpoints table
                        """CREATE TABLE IF NOT EXISTS agent_checkpoints (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            session_id UUID NOT NULL,
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            workflow_id UUID,
                            state_json JSONB NOT NULL,
                            version INTEGER NOT NULL DEFAULT 1,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",

                        // Generated images table (for Krea AI image generation)
                        """CREATE TABLE IF NOT EXISTS generated_images (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            session_id UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
                            prompt TEXT NOT NULL,
                            krea_job_id TEXT NOT NULL UNIQUE,
                            status TEXT NOT NULL DEFAULT 'queued',
                            image_url TEXT,
                            supabase_url TEXT,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_generated_images_user ON generated_images(user_id, created_at DESC)",
                        "CREATE INDEX IF NOT EXISTS idx_generated_images_session ON generated_images(session_id)",
                        "CREATE INDEX IF NOT EXISTS idx_generated_images_krea_job ON generated_images(krea_job_id)",

                        // Indexes for performance
                        "CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id) WHERE deleted_at IS NULL",
                        "CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id, created_at ASC)",
                        "CREATE INDEX IF NOT EXISTS idx_agent_traces_user ON agent_traces(user_id, created_at DESC)",
                        // Full-text search indexes for notes
                        "CREATE INDEX IF NOT EXISTS idx_notes_fts ON notes USING gin (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(content,'')))",
                        // Optimized indexes for common queries
                        "CREATE INDEX IF NOT EXISTS idx_notes_user_active ON notes(user_id, updated_at DESC) WHERE is_archived = false AND deleted_at IS NULL",
                        "CREATE INDEX IF NOT EXISTS idx_notes_user_pinned ON notes(user_id, updated_at DESC) WHERE is_pinned = true AND deleted_at IS NULL",
                        // Chat sessions indexes
                        "CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active ON chat_sessions(user_id, updated_at DESC) WHERE is_active = true AND is_archived = false",
                        "CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_pinned ON chat_sessions(user_id) WHERE is_pinned = true"
                    )

                    for ((index, sql) in migrations.withIndex()) {
                        try {
                            stmt.execute(sql)
                            migrationsApplied++
                        } catch (e: Exception) {
                            logger.warn("Migration statement skipped (may already exist): ${e.message?.take(120)}")
                        }
                    }
                    
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("Database migrations applied successfully (v6.0.0 minimal) - $migrationsApplied tables, ${duration}ms")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to run database migrations", e)
            throw e
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
                maximumPoolSize = 20  // Increased for better concurrency
                minimumIdle = 5  // Warm pool of 5 connections for local PostgreSQL
                idleTimeout = 300000  // 5 minutes (standard for local DB, not PgBouncer)
                connectionTimeout = 30000  // 30 seconds
                maxLifetime = 1800000  // 30 minutes (PostgreSQL default, not PgBouncer)
                keepaliveTime = 0  // Disabled for local PostgreSQL connections
                connectionTestQuery = "SELECT 1"
                leakDetectionThreshold = 120000  // 2 minutes
                addDataSourceProperty("prepareThreshold", "0")
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
                addDataSourceProperty("tcpKeepAlive", "true")
                addDataSourceProperty("socketTimeout", "60")
            }

            dataSource = try {
                val ds = HikariDataSource(config)
                
                // Test connection immediately to verify database is accessible
                ds.connection.use { conn ->
                    conn.createStatement().executeQuery("SELECT 1").close()
                    logger.info("Database connection test successful - PostgreSQL is accessible")
                }
                
                logger.info("Database connection established successfully (pool size: ${ds.maximumPoolSize}, min idle: ${ds.minimumIdle})")
                ds
            } catch (e: Exception) {
                logger.error("Failed to initialize DataSource: ${e.message}")
                logger.error("Check that PostgreSQL is running and DB_URL/DB_USER/DB_PASSWORD are correct")
                logger.error("If using Docker: docker ps (check container), docker logs <container_id>")
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
