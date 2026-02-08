package com.example.smarty.server.data

/**
 * Interface for vector storage operations.
 * Decouples the agent from specific database implementations.
 */
interface VectorStore {
    /**
     * Store a memory with its vector embedding.
     *
     * @param content The text content of the memory
     * @param embedding The vector embedding (float list)
     * @param metadata key-value pairs for context (e.g., source, timestamp)
     */
    suspend fun store(content: String, embedding: List<Float>, metadata: Map<String, String>)

    /**
     * Search for similar memories using vector similarity.
     *
     * @param embedding The query vector
     * @param limit Maximum number of results to return
     * @return List of matching memories with similarity scores
     */
    suspend fun search(embedding: List<Float>, limit: Int): List<MemoryResult>

    /**
     * Search for memories using a combination of vector similarity and keyword matching.
     *
     * @param query The raw text query for keyword matching
     * @param embedding The query vector for semantic matching
     * @param limit Maximum number of results to return
     * @return List of matching memories
     */
    suspend fun hybridSearch(query: String, embedding: List<Float>, limit: Int): List<MemoryResult>
}

/**
 * Result from a similarity search.
 */
data class MemoryResult(
    val id: String,
    val content: String,
    val metadata: Map<String, String>,
    val similarity: Double
)
