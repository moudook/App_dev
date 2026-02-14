package com.example.smarty.server.data

/**
 * Interface for vector storage operations.
 * Decouples the agent from specific database implementations.
 * All operations are scoped by userId for multi-tenant isolation.
 */
interface VectorStore {
    /**
     * Store context with its vector embedding.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param content The text content of the context
     * @param embedding The vector embedding (float list)
     * @param metadata key-value pairs for context (e.g., source, timestamp)
     */
    suspend fun store(userId: String, content: String, embedding: List<Float>, metadata: Map<String, String>)

    /**
     * Search for similar context using vector similarity.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param embedding The query vector
     * @param limit Maximum number of results to return
     * @return List of matching results with similarity scores
     */
    suspend fun search(userId: String, embedding: List<Float>, limit: Int): List<ContextResult>

    /**
     * Search for context using a combination of vector similarity and keyword matching.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param query The raw text query for keyword matching
     * @param embedding The query vector for semantic matching
     * @param limit Maximum number of results to return
     * @return List of matching results
     */
    suspend fun hybridSearch(userId: String, query: String, embedding: List<Float>, limit: Int): List<ContextResult>

    /**
     * Get recent user context (preferences, facts) regardless of query.
     * This provides baseline context for the agent even when starting fresh.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param limit Maximum number of results to return
     * @return List of recent context entries
     */
    suspend fun getRecentContext(userId: String, limit: Int = 10): List<ContextResult>
}

/**
 * Result from a similarity search.
 */
data class ContextResult(
    val id: String,
    val content: String,
    val metadata: Map<String, String>,
    val similarity: Double
)
