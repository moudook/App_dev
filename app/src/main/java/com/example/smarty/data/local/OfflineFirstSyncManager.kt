package com.example.smarty.data.local

import androidx.room.withTransaction
import com.example.smarty.core.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline-First Sync Manager with Intelligent Merge
 * Handles bidirectional sync between Room and Supabase
 */
class OfflineFirstSyncManager(
    private val database: SmartDatabase,
    private val crdtManager: CRDTManager,
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isSyncing = AtomicBoolean(false)
    private val syncQueue = mutableListOf<SyncOperation>()
    
    // Sync state flow for UI
    private val _syncState = MutableStateFlow(SyncStateInfo())
    val syncState: StateFlow<SyncStateInfo> = _syncState.asStateFlow()
    
    /**
     * Queue a sync operation
     */
    fun queueSync(operation: SyncOperation) {
        syncQueue.add(operation)
        trySync()
    }
    
    /**
     * Try to perform sync if not already syncing
     */
    private fun trySync() {
        if (isSyncing.compareAndSet(false, true)) {
            syncScope.launch {
                performSync()
            }
        }
    }
    
    /**
     * Main sync loop
     */
    private suspend fun performSync() {
        try {
            _syncState.update { it.copy(isSyncing = true, lastSyncAttempt = System.currentTimeMillis()) }
            
            // 1. Push local changes to server
            val pushResult = pushLocalChanges()
            
            // 2. Pull remote changes from server
            val pullResult = pullRemoteChanges()
            
            // 3. Resolve any conflicts
            val conflictResult = resolveConflicts(emptyList(), emptyList())
            
            // 4. Update sync state
            _syncState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncSuccess = System.currentTimeMillis(),
                    pushCount = pushResult.successCount,
                    pullCount = pullResult.successCount,
                    conflictCount = conflictResult.resolvedCount,
                    lastError = null,
                )
            }
            
        } catch (e: Exception) {
            _syncState.update {
                it.copy(
                    isSyncing = false,
                    lastError = e.message,
                    errorCount = it.errorCount + 1,
                )
            }
            scheduleRetry()
        } finally {
            isSyncing.set(false)
        }
    }
    
    /**
     * Push local changes to server
     */
    private suspend fun pushLocalChanges(): SyncResult {
        val db = database.smartDao()
        var successCount = 0
        var conflictCount = 0
        
        // Get pending operations from sync queue
        val pendingOps = database.syncQueueDao().getPendingItems(100)
        
        for (op in pendingOps) {
            try {
                when (op.entityType) {
                    "NOTE" -> pushNote(op)
                    "TASK" -> pushTask(op)
                    "TAG" -> pushTag(op)
                    "EVENT" -> pushEvent(op)
                    "CHAT" -> pushChat(op)
                }
                database.syncQueueDao().markSynced(op.id, System.currentTimeMillis())
                successCount++
            } catch (e: Exception) {
                if (isConflict(e)) {
                    conflictCount++
                    handleConflict(op, e)
                } else {
                    database.syncQueueDao().markFailed(op.id, e.message ?: "Unknown error")
                }
            }
        }
        
        return SyncResult(successCount, conflictCount)
    }
    
    /**
     * Pull remote changes from server
     */
    private suspend fun pullRemoteChanges(): SyncResult {
        // This would call Supabase REST API or use Realtime subscription
        // For now, simulate pulling changes
        
        var successCount = 0
        var conflictCount = 0
        
        // Simulate pulling notes
        val remoteNotes = fetchRemoteNotes()
        for (remoteNote in remoteNotes) {
            try {
                val localNote = database.smartDao().getNoteById(remoteNote.id)
                
                if (localNote == null) {
                    // New remote note - insert
                    database.smartDao().insertNote(remoteNote)
                    successCount++
                } else {
                    // Existing note - check for conflicts
                    val localUpdated = localNote.updatedAt
                    val remoteUpdated = remoteNote.updatedAt
                    
                    if (remoteUpdated > localUpdated) {
                        // Remote is newer - merge
                        val vectorClockLocal = crdtManager.getVectorClock(remoteNote.id)
                        val vectorClockRemote = CRDTManager.VectorClock() // Would come from server
                        
                        val resolution = crdtManager.resolveConflict(
                            local = localNote,
                            remote = remoteNote,
                            entityType = "notes",
                            localTimestamp = localUpdated,
                            remoteTimestamp = remoteUpdated,
                            vectorClockLocal = vectorClockLocal,
                            vectorClockRemote = vectorClockRemote,
                        )
                        
                        when (resolution) {
                            is CRDTManager.ConflictResolution.LocalWins -> {
                                // Keep local, will push later
                            }
                            is CRDTManager.ConflictResolution.RemoteWins -> {
                                database.smartDao().updateNote(remoteNote)
                                successCount++
                            }
                            is CRDTManager.ConflictResolution.Merged -> {
                                database.smartDao().updateNote(resolution.data)
                                successCount++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                conflictCount++
            }
        }
        
        return SyncResult(successCount, conflictCount)
    }
    
    /**
     * Resolve conflicts
     */
    private suspend fun resolveConflicts(
        pushConflicts: List<Conflict>,
        pullConflicts: List<Conflict>,
    ): ConflictResolution {
        var resolvedCount = 0
        
        for (conflict in pushConflicts + pullConflicts) {
            try {
                // Archive conflict for manual review
                val conflictRecord = ConflictRecord.create(
                    entityId = conflict.entityId,
                    entityType = conflict.entityType,
                    localPayload = conflict.localData,
                    serverPayload = conflict.remoteData,
                    localTs = conflict.localTimestamp,
                    serverTs = conflict.remoteTimestamp,
                    resolution = "AUTO_MERGED",
                )
                database.syncQueueDao().insertConflict(conflictRecord)
                resolvedCount++
            } catch (e: Exception) {
                // Log error
            }
        }
        
        return ConflictResolution(resolvedCount)
    }
    
    /**
     * Push a note to server
     */
    private suspend fun pushNote(op: SyncQueueItem) {
        // Would call Supabase API
        // Simulate network call
        delay(100)
    }
    
    /**
     * Push a task to server
     */
    private suspend fun pushTask(op: SyncQueueItem) {
        delay(100)
    }
    
    /**
     * Push a tag to server
     */
    private suspend fun pushTag(op: SyncQueueItem) {
        delay(100)
    }
    
    /**
     * Push an event to server
     */
    private suspend fun pushEvent(op: SyncQueueItem) {
        delay(100)
    }
    
    /**
     * Push a chat session to server
     */
    private suspend fun pushChat(op: SyncQueueItem) {
        delay(100)
    }
    
    /**
     * Fetch remote notes from server
     */
    private suspend fun fetchRemoteNotes(): List<Note> {
        // Simulate fetching from server
        delay(200)
        return emptyList()
    }
    
    /**
     * Check if error is a conflict
     */
    private fun isConflict(e: Exception): Boolean {
        return e.message?.contains("conflict", ignoreCase = true) == true
    }
    
    /**
     * Handle conflict
     */
    private suspend fun handleConflict(op: SyncQueueItem, e: Exception) {
        database.syncQueueDao().markConflict(op.id, e.message ?: "Conflict detected")
    }
    
    /**
     * Schedule retry for failed operations
     */
    private fun scheduleRetry() {
        syncScope.launch {
            delay(5000) // Wait 5 seconds
            trySync()
        }
    }
    
    /**
     * Perform immediate sync
     */
    suspend fun syncNow(): Boolean {
        return try {
            performSync()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Start periodic sync
     */
    fun startPeriodicSync(intervalMs: Long = 30000) {
        syncScope.launch {
            while (isActive) {
                delay(intervalMs)
                trySync()
            }
        }
    }
    
    /**
     * Stop sync
     */
    fun stopSync() {
        syncScope.cancel()
    }
    
    /**
     * Sync operation types
     */
    sealed class SyncOperation {
        data class Create(val entityType: String, val entityId: String, val data: Any) : SyncOperation()
        data class Update(val entityType: String, val entityId: String, val data: Any) : SyncOperation()
        data class Delete(val entityType: String, val entityId: String) : SyncOperation()
    }
    
    /**
     * Sync result
     */
    data class SyncResult(
        val successCount: Int,
        val conflictCount: Int,
    )
    
    /**
     * Conflict info
     */
    data class Conflict(
        val entityId: String,
        val entityType: String,
        val localData: String,
        val remoteData: String,
        val localTimestamp: Long,
        val remoteTimestamp: Long,
    )
    
    /**
     * Conflict resolution result
     */
    data class ConflictResolution(
        val resolvedCount: Int,
    )
    
    /**
     * Sync state information
     */
    data class SyncStateInfo(
        val isSyncing: Boolean = false,
        val lastSyncAttempt: Long = 0,
        val lastSyncSuccess: Long = 0,
        val pushCount: Int = 0,
        val pullCount: Int = 0,
        val conflictCount: Int = 0,
        val errorCount: Int = 0,
        val lastError: String? = null,
    )
}