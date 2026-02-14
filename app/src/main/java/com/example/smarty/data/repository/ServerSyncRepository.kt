package com.example.smarty.data.repository

import android.util.Log
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.data.remote.RemoteDataService
import com.example.smarty.protocol.NoteInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class ServerSyncRepository(
    private val remoteDataService: RemoteDataService,
    private val eventSink: com.example.smarty.core.common.worker.BackgroundAgentEventSink
) : SyncRepository {
    companion object {
        private const val TAG = "ServerSyncRepo"
        // Cooldown to prevent spamming server
        private const val MIN_SYNC_INTERVAL_MS = 60_000L
    }

    private var currentUserId: String? = null
    private val lastSyncTime = AtomicLong(0)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _remoteNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _remoteCategories = MutableStateFlow<List<Category>>(emptyList())

    override fun initializeForUser(userId: String) {
        currentUserId = userId
        Log.d(TAG, "Initialized Server Sync for user: $userId")
        
        // Initial fetch
        scope.launch {
            refreshData()
        }
        
        // Observe sync events
        scope.launch {
            eventSink.syncEvents.collect { syncType ->
                Log.d(TAG, "Received sync trigger: $syncType")
                refreshData()
            }
        }
    }

    override suspend fun syncNote(note: Note): Result<Unit> {
        // Read-only sync: Do nothing or maybe log that we are in read-only mode
        // For Phase 1, we don't push changes BACK to server from client yet.
        // Or do we? The plan says "Read-Only Sync (Server -> Client)"
        Log.d(TAG, "Read-only sync: Ignoring local note update for ${note.id}")
        return Result.success(Unit)
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> {
        Log.d(TAG, "Read-only sync: Ignoring local note deletion for $noteId")
        return Result.success(Unit)
    }

    override suspend fun syncCategory(category: Category): Result<Unit> {
        Log.d(TAG, "Read-only sync: Ignoring local category update for ${category.id}")
        return Result.success(Unit)
    }

    override suspend fun deleteCategory(categoryId: String): Result<Unit> {
        Log.d(TAG, "Read-only sync: Ignoring local category deletion for $categoryId")
        return Result.success(Unit)
    }

    override fun getRemoteNotesFlow(): Flow<List<Note>> = _remoteNotes.asStateFlow()

    override fun getRemoteCategoriesFlow(): Flow<List<Category>> = _remoteCategories.asStateFlow()

    /**
     * Trigger a fetch from the server.
     * Use this when SSE or polling indicates updates.
     */
    suspend fun refreshData() {
        val now = System.currentTimeMillis()
        if (now - lastSyncTime.get() < MIN_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Sync interval too short, skipping refresh")
            return
        }
        
        Log.d(TAG, "Refreshing data from server...")
        try {
            val noteInfos = remoteDataService.fetchNotes()
            val notes = noteInfos.map { mapToNote(it) }
            _remoteNotes.value = notes
            
            // Categories are implicitly derived from notes or fetched if we add an endpoint
            // For now, let's leave categories empty or derive from notes if needed
            // But DataRoutes doesn't expose categories endpoint explicitly yet (only Notes)
            // SmartyViewModel derives categories from NoteOperationsManager which uses DB.
            
            lastSyncTime.set(now)
            Log.d(TAG, "Refreshed ${notes.size} notes from server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh data", e)
        }
    }

    private fun mapToNote(info: NoteInfo): Note {
        // Map NoteInfo to domain Note
        // Note: NoteInfo is sparse compared to Note. We fill defaults.
        return Note(
            id = info.id,
            title = info.title,
            content = info.content,
            categoryName = info.category,
            // Assuming category ID matches name or handles in repo
            isArchived = info.isArchived,
            createdAt = info.createdAt,
            updatedAt = info.updatedAt,
            // Defaults for fields not in NoteInfo
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isAiCreated = true // Coming from server mostly implies AI or other input
        )
    }
}
