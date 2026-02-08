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
        getDataSource()
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
