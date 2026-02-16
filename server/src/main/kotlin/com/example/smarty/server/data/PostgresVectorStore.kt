package com.example.smarty.server.data

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * PostgreSQL implementation of VectorStore using raw JDBC and full-text search.
 * Note: Embedding-based vector search removed - using PostgreSQL full-text search.
 * Configuration is read exclusively from environment variables.
 * All operations are isolated by userId for multi-tenant security.
 */
class PostgresVectorStore : VectorStore {

    private val logger = LoggerFactory.getLogger(PostgresVectorStore::class.java)
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stores content with metadata, scoped to a specific user.
     *
     * @param userId The authenticated user's ID
     * @param content The text content to store
     * @param metadata Additional metadata
     */
    override suspend fun store(userId: String, content: String, metadata: Map<String, String>) {
        if (dataSource == null) {
            logger.warn("VectorStore store operation skipped: DB not configured")
            return
        }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO agent_context (user_id, content, metadata)
                    VALUES (?, ?, ?::jsonb)
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, content)
                    stmt.setString(3, json.encodeToString(metadata))
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Update existing context content.
     */
    suspend fun update(userId: String, contextId: String, content: String) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE agent_context SET content = ?, updated_at = NOW() WHERE id = ?::uuid AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, content)
                    stmt.setString(2, contextId)
                    stmt.setString(3, userId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Delete context by ID.
     */
    suspend fun delete(userId: String, contextId: String) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM agent_context WHERE id = ?::uuid AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, contextId)
                    stmt.setString(2, userId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Searches for similar content using full-text search.
     *
     * @param userId The authenticated user's ID
     * @param query The text query to search for
     * @param limit Maximum results to return
     */
    override suspend fun search(userId: String, query: String, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore search operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                val sql = """
                    SELECT id, content, metadata,
                        ts_rank_cd(to_tsvector('english', content), websearch_to_tsquery('english', ?)) as similarity
                    FROM agent_context
                    WHERE user_id = ?
                      AND to_tsvector('english', content) @@ websearch_to_tsquery('english', ?)
                    ORDER BY similarity DESC
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, query)
                    stmt.setString(2, userId)
                    stmt.setString(3, query)
                    stmt.setInt(4, limit)

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

    private fun mapRow(rs: ResultSet): ContextResult {
        val metadataJson = rs.getString("metadata")
        val metadata: Map<String, String> = try {
            if (metadataJson != null) json.decodeFromString(metadataJson) else emptyMap()
        } catch (e: Exception) {
            logger.warn("Failed to parse metadata JSON", e)
            emptyMap()
        }

        return ContextResult(
            id = rs.getString("id"),
            content = rs.getString("content"),
            metadata = metadata,
            similarity = rs.getDouble("similarity")
        )
    }

    /**
     * Get recent user context entries (preferences, facts, episodic memories).
     * This provides baseline context for the agent even without a specific query.
     */
    override suspend fun getRecentContext(userId: String, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.debug("VectorStore getRecentContext skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()
            dataSource.connection.use { conn ->
                val sql = """
                    SELECT id, content, metadata, 1.0 as similarity
                    FROM agent_context
                    WHERE user_id = ?
                    ORDER BY
                        CASE
                            WHEN metadata->>'type' = 'preference' THEN 1
                            WHEN metadata->>'type' = 'factual' THEN 2
                            WHEN metadata->>'type' = 'episodic' THEN 3
                            ELSE 4
                        END,
                        created_at DESC
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setInt(2, limit)

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

    /**
     * Optional: Initialize the schema if it doesn't exist.
     */
    suspend fun initSchema() {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_context (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id TEXT NOT NULL DEFAULT '',
                                content TEXT NOT NULL,
                                metadata JSONB,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())

                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_context_content ON agent_context USING GIN (to_tsvector('english', content));")
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
