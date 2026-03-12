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
                    // v4.2.0 MIGRATION: Minimal migrations for server startup
                    // Full schema applied via DATABASE_SCHEMA_v4.2.0_OPTIMIZED.sql
                    
                    val migrations = listOf(
                        // Verify users table exists (critical for v4.2.0 FKs)
                        """CREATE TABLE IF NOT EXISTS users (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            firebase_uid TEXT UNIQUE NOT NULL,
                            email TEXT,
                            display_name TEXT,
                            avatar_url TEXT,
                            is_active BOOLEAN DEFAULT true,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                            updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                        )""",
                        
                        // Verify junction tables exist (v4.2.0)
                        """CREATE TABLE IF NOT EXISTS chat_message_notes (
                            message_id UUID NOT NULL,
                            note_id UUID NOT NULL,
                            PRIMARY KEY (message_id, note_id)
                        )""",
                        
                        """CREATE TABLE IF NOT EXISTS calendar_event_notes (
                            event_id UUID NOT NULL,
                            note_id UUID NOT NULL,
                            PRIMARY KEY (event_id, note_id)
                        )""",
                        
                        // Create indexes for junction tables
                        "CREATE INDEX IF NOT EXISTS idx_chat_message_notes_message ON chat_message_notes(message_id)",
                        "CREATE INDEX IF NOT EXISTS idx_chat_message_notes_note ON chat_message_notes(note_id)",
                        "CREATE INDEX IF NOT EXISTS idx_calendar_event_notes_event ON calendar_event_notes(event_id)",
                        "CREATE INDEX IF NOT EXISTS idx_calendar_event_notes_note ON calendar_event_notes(note_id)"
                    )
                    
                    for (sql in migrations) {
                        try {
                            stmt.execute(sql)
                        } catch (e: Exception) {
                            logger.warn("Migration statement skipped (may already exist): ${e.message}")
                        }
                    }
                }
            }
            logger.info("Database migrations applied successfully (v4.2.0 minimal)")
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
