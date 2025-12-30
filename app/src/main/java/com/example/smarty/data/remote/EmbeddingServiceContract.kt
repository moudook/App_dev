package com.example.smarty.data.remote

/**
 * Contract interface for embedding service implementations.
 *
 * This interface defines the standard operations that all embedding providers must support.
 * Allows SemanticCache to work with different embedding providers (OpenAI, Gemini, etc.)
 *
 * Usage:
 * ```kotlin
 * val embeddingService: EmbeddingServiceContract = GeminiEmbeddingService(apiKey)
 * val embedding = embeddingService.embed("Hello world")
 * ```
 *
 * @see EmbeddingService (OpenAI implementation)
 * @see GeminiEmbeddingService (Gemini implementation)
 */
interface EmbeddingServiceContract {

    /**
     * Generate embedding vector for text.
     *
     * @param text The text to embed
     * @return FloatArray of embedding dimensions, or null on error
     */
    suspend fun embed(text: String): FloatArray?

    /**
     * Calculate cosine similarity between two embedding vectors.
     *
     * @param a First embedding vector
     * @param b Second embedding vector
     * @return Similarity score between 0 and 1 (1 = identical)
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float

    /**
     * Batch embed multiple texts.
     *
     * @param texts List of texts to embed
     * @return Map of text to embedding (only successful embeddings included)
     */
    suspend fun embedBatch(texts: List<String>): Map<String, FloatArray>
}
