package com.example.smarty.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * =============================================================================
 * SYNC QUEUE - Offline Write Queue for Cloud-First Architecture
 * =============================================================================
 */

/**
 * Types of operations that can be synced.
 */
enum class SyncOperation {
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * Types of entities that can be synced.
 */
enum class SyncEntityType {
    NOTE,
    CATEGORY,
    EVENT,
    MEMORY,
    CHAT_SESSION,
}

/**
 * Status of a sync queue item.
 */
enum class SyncStatus {
    PENDING, // Queued, waiting to be sent
    IN_FLIGHT, // Currently being sent to server
    SYNCED, // Successfully synced to server
    FAILED, // Failed after max retries
    CONFLICT, // Conflict detected, needs resolution
}

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["entityType"], unique = false),
        Index(value = ["status"], unique = false),
        Index(value = ["createdAt"], unique = false),
    ],
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
    val serverTimestamp: Long? = null,
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
            baseVersion: Long = 0,
        ): SyncQueueItem {
            return SyncQueueItem(
                id = java.util.UUID.randomUUID().toString(),
                operation = operation.name,
                entityType = entityType.name,
                entityId = entityId,
                payloadJson = payloadJson,
                baseVersion = baseVersion,
                createdAt = System.currentTimeMillis(),
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
            lastError = error,
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
            serverTimestamp = serverTs,
        )
    }

    /**
     * Create a copy marked as conflict.
     */
    fun markConflict(error: String): SyncQueueItem {
        return copy(
            status = SyncStatus.CONFLICT.name,
            lastError = error,
        )
    }
}

/**
 * Conflict record for tracking resolved conflicts.
 * Stores the losing side of a conflict for potential manual recovery.
 */
@Entity(
    tableName = "conflict_records",
    indices = [
        Index(value = ["entityId"], unique = false),
        Index(value = ["resolvedAt"], unique = false),
    ],
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
    val resolution: String, // "SERVER_WINS", "LOCAL_WINS", "MANUAL"
) {
    companion object {
        fun create(
            entityId: String,
            entityType: String,
            localPayload: String,
            serverPayload: String,
            localTs: Long,
            serverTs: Long,
            resolution: String = "SERVER_WINS",
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
                resolution = resolution,
            )
        }
    }
}
