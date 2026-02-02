package com.example.smarty.server.data

import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * PostgreSQL/Supabase implementation of VectorStore using raw JDBC and pgvector.
 *
 * Designed for high performance and low memory footprint (no ORM).
 * Configuration is read exclusively from environment variables.
 */
class SupabaseVectorStore : VectorStore {

    private val logger = LoggerFactory.getLogger(SupabaseVectorStore::class.java)
    private val dataSource: HikariDataSource?
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val dbUrl = System.getenv("DB_URL")
        val dbUser = System.getenv("DB_USER")
        val dbPassword = System.getenv("DB_PASSWORD")

        if (dbUrl.isNullOrBlank()) {
            logger.warn("DB_URL environment variable not set. Vector store disabled.")
            dataSource = null
        } else {
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
    }

    override suspend fun store(content: String, embedding: List<Float>, metadata: Map<String, String>) {
        if (dataSource == null) {
            logger.warn("VectorStore store operation skipped: DB not configured")
            return
        }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO agent_memory (content, embedding, metadata)
                    VALUES (?, ?, ?::jsonb)
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, content)
                    stmt.setObject(2, PGvector(embedding))
                    stmt.setString(3, json.encodeToString(metadata))
                    stmt.executeUpdate()
                }
            }
        }
    }

    override suspend fun search(embedding: List<Float>, limit: Int): List<MemoryResult> {
        if (dataSource == null) {
            logger.warn("VectorStore search operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<MemoryResult>()

            dataSource.connection.use { conn ->
                // Cosine distance operator (<=>) orders by distance (lower is better/more similar)
                // We return similarity as (1 - distance) for intuitive scoring
                val sql = """
                    SELECT id, content, metadata, 1 - (embedding <=> ?) as similarity
                    FROM agent_memory
                    ORDER BY embedding <=> ?
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    val vector = PGvector(embedding)
                    stmt.setObject(1, vector)
                    stmt.setObject(2, vector)
                    stmt.setInt(3, limit)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRow(rs))
                        }
                    }
                }
            }
            results
        }
    }

    private fun mapRow(rs: ResultSet): MemoryResult {
        val metadataJson = rs.getString("metadata")
        val metadata: Map<String, String> = try {
            if (metadataJson != null) json.decodeFromString(metadataJson) else emptyMap()
        } catch (e: Exception) {
            logger.warn("Failed to parse metadata JSON", e)
            emptyMap()
        }

        return MemoryResult(
            id = rs.getString("id"),
            content = rs.getString("content"),
            metadata = metadata,
            similarity = rs.getDouble("similarity")
        )
    }

    /**
     * Optional: Initialize the schema if it doesn't exist.
     * Useful for first runs.
     */
    suspend fun initSchema() {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            // Only attempt if connection is valid
            try {
                dataSource.connection.use { conn ->
                    // Read schema.sql from resources would be better, but for simplicity:
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector;")
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_memory (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                content TEXT NOT NULL,
                                embedding VECTOR(1536),
                                metadata JSONB,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())
                        stmt.execute("""
                            CREATE INDEX IF NOT EXISTS agent_memory_embedding_idx
                            ON agent_memory USING hnsw (embedding vector_cosine_ops);
                        """.trimIndent())
                    }
                }
                logger.info("Database schema initialized successfully")
            } catch (e: Exception) {
                logger.error("Failed to initialize schema: ${e.message}")
            }
        }
    }

    fun close() {
        if (dataSource != null && !dataSource.isClosed) {
            dataSource.close()
        }
    }
}
