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
 * This version uses the 'agent_memory' table. Note: 'PostgresVectorStore' 
 * is the primary implementation used by the agent, which uses 'agent_context'.
 */
class SupabaseVectorStore : VectorStore {

    private val logger = LoggerFactory.getLogger(SupabaseVectorStore::class.java)
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun store(userId: String, content: String, embedding: List<Float>, metadata: Map<String, String>) {
        if (dataSource == null) {
            logger.warn("VectorStore store operation skipped: DB not configured")
            return
        }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO agent_memory (user_id, content, embedding, metadata)
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

    override suspend fun search(userId: String, embedding: List<Float>, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore search operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                val sql = """
                    SELECT id, content, metadata, 1 - (embedding <=> ?) as similarity
                    FROM agent_memory
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

    override suspend fun hybridSearch(userId: String, query: String, embedding: List<Float>, limit: Int): List<ContextResult> {
        if (dataSource == null) {
            logger.warn("VectorStore hybridSearch operation skipped: DB not configured")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()

            dataSource.connection.use { conn ->
                // Using a similar hybrid search logic as PostgresVectorStore but for agent_memory
                val sql = """
                    SELECT id, content, metadata, 
                    ((1 - (embedding <=> ?)) * 0.7 + 
                     ts_rank_cd(to_tsvector('english', content), websearch_to_tsquery('english', ?)) * 0.3)::FLOAT as similarity
                    FROM agent_memory
                    WHERE user_id = ?
                    ORDER BY similarity DESC
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, PGvector(embedding))
                    stmt.setString(2, query)
                    stmt.setString(3, userId)
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

    override suspend fun getRecentContext(userId: String, limit: Int): List<ContextResult> {
        if (dataSource == null) return emptyList()
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ContextResult>()
            dataSource.connection.use { conn ->
                val sql = "SELECT id, content, metadata, 1.0 as similarity FROM agent_memory WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
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

    private fun mapRow(rs: ResultSet): ContextResult {
        val metadataJson = rs.getString("metadata")
        val metadata: Map<String, String> = try {
            if (metadataJson != null) json.decodeFromString(metadataJson) else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        return ContextResult(
            id = rs.getString("id"),
            content = rs.getString("content"),
            metadata = metadata,
            similarity = rs.getDouble("similarity")
        )
    }

    suspend fun initSchema() {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector;")
                        stmt.execute("""
                            CREATE TABLE IF NOT EXISTS agent_memory (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id TEXT NOT NULL DEFAULT '',
                                content TEXT NOT NULL,
                                embedding VECTOR(1536),
                                metadata JSONB,
                                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                            );
                        """.trimIndent())
                        stmt.execute("CREATE INDEX IF NOT EXISTS agent_memory_embedding_idx ON agent_memory USING hnsw (embedding vector_cosine_ops);")
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_user ON agent_memory(user_id);")
                    }
                }
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
