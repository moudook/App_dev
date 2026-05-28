package com.example.smarty.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.sql.Connection
import javax.sql.DataSource

/**
 * Singleton factory for managing the database connection pool.
 * REFACTOR: Migrations have been moved to proper SQL files in resources/db/migrations/
 * This file now only handles connection pooling and initialization.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private var dataSource: HikariDataSource? = null
    private var database: Database? = null
    private var connectionFailed = false
    private var connectionFailedAt = 0L
    private const val CONNECTION_RETRY_INTERVAL_MS = 60_000L // Retry after 60s

    fun init() {
        val ds = getDataSource()
        if (ds != null) {
            database = Database.connect(ds)
            runMigrations(ds)
        }
    }

    fun getDatabase(): Database? = database

    /**
     * Run database migrations from SQL files
     */
    private fun runMigrations(ds: DataSource) {
        try {
            ds.connection.use { conn ->
                logger.info("Starting database migrations...")
                val startTime = System.currentTimeMillis()

                // Load and run initial schema
                val migrationStream = javaClass.classLoader.getResourceAsStream("db/migrations/V1__Initial_schema.sql")

                if (migrationStream != null) {
                    val migrationSql = BufferedReader(InputStreamReader(migrationStream)).use { it.readText() }

                    conn.createStatement().use { stmt ->
                        val statements = mutableListOf<String>()
                        var inDollarBlock = false
                        val currentStmt = StringBuilder()

                        for (line in migrationSql.lines()) {
                            // Track whether we are inside a $$ ... $$ block
                            if (line.contains("$$")) {
                                var idx = 0
                                while (true) {
                                    idx = line.indexOf("$$", idx)
                                    if (idx == -1) break
                                    inDollarBlock = !inDollarBlock
                                    idx += 2
                                }
                            }

                            currentStmt.append(line).append("\n")

                            // Split statement only if we are outside a $$ block and line ends with semicolon
                            if (!inDollarBlock && line.trimEnd().endsWith(";")) {
                                val sql = currentStmt.toString().trim()
                                if (sql.isNotEmpty() && !sql.startsWith("--")) {
                                    statements.add(sql)
                                }
                                currentStmt.clear()
                            }
                        }

                        // Add any remaining statement
                        val remaining = currentStmt.toString().trim()
                        if (remaining.isNotEmpty() && !remaining.startsWith("--")) {
                            statements.add(remaining)
                        }

                        var applied = 0
                        var skipped = 0
                        var failed = 0
                        statements.forEach { sql ->
                            try {
                                stmt.execute(sql)
                                applied++
                            } catch (e: org.postgresql.util.PSQLException) {
                                // "already exists" errors are safe to skip (idempotent migrations)
                                val msg = e.message ?: ""
                                if (msg.contains("already exists") || msg.contains("duplicate key")) {
                                    skipped++
                                } else {
                                    failed++
                                    logger.error("Migration FAILED: ${msg.take(200)}")
                                }
                            } catch (e: Exception) {
                                failed++
                                logger.error("Migration FAILED: ${e.message?.take(200)}")
                            }
                        }

                        val duration = System.currentTimeMillis() - startTime
                        logger.info("Database migrations completed - applied: $applied, skipped: $skipped, failed: $failed in ${duration}ms")
                        if (failed > 0) {
                            logger.error("$failed migration statements failed! Database schema may be incomplete.")
                        }
                    }
                } else {
                    logger.warn("Migration file not found - skipping migrations")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to run database migrations - server starting with potentially incomplete schema", e)
        }
    }

    @Synchronized
    fun getDataSource(): DataSource? {
        if (connectionFailed) {
            // Allow retry after interval for transient failures
            if (System.currentTimeMillis() - connectionFailedAt < CONNECTION_RETRY_INTERVAL_MS) {
                return null
            }
            logger.info("Retrying database connection after transient failure...")
            connectionFailed = false
            dataSource = null
            database = null
        }
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

            // Append PgBouncer-safe params if not already present
            if (!dbUrl.contains("prepareThreshold")) {
                dbUrl = "$dbUrl${if (dbUrl.contains("?")) "&" else "?"}prepareThreshold=0&preferQueryMode=simple"
            }

            logger.info("Connecting to database: ${dbUrl.take(50)}...")

            val config =
                HikariConfig().apply {
                    jdbcUrl = dbUrl
                    username = dbUser
                    password = dbPassword
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 15
                    minimumIdle = 3
                    idleTimeout = 300000
                    connectionTimeout = 5000
                    maxLifetime = 1800000
                    keepaliveTime = 300000
                    connectionTestQuery = "SELECT 1"
                    leakDetectionThreshold = 30000
                    // Explicitly set transaction isolation to skip PgBouncer-incompatible
                    // checkDefaultIsolation which creates prepared statements
                    transactionIsolation = "TRANSACTION_READ_COMMITTED"
                    addDataSourceProperty("tcpKeepAlive", "true")
                    addDataSourceProperty("socketTimeout", "60")
                }

            dataSource =
                try {
                    val ds = HikariDataSource(config)

                    // Test connection immediately to verify database is accessible
                    ds.connection.use { conn ->
                        conn.createStatement().executeQuery("SELECT 1").close()
                        logger.info("Database connection test successful - PostgreSQL is accessible")
                    }

                    logger.info(
                        "Database connection established successfully (pool size: ${ds.maximumPoolSize}, min idle: ${ds.minimumIdle})",
                    )
                    ds
                } catch (e: Exception) {
                    logger.error("Failed to initialize DataSource: ${e.message}")
                    logger.error("Check that PostgreSQL is running and DB_URL/DB_USER/DB_PASSWORD are correct")
                    logger.error("If using Docker: docker ps (check container), docker logs <container_id>")
                    logger.error("Server will continue without database support")
                    connectionFailed = true
                    connectionFailedAt = System.currentTimeMillis()
                    null
                }
        }
        return dataSource
    }

    fun close() {
        dataSource?.close()
        dataSource = null
        connectionFailed = false
    }

    /**
     * Execute a block of operations atomically within a single database transaction.
     * Opens one connection, begins a transaction, executes [block], and commits.
     * If [block] throws, the transaction is rolled back and the exception rethrown.
     */
    suspend fun <T> withAtomicTransaction(block: suspend (Connection) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ds = getDataSource() ?: throw IllegalStateException("Database not available")
            ds.connection.use { conn ->
                conn.autoCommit = false
                conn.setNetworkTimeout(null, 30_000) // 30s network timeout
                try {
                    val result = block(conn)
                    conn.commit()
                    result
                } catch (e: Exception) {
                    try {
                        conn.rollback()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }
        }
}
