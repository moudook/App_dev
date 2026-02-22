package com.example.smarty.data.sync

import android.util.Log
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OfflineQueue(
    private val syncQueueDao: SyncQueueDao
) {
    val pendingCount: Flow<Int> = syncQueueDao.getPendingCountFlow()
    
    val pendingItems: Flow<List<SyncQueueItem>> = syncQueueDao.getPendingItemsFlow()

    suspend fun enqueueNoteCreate(note: Note) {
        enqueueOperation(SyncOperation.CREATE, SyncEntityType.NOTE, note.id, note.updatedAt)
    }

    suspend fun enqueueNoteUpdate(note: Note) {
        enqueueOperation(SyncOperation.UPDATE, SyncEntityType.NOTE, note.id, note.updatedAt)
    }

    suspend fun enqueueNoteDelete(noteId: String) {
        enqueueOperation(SyncOperation.DELETE, SyncEntityType.NOTE, noteId, System.currentTimeMillis())
        syncQueueDao.deleteForEntity(noteId, SyncEntityType.NOTE.name)
    }

    suspend fun enqueueEventCreate(event: CalendarEvent) {
        enqueueOperation(SyncOperation.CREATE, SyncEntityType.EVENT, event.id, event.updatedAt)
    }

    suspend fun enqueueEventUpdate(event: CalendarEvent) {
        enqueueOperation(SyncOperation.UPDATE, SyncEntityType.EVENT, event.id, event.updatedAt)
    }

    suspend fun enqueueEventDelete(eventId: String) {
        enqueueOperation(SyncOperation.DELETE, SyncEntityType.EVENT, eventId, System.currentTimeMillis())
        syncQueueDao.deleteForEntity(eventId, SyncEntityType.EVENT.name)
    }

    private suspend fun enqueueOperation(
        operation: SyncOperation,
        entityType: SyncEntityType,
        entityId: String,
        version: Long
    ) {
        val item = SyncQueueItem.create(
            operation = operation,
            entityType = entityType,
            entityId = entityId,
            payloadJson = "{}",
            baseVersion = version
        )
        syncQueueDao.insert(item)
        Log.d(TAG, "Enqueued ${operation.name} for ${entityType.name} $entityId")
    }

    suspend fun getPendingCount(): Int = syncQueueDao.getPendingCount()
    
    suspend fun getFailedCount(): Int = syncQueueDao.getFailedCount()
    
    suspend fun getConflictCount(): Int = syncQueueDao.getConflictCount()

    suspend fun retryFailed() {
        syncQueueDao.resetFailedItems()
        Log.i(TAG, "Reset failed items for retry")
    }

    suspend fun clearSynced() {
        syncQueueDao.deleteSyncedItems()
        Log.i(TAG, "Cleared synced items")
    }

    companion object {
        private const val TAG = "OfflineQueue"
    }
}
