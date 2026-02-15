package com.example.smarty.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * =============================================================================
 * SYNC QUEUE - Offline Write Queue for Cloud-First Architecture
 * =============================================================================
 *
 * All local writes (CREATE, UPDATE, DELETE) are queued here before being
 * sent to the server. This enables:
 *
 * 1. Offline operation - writes succeed immediately locally
 * 2. Optimistic UI - user sees changes instantly
 * 3. Reliable sync - queue is drained when connectivity resumes
 * 4. Conflict tracking - pending operations are tracked for resolution
 *
 * SYNC FLOW:
 * User Action -> Room DB (optimistic write)
 *            -> SyncQueue (queued with status=PENDING)
 *            -> SyncWorker drains queue -> Server API
 *            -> Server confirms -> Room DB (status=SYNCED, server_timestamp)
 *
 * CONFLICT RESOLUTION:
 * Server is authoritative. Server timestamp wins (LWW).
 * If conflict detected, local changes are archived for manual review.
 *
 * =============================================================================
 */

/**
 * Types of operations that can be synced.
 */
enum class SyncOperation {
    CREATE,
    UPDATE,
    DELETE
}

/**
 * Types of entities that can be synced.
 */
enum class SyncEntityType {
    NOTE,
    CATEGORY,
    EVENT,
    MEMORY,
    CHAT_SESSION
}

/**
 * Status of a sync queue item.
 */
enum class SyncStatus {
    PENDING,      // Queued, waiting to be sent
    IN_FLIGHT,    // Currently being sent to server
    SYNCED,       // Successfully synced to server
    FAILED,       // Failed after max retries
    CONFLICT      // Conflict detected, needs resolution
}

/**
 * Sync queue item representing a pending operation.
 *
 * @param id Unique identifier for this queue item
 * @param operation Type of operation (CREATE, UPDATE, DELETE)
 * @param entityType Type of entity being operated on
 * @param entityId ID of the entity
 * @param payloadJson Full serialized entity data
 * @param baseVersion Version of entity at time of write (for conflict detection)
 * @param createdAt Device timestamp when item was created
 * @param retryCount Number of failed sync attempts
 * @param status Current status of the item
 * @param lastError Last error message if failed
 * @param serverTimestamp Server timestamp after successful sync
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["entityId", "entityType"], unique = false),
        Index(value = ["status"], unique = false),
        Index(value = ["createdAt"], unique = false)
    ]
)
@Serializable
data class SyncQueueItem(
    @PrimaryKey
    val id: String,
    
    val operation: String,
    
    val entityType: String,
    
    val entityId: String,
    
    val payloadJson: String,
    
    val baseVersion: Long = 0,
    
    val createdAt: Long,
    
    val retryCount: Int = 0,
    
    val status: String = SyncStatus.PENDING.name,
    
    val lastError: String? = null,
    
    val serverTimestamp: Long? = null
) {
    companion object {
        /**
         * Create a new sync queue item.
         */
        fun create(
            operation: SyncOperation,
            entityType: SyncEntityType,
            entityId: String,
            payloadJson: String,
            baseVersion: Long = 0
        ): SyncQueueItem {
            return SyncQueueItem(
                id = java.util.UUID.randomUUID().toString(),
                operation = operation.name,
                entityType = entityType.name,
                entityId = entityId,
                payloadJson = payloadJson,
                baseVersion = baseVersion,
                createdAt = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Check if this item is ready to be retried.
     */
    fun canRetry(maxRetries: Int = 3): Boolean {
        return retryCount < maxRetries && status != SyncStatus.IN_FLIGHT.name
    }
    
    /**
     * Create a copy with incremented retry count.
     */
    fun withRetry(error: String): SyncQueueItem {
        return copy(
            retryCount = retryCount + 1,
            status = if (retryCount + 1 >= 3) SyncStatus.FAILED.name else SyncStatus.PENDING.name,
            lastError = error
        )
    }
    
    /**
     * Create a copy marked as in-flight.
     */
    fun markInFlight(): SyncQueueItem {
        return copy(status = SyncStatus.IN_FLIGHT.name)
    }
    
    /**
     * Create a copy marked as synced.
     */
    fun markSynced(serverTs: Long): SyncQueueItem {
        return copy(
            status = SyncStatus.SYNCED.name,
            serverTimestamp = serverTs
        )
    }
    
    /**
     * Create a copy marked as conflict.
     */
    fun markConflict(error: String): SyncQueueItem {
        return copy(
            status = SyncStatus.CONFLICT.name,
            lastError = error
        )
    }
}

/**
 * Conflict record for tracking resolved conflicts.
 * Stores the losing side of a conflict for potential manual recovery.
 */
@Entity(
    tableName = "conflict_archive",
    indices = [
        Index(value = ["entityId"], unique = false),
        Index(value = ["resolvedAt"], unique = false)
    ]
)
@Serializable
data class ConflictRecord(
    @PrimaryKey
    val id: String,
    
    val entityId: String,
    
    val entityType: String,
    
    val localPayloadJson: String,
    
    val serverPayloadJson: String,
    
    val localTimestamp: Long,
    
    val serverTimestamp: Long,
    
    val resolvedAt: Long,
    
    val resolution: String // "SERVER_WINS", "LOCAL_WINS", "MANUAL"
) {
    companion object {
        fun create(
            entityId: String,
            entityType: String,
            localPayload: String,
            serverPayload: String,
            localTs: Long,
            serverTs: Long,
            resolution: String = "SERVER_WINS"
        ): ConflictRecord {
            return ConflictRecord(
                id = java.util.UUID.randomUUID().toString(),
                entityId = entityId,
                entityType = entityType,
                localPayloadJson = localPayload,
                serverPayloadJson = serverPayload,
                localTimestamp = localTs,
                serverTimestamp = serverTs,
                resolvedAt = System.currentTimeMillis(),
                resolution = resolution
            )
        }
    }
}
