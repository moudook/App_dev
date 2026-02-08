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
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

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

    /**
     * SQL to enable hybrid search in Supabase/Postgres:
     *
     * -- 1. Create a GIN index for text search
     * CREATE INDEX IF NOT EXISTS agent_memory_content_idx ON agent_memory USING GIN (to_tsvector('english', content));
     *
     * -- 2. Create the hybrid search function
     * CREATE OR REPLACE FUNCTION match_documents_hybrid(
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
     *     agent_memory.id,
     *     agent_memory.content,
     *     agent_memory.metadata,
     *     ((1 - (agent_memory.embedding <=> query_embedding)) * 0.7 +
     *      ts_rank_cd(to_tsvector('english', agent_memory.content), websearch_to_tsquery('english', query_text)) * 0.3)::FLOAT as similarity
     *   FROM agent_memory
     *   WHERE (1 - (agent_memory.embedding <=> query_embedding)) > match_threshold
     *      OR to_tsvector('english', agent_memory.content) @@ websearch_to_tsquery('english', query_text)
     *   ORDER BY similarity DESC
     *   LIMIT match_count;
     * END;
     * $$;
     */
    override suspend fun hybridSearch(query: String, embedding: List<Float>, limit: Int): List<MemoryResult> {
        if (dataSource == null) {
            logger.warn("VectorStore hybridSearch operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<MemoryResult>()

            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM match_documents_hybrid(?, ?, ?, ?)"

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, query)
                    stmt.setObject(2, PGvector(embedding))
                    stmt.setDouble(3, 0.5) // match_threshold
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
