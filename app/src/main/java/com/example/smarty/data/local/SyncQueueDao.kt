package com.example.smarty.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * =============================================================================
 * SYNC QUEUE DAO - Data Access Object for Offline Sync Queue
 * =============================================================================
 *
 * Provides CRUD operations for the sync queue and conflict archive.
 * Used by SyncWorker to drain pending operations to the server.
 *
 * USAGE:
 * 1. On every local write, enqueue the operation
 * 2. SyncWorker calls getPendingItems() to fetch work
 * 3. After server response, update status (synced, failed, conflict)
 * 4. Conflicts are archived for potential manual recovery
 *
 * =============================================================================
 */
@Dao
interface SyncQueueDao {
    // ============================================================================
    // SYNC QUEUE OPERATIONS
    // ============================================================================

    /**
     * Insert a new sync queue item.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueItem)

    /**
     * Insert multiple sync queue items.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SyncQueueItem>)

    /**
     * Update an existing sync queue item.
     */
    @Update
    suspend fun update(item: SyncQueueItem)

    /**
     * Delete a sync queue item by ID.
     */
    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete all synced items (cleanup).
     */
    @Query("DELETE FROM sync_queue WHERE status = 'SYNCED'")
    suspend fun deleteSyncedItems()

    /**
     * Delete all items for a specific entity (e.g., after entity is deleted).
     */
    @Query("DELETE FROM sync_queue WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun deleteForEntity(
        entityId: String,
        entityType: String,
    )

    /**
     * Get a sync queue item by ID.
     */
    @Query("SELECT * FROM sync_queue WHERE id = :id")
    suspend fun getById(id: String): SyncQueueItem?

    /**
     * Get all pending items, ordered by creation time (FIFO).
     */
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingItems(limit: Int = 50): List<SyncQueueItem>

    /**
     * Get all pending items as a Flow for UI observation.
     */
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingItemsFlow(): Flow<List<SyncQueueItem>>

    /**
     * Get all items with a specific status.
     */
    @Query("SELECT * FROM sync_queue WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getItemsByStatus(status: String): List<SyncQueueItem>

    /**
     * Get all items for a specific entity.
     */
    @Query("SELECT * FROM sync_queue WHERE entityId = :entityId AND entityType = :entityType ORDER BY createdAt ASC")
    suspend fun getItemsForEntity(
        entityId: String,
        entityType: String,
    ): List<SyncQueueItem>

    /**
     * Get count of pending items.
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    /**
     * Get count of pending items as Flow.
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    /**
     * Get count of failed items.
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'FAILED'")
    suspend fun getFailedCount(): Int

    /**
     * Get count of conflict items.
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'CONFLICT'")
    suspend fun getConflictCount(): Int

    /**
     * Get all items in the queue (for debugging).
     */
    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC")
    suspend fun getAll(): List<SyncQueueItem>

    /**
     * Mark an item as in-flight (being processed).
     */
    @Query("UPDATE sync_queue SET status = 'IN_FLIGHT' WHERE id = :id")
    suspend fun markInFlight(id: String)

    /**
     * Mark an item as synced with server timestamp.
     */
    @Query("UPDATE sync_queue SET status = 'SYNCED', serverTimestamp = :serverTimestamp WHERE id = :id")
    suspend fun markSynced(
        id: String,
        serverTimestamp: Long,
    )

    /**
     * Mark an item as failed with error message.
     */
    @Query("UPDATE sync_queue SET status = 'FAILED', lastError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(
        id: String,
        error: String,
    )

    /**
     * Mark an item as conflict.
     */
    @Query("UPDATE sync_queue SET status = 'CONFLICT', lastError = :error WHERE id = :id")
    suspend fun markConflict(
        id: String,
        error: String,
    )

    /**
     * Reset failed items to pending (for manual retry).
     */
    @Query("UPDATE sync_queue SET status = 'PENDING', retryCount = 0 WHERE status = 'FAILED'")
    suspend fun resetFailedItems()

    /**
     * Clear all items (for testing or reset).
     */
    @Query("DELETE FROM sync_queue")
    suspend fun clearAll()

    // ============================================================================
    // CONFLICT ARCHIVE OPERATIONS
    // ============================================================================

    /**
     * Insert a conflict record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConflict(record: ConflictRecord)

    /**
     * Get all conflict records.
     */
    @Query("SELECT * FROM conflict_records ORDER BY resolvedAt DESC")
    suspend fun getAllConflicts(): List<ConflictRecord>

    /**
     * Get conflict records for a specific entity.
     */
    @Query("SELECT * FROM conflict_records WHERE entityId = :entityId ORDER BY resolvedAt DESC")
    suspend fun getConflictsForEntity(entityId: String): List<ConflictRecord>

    /**
     * Get count of conflict records.
     */
    @Query("SELECT COUNT(*) FROM conflict_records")
    suspend fun getConflictRecordCount(): Int

    /**
     * Delete a conflict record by ID.
     */
    @Query("DELETE FROM conflict_records WHERE id = :id")
    suspend fun deleteConflictById(id: String)

    /**
     * Clear all conflict records.
     */
    @Query("DELETE FROM conflict_records")
    suspend fun clearAllConflicts()

    // ============================================================================
    // SYNC STATUS SUMMARY
    // ============================================================================

    /**
     * Get sync status summary.
     */
    @Query(
        """
        SELECT 
            COUNT(CASE WHEN status = 'PENDING' THEN 1 END) as pending,
            COUNT(CASE WHEN status = 'IN_FLIGHT' THEN 1 END) as inFlight,
            COUNT(CASE WHEN status = 'SYNCED' THEN 1 END) as synced,
            COUNT(CASE WHEN status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN status = 'CONFLICT' THEN 1 END) as conflicted
        FROM sync_queue
    """,
    )
    suspend fun getSyncStatusSummary(): SyncStatusSummary

    /**
     * Get sync status summary as Flow.
     */
    @Query(
        """
        SELECT 
            COUNT(CASE WHEN status = 'PENDING' THEN 1 END) as pending,
            COUNT(CASE WHEN status = 'IN_FLIGHT' THEN 1 END) as inFlight,
            COUNT(CASE WHEN status = 'SYNCED' THEN 1 END) as synced,
            COUNT(CASE WHEN status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN status = 'CONFLICT' THEN 1 END) as conflicted
        FROM sync_queue
    """,
    )
    fun getSyncStatusSummaryFlow(): Flow<SyncStatusSummary>
}

/**
 * Summary of sync queue status.
 */
data class SyncStatusSummary(
    val pending: Int = 0,
    val inFlight: Int = 0,
    val synced: Int = 0,
    val failed: Int = 0,
    val conflicted: Int = 0,
) {
    val total: Int get() = pending + inFlight + synced + failed + conflicted
    val hasPending: Boolean get() = pending > 0
    val hasFailures: Boolean get() = failed > 0
    val hasConflicts: Boolean get() = conflicted > 0
    val isSynced: Boolean get() = pending == 0 && inFlight == 0 && failed == 0 && conflicted == 0
}
