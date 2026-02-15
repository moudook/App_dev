package com.example.smarty.data.sync

import android.util.Log
import com.example.smarty.core.domain.model.getTags
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteAgentService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * =============================================================================
 * SYNC MANAGER - Cloud-First Synchronization with LWW Conflict Resolution
 * =============================================================================
 *
 * Manages synchronization between local Room database and remote server.
 *
 * ARCHITECTURE:
 * - Server is the single source of truth (PostgreSQL)
 * - Room DB is a read-optimized cache
 * - All writes go through sync queue
 * - Conflict resolution: Last-Write-Wins (LWW) with server timestamp
 *
 * SYNC FLOW:
 * 1. Local write -> Room DB (optimistic) -> SyncQueue (pending)
 * 2. SyncWorker drains queue -> Server API
 * 3. Server responds with serverTimestamp
 * 4. Local entity updated with serverTimestamp, status=SYNCED
 *
 * CONFLICT RESOLUTION (LWW):
 * - Server timestamp is authoritative
 * - If local write has baseVersion < current server version -> CONFLICT
 * - Resolution: Server version wins, local changes archived to conflict_archive
 *
 * =============================================================================
 */

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    data class Success(val serverTimestamp: Long) : SyncResult()
    data class Conflict(val serverData: String, val localData: String) : SyncResult()
    data class Error(val message: String, val retryable: Boolean) : SyncResult()
}

/**
 * Sync status for UI display.
 */
data class SyncStatus(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val conflictCount: Int = 0,
    val lastSyncTime: Long = 0,
    val lastError: String? = null
) {
    val isSynced: Boolean get() = pendingCount == 0 && failedCount == 0 && conflictCount == 0
    val hasIssues: Boolean get() = failedCount > 0 || conflictCount > 0
}

/**
 * Manages synchronization between local database and server.
 */
