package com.example.smarty.server.data

import com.pgvector.PGvector
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * PostgreSQL/Supabase implementation of VectorStore using raw JDBC and pgvector.
 *
 * Designed for high performance and low memory footprint (no ORM).
 * Configuration is read exclusively from environment variables.
 * All operations are isolated by userId for multi-tenant security.
 */
class PostgresVectorStore : VectorStore {

    private val logger = LoggerFactory.getLogger(PostgresVectorStore::class.java)
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stores content with embedding, scoped to a specific user.
     *
     * @param userId The authenticated user's ID
     * @param content The text content to store
     * @param embedding The vector embedding
     * @param metadata Additional metadata
     */
    override suspend fun store(userId: String, content: String, embedding: List<Float>, metadata: Map<String, String>) {
        if (dataSource == null) {
            logger.warn("VectorStore store operation skipped: DB not configured")
            return
        }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO agent_context (user_id, content, embedding, metadata)
                    VALUES (?, ?, ?, ?::jsonb)
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, content)
                    stmt.setObject(3, PGvector(embedding))
                    stmt.setString(4, json.encodeToString(metadata))
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * Update existing context content and re-calculate embedding.
     */
    suspend fun update(userId: String, contextId: String, content: String, embedding: List<Float>) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE agent_context SET content = ?, embedding = ?, updated_at = NOW() WHERE id = ?::uuid AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, content)
                    stmt.setObject(2, PGvector(embedding))
                    stmt.setString(3, contextId)
                    stmt.setString(4, userId)
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
     * Searches for similar content, scoped to a specific user.
     *
     * @param userId The authenticated user's ID
     * @param embedding The query vector
     * @param limit Maximum results to return
     */
    override suspend fun search(userId: String, embedding: List<Float>, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore search operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                // Cosine distance operator (<=>) orders by distance (lower is better/more similar)
                // We return similarity as (1 - distance) for intuitive scoring
                val sql = """
                    SELECT id, content, metadata, 1 - (embedding <=> ?) as similarity
                    FROM agent_context
                    WHERE user_id = ?
                    ORDER BY embedding <=> ?
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    val vector = PGvector(embedding)
                    stmt.setObject(1, vector)
                    stmt.setString(2, userId)
                    stmt.setObject(3, vector)
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

    /**
     * SQL to enable hybrid search in Supabase/Postgres:
     *
     * -- 1. Create a GIN index for text search
     * CREATE INDEX IF NOT EXISTS agent_context_content_idx ON agent_context USING GIN (to_tsvector('english', content));
     *
     * -- 2. Create the hybrid search function
     * CREATE OR REPLACE FUNCTION match_documents_hybrid(
     *   query_user_id TEXT,
     *   query_text TEXT,
     *   query_embedding VECTOR(1536),
     *   match_threshold FLOAT,
     *   match_count INT
     * )
     * RETURNS TABLE (
     *   id UUID,
     *   content TEXT,
     *   metadata JSONB,
     *   similarity FLOAT
     * )
     * LANGUAGE plpgsql
     * AS $$
     * BEGIN
     *   RETURN QUERY
     *   SELECT
     *     agent_context.id,
     *     agent_context.content,
     *     agent_context.metadata,
     *     ((1 - (agent_context.embedding <=> query_embedding)) * 0.7 +
     *      ts_rank_cd(to_tsvector('english', agent_context.content), websearch_to_tsquery('english', query_text)) * 0.3)::FLOAT as similarity
     *   FROM agent_context
     *   WHERE agent_context.user_id = query_user_id
     *     AND ((1 - (agent_context.embedding <=> query_embedding)) > match_threshold
     *      OR to_tsvector('english', agent_context.content) @@ websearch_to_tsquery('english', query_text))
     *   ORDER BY similarity DESC
     *   LIMIT match_count;
     * END;
     * $$;
     */
    override suspend fun hybridSearch(userId: String, query: String, embedding: List<Float>, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore hybridSearch operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM match_documents_hybrid(?, ?, ?, ?, ?)"

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, query)
                    stmt.setObject(3, PGvector(embedding))
                    stmt.setDouble(4, 0.5) // match_threshold
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
                // Get recent preferences and facts, prioritizing important types
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
                        
                        // 1. RAG Context Table
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_context (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id TEXT NOT NULL DEFAULT '',
                                content TEXT NOT NULL,
                                embedding VECTOR(1536),
                                metadata JSONB,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())
                        
                        // 2. Agent Tracing Table (KOOG-inspired)
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_traces (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                session_id TEXT NOT NULL,
                                user_id TEXT NOT NULL,
                                step_type TEXT NOT NULL,
                                content TEXT NOT NULL,
                                metadata JSONB,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())
                        
                        // 3. Agent Checkpoints (State Persistence)
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_checkpoints (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                session_id TEXT NOT NULL UNIQUE,
                                user_id TEXT NOT NULL,
                                state_json JSONB NOT NULL,
                                last_node TEXT,
                                version BIGINT DEFAULT 0,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())

                        stmt.execute("CREATE INDEX IF NOT EXISTS agent_context_embedding_idx ON agent_context USING hnsw (embedding vector_cosine_ops);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_traces_session ON agent_traces(session_id);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_checkpoints_session ON agent_checkpoints(session_id);")
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
