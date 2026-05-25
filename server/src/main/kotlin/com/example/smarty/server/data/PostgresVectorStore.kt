package com.example.smarty.server.data

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.ResultSet

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
     * Stores a memory note for a specific user into the `notes` table (v6 schema).
     * This is used by the `memory` tool (action=remember).
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param content The text content to store
     * @param metadata Additional metadata (type, category, etc.)
     */
    override suspend fun store(
        userId: String,
        content: String,
        metadata: Map<String, String>,
    ) {
        if (dataSource == null) {
            logger.warn("VectorStore store operation skipped: DB not configured")
            return
        }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                // v6: store memories as notes - category is now stored in metadata
                val title = metadata["title"] ?: content.take(50)
                val metadataJson =
                    json.encodeToString(
                        metadata + mapOf("memory_type" to (metadata["type"] ?: metadata["category"] ?: "memory")),
                    )
                val sql =
                    """
                    INSERT INTO notes (user_id, title, content, metadata, category_id, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?::jsonb, (SELECT id FROM categories WHERE name = ? AND user_id = ?::uuid LIMIT 1), now(), now())
                    ON CONFLICT DO NOTHING
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, title)
                    stmt.setString(3, content)
                    stmt.setString(4, metadataJson)
                    stmt.setString(5, metadata["type"] ?: metadata["category"] ?: "preference")
                    stmt.setString(6, userId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Update existing note content by ID (v6 schema).
     */
    suspend fun update(
        userId: String,
        contextId: String,
        content: String,
    ) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE notes SET content = ?, updated_at = NOW() WHERE id = ?::uuid AND user_id = ?::uuid AND deleted_at IS NULL"
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
     * Soft-delete a note by ID (v6 schema uses deleted_at).
     */
    suspend fun delete(
        userId: String,
        contextId: String,
    ) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE notes SET deleted_at = NOW() WHERE id = ?::uuid AND user_id = ?::uuid"
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
    override suspend fun search(
        userId: String,
        query: String,
        limit: Int,
    ): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore search operation skipped: DB not configured")
            return emptyList()
        }

        // Skip empty or very short queries
        if (query.isBlank() || query.length < 2) {
            return getRecentContext(userId, limit)
        }

        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                // Optimized FTS query - use simpler search for better performance
                val sql =
                    """
                    SELECT id::text, COALESCE(content, '') as content,
                        COALESCE(metadata, '{}') as metadata,
                        ts_rank(to_tsvector('english', COALESCE(title,'') || ' ' || COALESCE(content,'')),
                                   plainto_tsquery('english', ?)) as rank
                    FROM notes
                    WHERE user_id = ?::uuid
                      AND deleted_at IS NULL
                      AND is_archived = false
                      AND to_tsvector('english', COALESCE(title,'') || ' ' || COALESCE(content,'')) @@ plainto_tsquery('english', ?)
                    ORDER BY rank DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, query)
                    stmt.setString(2, userId)
                    stmt.setString(3, query)
                    stmt.setInt(4, limit.coerceIn(1, 20)) // Cap at 20 results

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
        val metadata: Map<String, String> =
            try {
                if (metadataJson != null) json.decodeFromString(metadataJson) else emptyMap()
            } catch (e: Exception) {
                logger.warn("Failed to parse metadata JSON", e)
                emptyMap()
            }

        return ContextResult(
            id = rs.getString("id"),
            content = rs.getString("content"),
            metadata = metadata,
            // FIXED: Column is named 'rank' in the SQL query, not 'similarity'
            similarity = rs.getDouble("rank"),
        )
    }

    /**
     * Get recent user notes/memories for baseline agent context.
     * v6 schema: reads from `notes`, ordered by category priority then recency.
     */
    override suspend fun getRecentContext(
        userId: String,
        limit: Int,
    ): List<ContextResult> {
        if (dataSource == null) {
            logger.debug("VectorStore getRecentContext skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT n.id::text,
                           COALESCE(n.content, '') as content,
                           COALESCE(n.metadata, '{}') as metadata,
                           1.0 as rank
                    FROM notes n
                    WHERE n.user_id = ?::uuid
                      AND n.deleted_at IS NULL
                    ORDER BY
                        CASE 
                            WHEN n.category_id = (SELECT id FROM categories WHERE name = 'preference' AND user_id = ?::uuid LIMIT 1) THEN 1
                            WHEN n.category_id = (SELECT id FROM categories WHERE name = 'memory' AND user_id = ?::uuid LIMIT 1) THEN 2
                            WHEN n.category_id = (SELECT id FROM categories WHERE name = 'factual' AND user_id = ?::uuid LIMIT 1) THEN 3
                            ELSE 4
                        END,
                        n.updated_at DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, userId)
                    stmt.setString(3, userId)
                    stmt.setString(4, userId)
                    stmt.setInt(5, limit)

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
     * Ensure FTS indexes exist on the notes table (idempotent, safe at startup).
     */
    suspend fun initSchema() {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC) WHERE deleted_at IS NULL",
                        )
                        stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_notes_content_fts ON notes USING GIN (to_tsvector('english', COALESCE(title,'') || ' ' || COALESCE(content,''))) WHERE deleted_at IS NULL",
                        )
                    }
                }
                logger.info("notes FTS indexes verified successfully")
            } catch (e: Exception) {
                logger.warn("Could not verify notes indexes (non-fatal): ${e.message}")
            }
        }
    }

    fun close() {
        if (dataSource != null && !dataSource.isClosed) {
            dataSource.close()
        }
    }
}