class SyncManager(
    private val syncQueueDao: SyncQueueDao,
    private val noteDao: NoteDao,
    private val calendarDao: CalendarDao,
    private val remoteAgentService: RemoteAgentService,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_RETRIES = 3
        private const val BATCH_SIZE = 50
        private const val SYNC_INTERVAL_MS = 30_000L // 30 seconds
    }

    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var syncJob: Job? = null

    /**
     * Start periodic sync.
     */
    fun startPeriodicSync() {
        if (syncJob?.isActive == true) return

        syncJob = syncScope.launch {
            while (isActive) {
                try {
                    processSyncQueue()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error: ${e.message}", e)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Periodic sync started")
    }

    /**
     * Stop periodic sync.
     */
    fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
        Log.i(TAG, "Periodic sync stopped")
    }

    /**
     * Force immediate sync.
     */
    suspend fun syncNow(): Boolean {
        return try {
            processSyncQueue()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed: ${e.message}", e)
            false
        }
    }

    /**
     * Enqueue a note operation for sync.
     */
    suspend fun enqueueNoteOperation(
        operation: SyncOperation,
        note: com.example.smarty.core.domain.model.Note
    ) {
        val payload = json.encodeToString(
            kotlinx.serialization.serializer(),
            NotePayload.fromNote(note)
        )

        val item = SyncQueueItem.create(
            operation = operation,
            entityType = SyncEntityType.NOTE,
            entityId = note.id,
            payloadJson = payload,
            baseVersion = note.updatedAt
        )

        syncQueueDao.insert(item)
        updateStatus()
        Log.d(TAG, "Enqueued ${operation.name} for note ${note.id}")
    }

    /**
     * Enqueue an event operation for sync.
     */
    suspend fun enqueueEventOperation(
        operation: SyncOperation,
        event: com.example.smarty.core.domain.model.CalendarEvent
    ) {
        val payload = json.encodeToString(
            kotlinx.serialization.serializer(),
            EventPayload.fromEvent(event)
        )

        val item = SyncQueueItem.create(
            operation = operation,
            entityType = SyncEntityType.EVENT,
            entityId = event.id,
            payloadJson = payload,
            baseVersion = event.updatedAt
        )

        syncQueueDao.insert(item)
        updateStatus()
        Log.d(TAG, "Enqueued ${operation.name} for event ${event.id}")
    }

    /**
     * Process all pending items in the sync queue.
     */
    private suspend fun processSyncQueue() {
        // Update status to syncing
        _syncStatus.update { it.copy(isSyncing = true) }

        try {
            // Get pending items
            val pendingItems = syncQueueDao.getPendingItems(BATCH_SIZE)

            if (pendingItems.isEmpty()) {
                _syncStatus.update { it.copy(isSyncing = false, lastSyncTime = System.currentTimeMillis()) }
                return
            }

            Log.i(TAG, "Processing ${pendingItems.size} sync items")

            // Process each item
            pendingItems.forEach { item ->
                processSyncItem(item)
            }

            // Clean up synced items older than 7 days
            syncQueueDao.deleteSyncedItems()

        } finally {
            updateStatus()
            _syncStatus.update { it.copy(isSyncing = false, lastSyncTime = System.currentTimeMillis()) }
        }
    }

    /**
     * Process a single sync queue item.
     */
    private suspend fun processSyncItem(item: SyncQueueItem) {
        // Mark as in-flight
        syncQueueDao.markInFlight(item.id)

        try {
            val result = when (item.entityType) {
                SyncEntityType.NOTE.name -> processNoteSync(item)
                SyncEntityType.EVENT.name -> processEventSync(item)
                else -> {
                    Log.w(TAG, "Unknown entity type: ${item.entityType}")
                    SyncResult.Error("Unknown entity type", false)
                }
            }

            when (result) {
                is SyncResult.Success -> {
                    syncQueueDao.markSynced(item.id, result.serverTimestamp)
                    Log.d(TAG, "Synced ${item.entityType} ${item.entityId}")
                }
                is SyncResult.Conflict -> {
                    syncQueueDao.markConflict(item.id, "Server version is newer")
                    archiveConflict(item, result.serverData, result.localData)
                    Log.w(TAG, "Conflict for ${item.entityType} ${item.entityId}")
                }
                is SyncResult.Error -> {
                    if (result.retryable && item.canRetry(MAX_RETRIES)) {
                        syncQueueDao.markFailed(item.id, result.message)
                        Log.w(TAG, "Retryable error for ${item.entityId}: ${result.message}")
                    } else {
                        syncQueueDao.markFailed(item.id, result.message)
                        Log.e(TAG, "Permanent error for ${item.entityId}: ${result.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing ${item.id}", e)
            if (item.canRetry(MAX_RETRIES)) {
                syncQueueDao.markFailed(item.id, e.message ?: "Unknown error")
            } else {
                syncQueueDao.markFailed(item.id, "Max retries exceeded: ${e.message}")
            }
        }
    }

    /**
     * Process a note sync operation.
     */
    private suspend fun processNoteSync(item: SyncQueueItem): SyncResult {
        return try {
            val payload = json.decodeFromString<NotePayload>(item.payloadJson)

            // TODO: Call actual server API
            // For now, simulate success with server timestamp
            val serverTimestamp = System.currentTimeMillis()

            // Update local note with server timestamp
            val note = noteDao.getNoteById(item.entityId)
            if (note != null) {
                noteDao.updateNote(note.copy(updatedAt = serverTimestamp))
            }

            SyncResult.Success(serverTimestamp)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error", true)
        }
    }

    /**
     * Process an event sync operation.
     */
    private suspend fun processEventSync(item: SyncQueueItem): SyncResult {
        return try {
            val payload = json.decodeFromString<EventPayload>(item.payloadJson)

            // TODO: Call actual server API
            val serverTimestamp = System.currentTimeMillis()

            // Update local event with server timestamp
            val event = calendarDao.getEventById(item.entityId)
            if (event != null) {
                calendarDao.updateEvent(event.copy(updatedAt = serverTimestamp))
            }

            SyncResult.Success(serverTimestamp)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error", true)
        }
    }

    /**
     * Archive a conflict for potential manual recovery.
     */
    private suspend fun archiveConflict(
        item: SyncQueueItem,
        serverData: String,
        localData: String
    ) {
        val record = ConflictRecord.create(
            entityId = item.entityId,
            entityType = item.entityType,
            localPayload = localData,
            serverPayload = serverData,
            localTs = item.baseVersion,
            serverTs = System.currentTimeMillis(),
            resolution = "SERVER_WINS"
        )
        syncQueueDao.insertConflict(record)
    }

    /**
     * Update sync status from database.
     */
    private suspend fun updateStatus() {
        val summary = syncQueueDao.getSyncStatusSummary()
        _syncStatus.update {
            it.copy(
                pendingCount = summary.pending,
                failedCount = summary.failed,
                conflictCount = summary.conflicted
            )
        }
    }

    /**
     * Reset failed items for retry.
     */
    suspend fun retryFailed() {
        syncQueueDao.resetFailedItems()
        updateStatus()
        Log.i(TAG, "Reset failed items for retry")
    }

    /**
     * Clear all sync data (for testing or reset).
     */
    suspend fun clearAll() {
        syncQueueDao.clearAll()
        syncQueueDao.clearAllConflicts()
        updateStatus()
    }
}

// =============================================================================
// PAYLOAD MODELS FOR SERIALIZATION
// =============================================================================

@Serializable
data class NotePayload(
    val id: String,
    val title: String,
    val content: String,
    val summary: String?,
    val categoryName: String?,
    val tags: List<String>,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isFullPrivacy: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        fun fromNote(note: com.example.smarty.core.domain.model.Note): NotePayload {
            return NotePayload(
                id = note.id,
                title = note.title,
                content = note.content,
                summary = note.summary,
                categoryName = note.categoryName,
                tags = note.getTags(),
                isPinned = note.isPinned,
                isArchived = note.isArchived,
                isFullPrivacy = note.isFullPrivacy,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt
            )
        }
    }
}

@Serializable
data class EventPayload(
    val id: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val isAllDay: Boolean,
    val location: String?,
    val reminderMinutes: Int?,
    val googleEventId: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        fun fromEvent(event: com.example.smarty.core.domain.model.CalendarEvent): EventPayload {
            return EventPayload(
                id = event.id,
                title = event.title,
                description = event.description,
                startTime = event.startTime,
                endTime = event.endTime,
                isAllDay = event.isAllDay,
                location = event.location,
                reminderMinutes = event.reminderMinutes,
                googleEventId = event.googleEventId,
                createdAt = event.createdAt,
                updatedAt = event.updatedAt
            )
        }
    }
}
