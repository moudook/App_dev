package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import kotlinx.coroutines.flow.Flow

/**
 * =============================================================================
 * AI MEMORY DAO
 * =============================================================================
 *
 * Data Access Object for AI memory persistence.
 * Provides CRUD operations and specialized queries for context building.
 *
 * =============================================================================
 */
@Dao
interface AIMemoryDao {

    // =========================================================================
    // CRUD OPERATIONS
    // =========================================================================

    /**
     * Insert a new memory. Replaces if ID already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: AIMemory)

    /**
     * Insert multiple memories.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<AIMemory>)

    /**
     * Update an existing memory.
     */
    @Update
    suspend fun updateMemory(memory: AIMemory)

    /**
     * Delete a specific memory.
     */
    @Delete
    suspend fun deleteMemory(memory: AIMemory)

    /**
     * Delete memory by ID.
     */
    @Query("DELETE FROM ai_memories WHERE id = :memoryId")
    suspend fun deleteMemoryById(memoryId: String)

    /**
     * Clear all memories.
     */
    @Query("DELETE FROM ai_memories")
    suspend fun clearAllMemories()

    // =========================================================================
    // RETRIEVAL QUERIES
    // =========================================================================

    /**
     * Get a specific memory by ID.
     */
    @Query("SELECT * FROM ai_memories WHERE id = :memoryId")
    suspend fun getMemoryById(memoryId: String): AIMemory?

    /**
     * Get all memories as a Flow for reactive updates.
     */
    @Query("SELECT * FROM ai_memories ORDER BY lastUsedAt DESC")
    fun getAllMemoriesFlow(): Flow<List<AIMemory>>

    /**
     * Get all memories (one-shot).
     */
    @Query("SELECT * FROM ai_memories ORDER BY lastUsedAt DESC")
    suspend fun getAllMemories(): List<AIMemory>

    /**
     * Get recent memories for context building.
     * Sorted by last used time, most recent first.
     * Used when building AI agent prompt.
     *
     * @param limit Maximum number of memories to return
     */
    @Query("""
        SELECT * FROM ai_memories
        ORDER BY lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentMemories(limit: Int): List<AIMemory>

    /**
     * Get top memories by usage count.
     * Returns frequently used/useful memories.
     *
     * @param limit Maximum number of memories to return
     */
    @Query("""
        SELECT * FROM ai_memories
        ORDER BY usageCount DESC, confidence DESC
        LIMIT :limit
    """)
    suspend fun getMostUsedMemories(limit: Int): List<AIMemory>

    /**
     * Get high-confidence memories.
     * Returns memories the AI is most sure about.
     *
     * @param minConfidence Minimum confidence threshold (0.0-1.0)
     * @param limit Maximum number of memories to return
     */
    @Query("""
        SELECT * FROM ai_memories
        WHERE confidence >= :minConfidence
        ORDER BY confidence DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getHighConfidenceMemories(minConfidence: Float, limit: Int): List<AIMemory>

    /**
     * Get memories by type.
     * Useful for retrieving specific categories of memories.
     *
     * @param type Memory type to filter by
     */
    @Query("SELECT * FROM ai_memories WHERE type = :type ORDER BY lastUsedAt DESC")
    suspend fun getMemoriesByType(type: MemoryType): List<AIMemory>

    /**
     * Get memories by type with limit.
     */
    @Query("""
        SELECT * FROM ai_memories
        WHERE type = :type
        ORDER BY lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getMemoriesByType(type: MemoryType, limit: Int): List<AIMemory>

    /**
     * Search memories by content.
     * Simple text search for finding relevant memories.
     *
     * @param query Search term
     */
    @Query("""
        SELECT * FROM ai_memories
        WHERE content LIKE '%' || :query || '%'
        ORDER BY lastUsedAt DESC
    """)
    suspend fun searchMemories(query: String): List<AIMemory>

    /**
     * Get relevant memories based on a query.
     * Uses keyword matching and limits results for token efficiency.
     *
     * @param query The user's query to match against
     * @param limit Maximum number of memories to return
     */
    @Query("""
        SELECT * FROM ai_memories
        WHERE content LIKE '%' || :query || '%'
        OR :query LIKE '%' || content || '%'
        ORDER BY confidence DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getRelevantMemories(query: String, limit: Int): List<AIMemory>

    // =========================================================================
    // USAGE TRACKING
    // =========================================================================

    /**
     * Increment usage count and update last used timestamp.
     * Call this when a memory is included in agent context.
     *
     * @param memoryId ID of the memory being used
     */
    @Query("""
        UPDATE ai_memories
        SET usageCount = usageCount + 1,
            lastUsedAt = :timestamp
        WHERE id = :memoryId
    """)
    suspend fun incrementUsage(memoryId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update confidence level for a memory.
     * Call when user confirms or contradicts a memory.
     *
     * @param memoryId ID of the memory to update
     * @param newConfidence New confidence value (0.0-1.0)
     */
    @Query("""
        UPDATE ai_memories
        SET confidence = :newConfidence
        WHERE id = :memoryId
    """)
    suspend fun updateConfidence(memoryId: String, newConfidence: Float)

    /**
     * Decrease confidence for a memory (when user contradicts it).
     * Reduces by 0.2, with minimum of 0.1.
     *
     * @param memoryId ID of the memory to decrease confidence
     */
    @Query("""
        UPDATE ai_memories
        SET confidence = MAX(0.1, confidence - 0.2)
        WHERE id = :memoryId
    """)
    suspend fun decreaseConfidence(memoryId: String)

    // =========================================================================
    // MAINTENANCE QUERIES
    // =========================================================================

    /**
     * Get count of all memories.
     */
    @Query("SELECT COUNT(*) FROM ai_memories")
    suspend fun getMemoryCount(): Int

    /**
     * Delete old, low-confidence, rarely-used memories.
     * Keeps the memory store lean.
     *
     * @param maxAge Maximum age in milliseconds
     * @param maxConfidence Maximum confidence threshold
     * @param maxUsageCount Maximum usage count threshold
     */
    @Query("""
        DELETE FROM ai_memories
        WHERE lastUsedAt < :maxAge
        AND confidence < :maxConfidence
        AND usageCount < :maxUsageCount
    """)
    suspend fun pruneOldMemories(maxAge: Long, maxConfidence: Float, maxUsageCount: Int)

    /**
     * Delete memories older than a certain age.
     *
     * @param maxAge Maximum age in milliseconds (from current time)
     */
    @Query("""
        DELETE FROM ai_memories
        WHERE lastUsedAt < :cutoffTime
    """)
    suspend fun deleteMemoriesOlderThan(cutoffTime: Long)

    /**
     * Check if a similar memory already exists.
     * Helps prevent duplicate memories.
     *
     * @param content Content to check for
     * @param type Memory type
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM ai_memories
            WHERE type = :type
            AND content = :content
        )
    """)
    suspend fun memoryExists(content: String, type: MemoryType): Boolean

    /**
     * Get count of memories by type.
     */
    @Query("SELECT COUNT(*) FROM ai_memories WHERE type = :type")
    suspend fun getMemoryCountByType(type: MemoryType): Int
}
