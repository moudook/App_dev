package com.example.smarty.data.repository

import android.util.Log
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.data.sync.SyncCoordinator
import com.example.smarty.data.sync.OfflineQueue
import com.example.smarty.protocol.NoteInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class ServerSyncRepository(
    private val remoteDataSource: RemoteDataSource,
    private val eventSink: com.example.smarty.core.common.worker.BackgroundAgentEventSink,
    private val syncCoordinator: SyncCoordinator,
    private val offlineQueue: OfflineQueue
) : SyncRepository {
    companion object {
        private const val TAG = "ServerSyncRepo"
        private const val MIN_SYNC_INTERVAL_MS = 30_000L
    }

    private var currentUserId: String? = null
    private val lastSyncTime = AtomicLong(0)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _remoteNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _remoteCategories = MutableStateFlow<List<Category>>(emptyList())

    override fun initializeForUser(userId: String) {
        currentUserId = userId
        Log.d(TAG, "Initialized Server Sync for user: $userId")
        
        scope.launch {
            refreshData()
        }
        
        scope.launch {
            eventSink.syncEvents.collect { syncType ->
                Log.d(TAG, "Received sync trigger: $syncType")
                refreshData()
            }
        }
    }

    override suspend fun syncNote(note: Note): Result<Unit> {
        return try {
            if (note.isFullPrivacy) {
                Log.d(TAG, "Skipping sync for private note ${note.id}")
                return Result.success(Unit)
            }

            val success = if (note.isArchived) {
                remoteDataSource.deleteNote(note.id)
            } else {
                val existingId = remoteDataSource.createNote(note.title, note.content, note.categoryName)
                if (existingId == null) {
                    remoteDataSource.updateNote(note.id, note.title, note.content, note.categoryName)
                } else {
                    true
                }
            }

            if (success) {
                Log.d(TAG, "Synced note ${note.id} to server")
                Result.success(Unit)
            } else {
                offlineQueue.enqueueNoteUpdate(note)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync note ${note.id}", e)
            offlineQueue.enqueueNoteUpdate(note)
            Result.success(Unit)
        }
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> {
        return try {
            val success = remoteDataSource.deleteNote(noteId)
            if (success) {
                Log.d(TAG, "Deleted note $noteId from server")
                Result.success(Unit)
            } else {
                offlineQueue.enqueueNoteDelete(noteId)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete note $noteId", e)
            offlineQueue.enqueueNoteDelete(noteId)
            Result.success(Unit)
        }
    }

    override suspend fun syncCategory(category: Category): Result<Unit> {
        Log.d(TAG, "Category sync not implemented for server - categories derived from notes")
        return Result.success(Unit)
    }

    override suspend fun deleteCategory(categoryId: String): Result<Unit> {
        Log.d(TAG, "Category delete not implemented for server - categories derived from notes")
        return Result.success(Unit)
    }

    override fun getRemoteNotesFlow(): Flow<List<Note>> = _remoteNotes.asStateFlow()

    override fun getRemoteCategoriesFlow(): Flow<List<Category>> = _remoteCategories.asStateFlow()

    suspend fun refreshData() {
        val now = System.currentTimeMillis()
        if (now - lastSyncTime.get() < MIN_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Sync interval too short, skipping refresh")
            return
        }
        
        Log.d(TAG, "Refreshing data from server...")
        try {
            val noteInfos = remoteDataSource.fetchNotes()
            val notes = noteInfos.map { mapToNote(it) }
            _remoteNotes.value = notes
            
            lastSyncTime.set(now)
            Log.d(TAG, "Refreshed ${notes.size} notes from server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh data", e)
        }
    }

    suspend fun pullFromServer() {
        Log.d(TAG, "Pulling all data from server...")
        when (val result = syncCoordinator.pullFromServer()) {
            is com.example.smarty.data.sync.PullResult.Success -> {
                Log.d(TAG, "Pull complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
            }
            is com.example.smarty.data.sync.PullResult.Offline -> {
                Log.d(TAG, "Pull skipped: offline")
            }
            is com.example.smarty.data.sync.PullResult.Error -> {
                Log.e(TAG, "Pull failed: ${result.message}")
            }
        }
    }

    suspend fun pushPendingChanges() {
        Log.d(TAG, "Pushing pending changes to server...")
        when (val result = syncCoordinator.pushPendingChanges()) {
            is com.example.smarty.data.sync.PushResult.Success -> {
                Log.d(TAG, "Push complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
            }
            is com.example.smarty.data.sync.PushResult.Offline -> {
                Log.d(TAG, "Push skipped: offline")
            }
            is com.example.smarty.data.sync.PushResult.Error -> {
                Log.e(TAG, "Push failed: ${result.message}")
            }
        }
    }

    private fun mapToNote(info: NoteInfo): Note {
        return Note(
            id = info.id,
            title = info.title,
            content = info.content,
            categoryName = info.category,
            isArchived = info.isArchived,
            createdAt = info.createdAt,
            updatedAt = info.updatedAt,
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isAiCreated = true
        )
    }
}
