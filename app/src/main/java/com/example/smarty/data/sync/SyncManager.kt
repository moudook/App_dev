package com.example.smarty.data.sync

import android.util.Log
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * =============================================================================
 * SYNC MANAGER - Cloud-First Synchronization with LWW Conflict Resolution
 * =============================================================================
 *
 * Manages synchronization between local Room database and remote server.
 */

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    data class Success(
        val serverTimestamp: Long,
    ) : SyncResult()

    data class Conflict(
        val serverData: String,
        val localData: String,
    ) : SyncResult()

    data class Error(
        val message: String,
        val retryable: Boolean,
    ) : SyncResult()
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
    val lastError: String? = null,
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
    private val json: Json = Json { ignoreUnknownKeys = true },
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

        syncJob =
            syncScope.launch {
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
    suspend fun syncNow(): Boolean =
        try {
            processSyncQueue()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Force sync failed: ${e.message}", e)
            false
        }

    /**
     * Enqueue a note operation for sync.
     */
    suspend fun enqueueNoteOperation(
        operation: SyncOperation,
        note: com.example.smarty.core.domain.model.Note,
    ) {
        val payload =
            json.encodeToString(
                kotlinx.serialization.serializer(),
                NotePayload.fromNote(note),
            )

        val item =
            SyncQueueItem.create(
                operation = operation,
                entityType = SyncEntityType.NOTE,
                entityId = note.id,
                payloadJson = payload,
                baseVersion = note.updatedAt,
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
        event: com.example.smarty.core.domain.model.CalendarEvent,
    ) {
        val payload =
            json.encodeToString(
                kotlinx.serialization.serializer(),
                EventPayload.fromEvent(event),
            )

        val item =
            SyncQueueItem.create(
                operation = operation,
                entityType = SyncEntityType.EVENT,
                entityId = event.id,
                payloadJson = payload,
                baseVersion = event.updatedAt,
            )

        syncQueueDao.insert(item)
        updateStatus()
        Log.d(TAG, "Enqueued ${operation.name} for event ${event.id}")
    }

    /**
     * Process all pending items in the sync queue.
     */
    private suspend fun processSyncQueue() {
        _syncStatus.update { it.copy(isSyncing = true) }

        try {
            val pendingItems = syncQueueDao.getPendingItems(BATCH_SIZE)

            if (pendingItems.isEmpty()) {
                _syncStatus.update { it.copy(isSyncing = false, lastSyncTime = System.currentTimeMillis()) }
                return
            }

            Log.i(TAG, "Processing ${pendingItems.size} sync items")

            val notesToPush = mutableListOf<com.example.smarty.protocol.NotePushItem>()
            val sessionsToPush = mutableListOf<com.example.smarty.protocol.SessionPushItem>()
            val eventsToPush = mutableListOf<com.example.smarty.protocol.EventPushItem>()

            pendingItems.forEach { item ->
                syncQueueDao.markInFlight(item.id)
                try {
                    when (item.entityType) {
                        SyncEntityType.NOTE.name -> {
                            val payload = json.decodeFromString<NotePayload>(item.payloadJson)
                            notesToPush.add(payload.toPushItem())
                        }
                        SyncEntityType.EVENT.name -> {
                            val payload = json.decodeFromString<EventPayload>(item.payloadJson)
                            eventsToPush.add(payload.toPushItem())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse payload for ${item.id}", e)
                    syncQueueDao.markFailed(item.id, "Parse error: ${e.message}")
                }
            }

            if (notesToPush.isNotEmpty() || sessionsToPush.isNotEmpty() || eventsToPush.isNotEmpty()) {
                val request =
                    com.example.smarty.protocol.SyncPushRequest(
                        notes = notesToPush.ifEmpty { null },
                        sessions = sessionsToPush.ifEmpty { null },
                        events = eventsToPush.ifEmpty { null },
                    )

                val response = remoteAgentService.pushSync(request)

                if (response?.success == true) {
                    val now = System.currentTimeMillis()
                    pendingItems.forEach { item ->
                        syncQueueDao.markSynced(item.id, now)
                    }
                    Log.i(TAG, "Batch sync successful: ${pendingItems.size} items")
                } else {
                    val errorMsg = response?.errors?.joinToString() ?: "Unknown server error"
                    pendingItems.forEach { item ->
                        syncQueueDao.markFailed(item.id, errorMsg)
                    }
                    Log.e(TAG, "Batch sync failed: $errorMsg")
                }
            }

            // Cleanup synced items
            syncQueueDao.deleteSyncedItems()
        } finally {
            updateStatus()
            _syncStatus.update { it.copy(isSyncing = false, lastSyncTime = System.currentTimeMillis()) }
        }
    }

    private suspend fun updateStatus() {
        val summary = syncQueueDao.getSyncStatusSummary()
        _syncStatus.update {
            it.copy(
                pendingCount = summary.pending,
                failedCount = summary.failed,
                conflictCount = summary.conflicted,
            )
        }
    }

    suspend fun retryFailed() {
        syncQueueDao.resetFailedItems()
        updateStatus()
        Log.i(TAG, "Reset failed items for retry")
    }

    suspend fun clearAll() {
        syncQueueDao.clearAll()
        syncQueueDao.clearAllConflicts()
        updateStatus()
    }
}

// =============================================================================
// PAYLOAD MODELS
// =============================================================================

@Serializable
data class NotePayload(
    val id: String,
    val title: String,
    val content: String,
    val summary: String?,
    val sourceUrl: String?,
    val imageUri: String?,
    val fileUri: String?,
    val fileName: String?,
    val fileMimeType: String?,
    val fileSize: Long?,
    val type: String,
    val categoryId: String?,
    val categoryName: String?,
    val stackId: String?,
    val parentNoteId: String?,
    val whySaved: String?,
    val processingStatus: String,
    val isArchived: Boolean,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isFullPrivacy: Boolean,
    val excludeFromAiChat: Boolean,
    val isAiCreated: Boolean,
    val isViewed: Boolean,
    val todoContent: String?,
    val attachmentsJson: String?,
    val tagsJson: String?,
    val chunkAnalysesJson: String?,
    val reminderText: String?,
    val reminderExpiresAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toPushItem(): com.example.smarty.protocol.NotePushItem =
        com.example.smarty.protocol.NotePushItem(
            id = id,
            title = title,
            content = content,
            summary = summary,
            sourceUrl = sourceUrl,
            imageUri = imageUri,
            fileUri = fileUri,
            fileName = fileName,
            fileMimeType = fileMimeType,
            fileSize = fileSize,
            type = type,
            categoryId = categoryId,
            categoryName = categoryName,
            stackId = stackId,
            parentNoteId = parentNoteId,
            whySaved = whySaved,
            processingStatus = processingStatus,
            isArchived = isArchived,
            isPinned = isPinned,
            isFavorite = isFavorite,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isAiCreated = isAiCreated,
            isViewed = isViewed,
            todoContent = todoContent,
            attachmentsJson = attachmentsJson,
            tagsJson = tagsJson,
            chunkAnalysesJson = chunkAnalysesJson,
            reminderText = reminderText,
            reminderExpiresAt = reminderExpiresAt,
            updatedAt = updatedAt,
        )

    companion object {
        fun fromNote(note: com.example.smarty.core.domain.model.Note): NotePayload =
            NotePayload(
                id = note.id,
                title = note.title,
                content = note.content,
                summary = note.summary,
                sourceUrl = note.sourceUrl,
                imageUri = note.imageUri,
                fileUri = note.fileUri,
                fileName = note.fileName,
                fileMimeType = note.fileMimeType,
                fileSize = note.fileSize,
                type = note.type.name,
                categoryId = note.categoryId,
                categoryName = note.categoryName,
                stackId = note.stackId,
                parentNoteId = note.parentNoteId,
                whySaved = note.whySaved,
                processingStatus = note.processingStatus.name,
                isArchived = note.isArchived,
                isPinned = note.isPinned,
                isFavorite = note.isFavorite,
                isFullPrivacy = note.isFullPrivacy,
                excludeFromAiChat = note.excludeFromAiChat,
                isAiCreated = note.isAiCreated,
                isViewed = note.isViewed,
                todoContent = note.todoContent,
                attachmentsJson = note.attachmentsJson,
                tagsJson = note.tagsJson,
                chunkAnalysesJson = note.chunkAnalysesJson,
                reminderText = note.reminderText,
                reminderExpiresAt = note.reminderExpiresAt,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
            )
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
    val linkedNoteId: String?,
    val googleEventId: String?,
    val isEventPrivate: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toPushItem(): com.example.smarty.protocol.EventPushItem =
        com.example.smarty.protocol.EventPushItem(
            id = id,
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            location = location,
            reminderMinutes = reminderMinutes ?: 15,
            linkedNoteId = linkedNoteId,
            googleEventId = googleEventId,
            isEventPrivate = isEventPrivate,
        )

    companion object {
        fun fromEvent(event: com.example.smarty.core.domain.model.CalendarEvent): EventPayload =
            EventPayload(
                id = event.id,
                title = event.title,
                description = event.description,
                startTime = event.startTime,
                endTime = event.endTime,
                isAllDay = event.isAllDay,
                location = event.location,
                reminderMinutes = event.reminderMinutes,
                linkedNoteId = event.linkedNoteId,
                googleEventId = event.googleEventId,
                isEventPrivate = event.isEventPrivate,
                createdAt = event.createdAt,
                updatedAt = event.updatedAt,
            )
    }
}
