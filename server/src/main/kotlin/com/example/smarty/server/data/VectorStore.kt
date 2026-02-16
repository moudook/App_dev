package com.example.smarty.server.data

/**
 * Interface for context storage operations.
 * Decouples the agent from specific database implementations.
 * All operations are scoped by userId for multi-tenant isolation.
 * Note: Embedding-based search removed - using text-only search.
 */
interface VectorStore {
    /**
     * Store context with metadata.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param content The text content of the context
     * @param metadata key-value pairs for context (e.g., source, type, timestamp)
     */
    suspend fun store(userId: String, content: String, metadata: Map<String, String>)

    /**
     * Search for context using full-text search.
     *
     * @param userId The authenticated user's ID for multi-tenant isolation
     * @param query The text query to search for
     * @param limit Maximum number of results to return
     * @return List of matching results with similarity scores
     */
    suspend fun search(userId: String, query: String, limit: Int): List<ContextResult>

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
 * Result from a context search.
 */
data class ContextResult(
    val id: String,
    val content: String,
    val metadata: Map<String, String>,
    val similarity: Double
)
