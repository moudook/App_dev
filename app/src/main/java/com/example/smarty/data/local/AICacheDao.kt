package com.example.smarty.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Entity for storing AI response cache in Room database.
 * Provides persistent caching across app restarts.
 */
@Entity(tableName = "ai_cache")
data class CachedAIResponse(
    @PrimaryKey
    val contentHash: String,
    val jsonResponse: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val lastAccessedAt: Long = System.currentTimeMillis(),
)

/**
 * DAO for AI response caching operations.
 * Supports TTL-based expiration and LRU eviction.
 */
@Dao
interface AICacheDao {
    /**
     * Get cached response if not expired.
     */
    @Query("SELECT * FROM ai_cache WHERE contentHash = :hash AND expiresAt > :now")
    suspend fun get(
        hash: String,
        now: Long = System.currentTimeMillis(),
    ): CachedAIResponse?

    /**
     * Insert or update a cache entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CachedAIResponse)

    /**
     * Update last accessed time for LRU tracking.
     */
    @Query("UPDATE ai_cache SET lastAccessedAt = :accessTime WHERE contentHash = :hash")
    suspend fun updateLastAccess(
        hash: String,
        accessTime: Long = System.currentTimeMillis(),
    )

    /**
     * Delete expired entries.
     */
    @Query("DELETE FROM ai_cache WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long = System.currentTimeMillis())

    /**
     * Get count of all cache entries.
     */
    @Query("SELECT COUNT(*) FROM ai_cache")
    suspend fun getCount(): Int

    /**
     * Get count of valid (non-expired) entries.
     */
    @Query("SELECT COUNT(*) FROM ai_cache WHERE expiresAt > :now")
    suspend fun getValidCount(now: Long = System.currentTimeMillis()): Int

    /**
     * Delete oldest entries to maintain max size.
     * Returns number of rows deleted.
     */
    @Query(
        """
        DELETE FROM ai_cache WHERE contentHash IN (
            SELECT contentHash FROM ai_cache
            ORDER BY lastAccessedAt ASC
            LIMIT :countToDelete
        )
    """,
    )
    suspend fun evictOldest(countToDelete: Int): Int

    /**
     * Clear all cache entries.
     */
    @Query("DELETE FROM ai_cache")
    suspend fun clearAll()

    /**
     * Delete specific entry.
     */
    @Query("DELETE FROM ai_cache WHERE contentHash = :hash")
    suspend fun delete(hash: String)
}
