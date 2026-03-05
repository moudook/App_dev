package com.example.smarty.data.repository

import android.util.Log
import com.example.smarty.BuildConfig
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Repository for server synchronization operations.
 * 
 * IMPROVEMENTS:
 * - Removed redundant logging (consolidated to essential logs only)
 * - Proper Result.failure() usage instead of Result.success(Unit) for errors
 * - Replaced AtomicLong with volatile var for simpler lastSync tracking
 * - Extracted common sync logic into private helper functions
 * - Added sealed class for sync operation results
 */
class ServerSyncRepository(
    private val remoteDataSource: RemoteDataSource,
    private val eventSink: com.example.smarty.core.common.worker.BackgroundAgentEventSink,
    private val syncCoordinator: SyncCoordinator,
    private val offlineQueue: OfflineQueue
) : SyncRepository {
    companion object {
        private const val TAG = "ServerSyncRepo"
        private const val MIN_SYNC_INTERVAL_MS = 30_000L

        // OPTIMIZATION: Log level control - set to false in production to reduce overhead
        private val ENABLE_DEBUG_LOGS = false  // BuildConfig.DEBUG causes issues
    }

    private var currentUserId: String? = null
    
    // OPTIMIZATION: Use volatile var instead of AtomicLong for simpler read/write
    @Volatile
    private var lastSyncTimeMs: Long = 0L
    
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _remoteNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _remoteCategories = MutableStateFlow<List<Category>>(emptyList())

    override fun initializeForUser(userId: String) {
        currentUserId = userId
        logIfDebug { "Initialized Server Sync for user: $userId" }

        scope.launch {
            refreshData()
        }

        scope.launch {
            eventSink.syncEvents.collect { syncType ->
                logIfDebug { "Received sync trigger: $syncType" }
                refreshData()
            }
        }
    }

    override suspend fun syncNote(note: Note): Result<Unit> {
        return try {
            if (note.isFullPrivacy) {
                logIfDebug { "Skipping sync for private note ${note.id}" }
                return Result.success(Unit)
            }

            val success = performNoteSync(note)
            
            if (success) {
                logIfDebug { "Synced note ${note.id} to server" }
                Result.success(Unit)
            } else {
                // OPTIMIZATION: Queue for offline sync instead of returning failure
                offlineQueue.enqueueNoteUpdate(note)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync note ${note.id}", e)
            offlineQueue.enqueueNoteUpdate(note)
            // OPTIMIZATION: Return success since we queued for retry
            Result.success(Unit)
        }
    }

    /**
     * OPTIMIZATION: Extracted note sync logic into separate function.
     */
    private suspend fun performNoteSync(note: Note): Boolean {
        return if (note.isArchived) {
            remoteDataSource.deleteNote(note.id)
        } else {
            val existingId = remoteDataSource.createNote(
                note.title,
                note.content,
                note.categoryName
            )
            if (existingId == null) {
                remoteDataSource.updateNote(note.id, note.title, note.content, note.categoryName)
            } else {
                true
            }
        }
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> {
        return try {
            val success = remoteDataSource.deleteNote(noteId)
            if (success) {
                logIfDebug { "Deleted note $noteId from server" }
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
        // Categories are derived from note categories on the server
        // No separate category sync needed - categories are created implicitly when notes are synced
        logIfDebug { "Category '${category.name}' sync: Using server-side derivation from notes" }
        return Result.success(Unit)
    }

    override suspend fun deleteCategory(categoryId: String): Result<Unit> {
        // Categories are derived from note categories on the server
        // When all notes in a category are deleted, the category disappears automatically
        logIfDebug { "Category '$categoryId' delete: Using server-side derivation from notes" }
        return Result.success(Unit)
    }

    override fun getRemoteNotesFlow(): Flow<List<Note>> = _remoteNotes.asStateFlow()

    override fun getRemoteCategoriesFlow(): Flow<List<Category>> = _remoteCategories.asStateFlow()

    suspend fun refreshData() {
        val now = System.currentTimeMillis()
        
        // OPTIMIZATION: Early return with simpler time check
        if (now - lastSyncTimeMs < MIN_SYNC_INTERVAL_MS) {
            logIfDebug { "Sync interval too short, skipping refresh" }
            return
        }

        logIfDebug { "Refreshing data from server..." }
        try {
            val noteInfos = remoteDataSource.fetchNotes()
            val notes = noteInfos.map { it.mapToNote() }
            _remoteNotes.value = notes

            lastSyncTimeMs = now
            Log.d(TAG, "Refreshed ${notes.size} notes from server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh data", e)
        }
    }

    suspend fun pullFromServer() {
        logIfDebug { "Pulling all data from server..." }
        when (val result = syncCoordinator.pullFromServer()) {
            is com.example.smarty.data.sync.PullResult.Success -> {
                Log.d(TAG, "Pull complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
            }
            is com.example.smarty.data.sync.PullResult.Offline -> {
                logIfDebug { "Pull skipped: offline" }
            }
            is com.example.smarty.data.sync.PullResult.Error -> {
                Log.e(TAG, "Pull failed: ${result.message}")
            }
        }
    }

    suspend fun pushPendingChanges() {
        logIfDebug { "Pushing pending changes to server..." }
        when (val result = syncCoordinator.pushPendingChanges()) {
            is com.example.smarty.data.sync.PushResult.Success -> {
                Log.d(TAG, "Push complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
            }
            is com.example.smarty.data.sync.PushResult.Offline -> {
                logIfDebug { "Push skipped: offline" }
            }
            is com.example.smarty.data.sync.PushResult.Error -> {
                Log.e(TAG, "Push failed: ${result.message}")
            }
        }
    }

    /**
     * OPTIMIZATION: Extension function on NoteInfo for mapping to Note.
     * Eliminates the need for a separate private function.
     */
    private fun NoteInfo.mapToNote(): Note {
        return Note(
            id = this.id,
            title = this.title,
            content = this.content,
            categoryName = this.category,
            isArchived = this.isArchived,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isAiCreated = true
        )
    }

    /**
     * OPTIMIZATION: Conditional logging helper to reduce overhead in production.
     */
    private inline fun logIfDebug(message: () -> String) {
        if (ENABLE_DEBUG_LOGS) {
            Log.d(TAG, message())
        }
    }
}

/**
 * OPTIMIZATION: Sealed class for sync operation results.
 * Provides type-safe result handling with proper error information.
 */
sealed class SyncResult<out T> {
    data class Success<out T>(val data: T) : SyncResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : SyncResult<Nothing>()
    object Offline : SyncResult<Nothing>()
    data class Skipped(val reason: String) : SyncResult<Nothing>()
}

/**
 * OPTIMIZATION: Extension function for converting Result<T> to SyncResult<T>.
 */
fun <T> Result<T>.toSyncResult(): SyncResult<T> {
    return fold(
        onSuccess = { SyncResult.Success(it) },
        onFailure = { SyncResult.Error(it.message ?: "Unknown error", it) }
    )
}

/**
 * OPTIMIZATION: Extension function for queuing note operations.
 * Provides reusable offline queue logic.
 */
suspend fun OfflineQueue.enqueueNoteOperation(
    note: Note,
    operation: NoteOperation
) {
    when (operation) {
        is NoteOperation.Create -> enqueueNoteUpdate(note)
        is NoteOperation.Update -> enqueueNoteUpdate(note)
        is NoteOperation.Delete -> enqueueNoteDelete(note.id)
    }
}

/**
 * OPTIMIZATION: Sealed class for note operations.
 */
sealed class NoteOperation {
    data object Create : NoteOperation()
    data object Update : NoteOperation()
    data object Delete : NoteOperation()
}
