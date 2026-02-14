package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.ImpressedEntry
import kotlinx.coroutines.flow.Flow

/**
 * =============================================================================
 * IMPRESSED LOG DAO
 * =============================================================================
 *
 * Data Access Object for impressed user log persistence.
 * Tracks AI actions that positively impacted the user.
 *
 * =============================================================================
 */
@Dao
interface ImpressedLogDao {

    // =========================================================================
    // CRUD OPERATIONS
    // =========================================================================

    /**
     * Insert a new impressed entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: ImpressedEntry)

    /**
     * Insert multiple entries.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<ImpressedEntry>)

    /**
     * Delete a specific entry.
     */
    @Delete
    suspend fun deleteEntry(entry: ImpressedEntry)

    /**
     * Delete entry by ID.
     */
    @Query("DELETE FROM impressed_log WHERE id = :entryId")
    suspend fun deleteEntryById(entryId: String)

    /**
     * Clear all entries.
     */
    @Query("DELETE FROM impressed_log")
    suspend fun clearAllEntries()

    // =========================================================================
    // RETRIEVAL QUERIES
    // =========================================================================

    /**
     * Get all entries as a Flow for reactive updates.
     */
    @Query("SELECT * FROM impressed_log ORDER BY timestamp DESC")
    fun getAllEntriesFlow(): Flow<List<ImpressedEntry>>

    /**
     * Get all entries (one-shot).
     */
    @Query("SELECT * FROM impressed_log ORDER BY timestamp DESC")
    suspend fun getAllEntries(): List<ImpressedEntry>

    /**
     * Get recent entries for pattern analysis.
     *
     * @param limit Maximum number of entries to return
     */
    @Query("""
        SELECT * FROM impressed_log
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentEntries(limit: Int): List<ImpressedEntry>

    /**
     * Get entries by action type.
     * Useful for analyzing which actions are most successful.
     *
     * @param actionType Type of action to filter by
     */
    @Query("""
        SELECT * FROM impressed_log
        WHERE actionType = :actionType
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesByActionType(actionType: String): List<ImpressedEntry>

    /**
     * Get entries by user signal.
     * Useful for analyzing which signals are most common.
     *
     * @param userSignal Signal to filter by
     */
    @Query("""
        SELECT * FROM impressed_log
        WHERE userSignal = :userSignal
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesBySignal(userSignal: String): List<ImpressedEntry>

    /**
     * Get entries in a time range.
     *
     * @param startTime Start of range (inclusive)
     * @param endTime End of range (exclusive)
     */
    @Query("""
        SELECT * FROM impressed_log
        WHERE timestamp >= :startTime
        AND timestamp < :endTime
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesInRange(startTime: Long, endTime: Long): List<ImpressedEntry>

    // =========================================================================
    // ANALYTICS QUERIES
    // =========================================================================

    /**
     * Get count of all entries.
     */
    @Query("SELECT COUNT(*) FROM impressed_log")
    suspend fun getEntryCount(): Int

    /**
     * Get count of entries by action type.
     * Returns action types sorted by frequency.
     */
    @Query("""
        SELECT actionType, COUNT(*) as count
        FROM impressed_log
        GROUP BY actionType
        ORDER BY count DESC
    """)
    suspend fun getActionTypeCounts(): List<ActionTypeCount>

    /**
     * Get count of entries by user signal.
     * Returns signals sorted by frequency.
     */
    @Query("""
        SELECT userSignal, COUNT(*) as count
        FROM impressed_log
        GROUP BY userSignal
        ORDER BY count DESC
    """)
    suspend fun getSignalCounts(): List<SignalCount>

    /**
     * Get most successful action types (most positive impressions).
     *
     * @param limit Number of top action types to return
     */
    @Query("""
        SELECT actionType, COUNT(*) as count
        FROM impressed_log
        GROUP BY actionType
        ORDER BY count DESC
        LIMIT :limit
    """)
    suspend fun getTopActionTypes(limit: Int): List<ActionTypeCount>

    /**
     * Get entries count in the last N days.
     * Useful for tracking recent AI performance.
     *
     * @param cutoffTime Timestamp of the cutoff (entries after this time)
     */
    @Query("SELECT COUNT(*) FROM impressed_log WHERE timestamp >= :cutoffTime")
    suspend fun getRecentEntryCount(cutoffTime: Long): Int

    // =========================================================================
    // MAINTENANCE QUERIES
    // =========================================================================

    /**
     * Delete entries older than a certain time.
     * Keeps the log from growing too large.
     *
     * @param cutoffTime Entries before this time will be deleted
     */
    @Query("DELETE FROM impressed_log WHERE timestamp < :cutoffTime")
    suspend fun deleteEntriesOlderThan(cutoffTime: Long)

    /**
     * Keep only the most recent N entries.
     * Alternative approach to time-based cleanup.
     *
     * @param keepCount Number of recent entries to keep
     */
    @Query("""
        DELETE FROM impressed_log
        WHERE id NOT IN (
            SELECT id FROM impressed_log
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun keepRecentEntries(keepCount: Int)

    /**
     * Delete all log entries.
     * Used for user data cleanup on sign-out.
     */
    @Query("DELETE FROM impressed_log")
    suspend fun deleteAllLogs()
}

/**
 * Result class for action type count query.
 */
data class ActionTypeCount(
    val actionType: String,
    val count: Int
)

/**
 * Result class for signal count query.
 */
data class SignalCount(
    val userSignal: String,
    val count: Int
)
