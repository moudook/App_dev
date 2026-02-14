package com.example.smarty.features.chat.domain.memory

import com.example.smarty.features.chat.domain.memory.MemorySyncManager
import com.example.smarty.data.model.SyncResult
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Use case for synchronizing memories from notes.
 * Encapsulates the logic of triggering memory extraction and monitoring status.
 */
class SyncMemoriesUseCase(
    private val memorySyncManager: MemorySyncManager
) {
    val isSyncing: StateFlow<Boolean> = memorySyncManager.isSyncing
    val syncResult: StateFlow<SyncResult?> = memorySyncManager.syncResult
    val unreadCount: StateFlow<Int> = memorySyncManager.unreadCount

    suspend operator fun invoke(): SyncResult {
        return memorySyncManager.syncMemoriesFromNotes()
    }

    fun clearResult() {
        memorySyncManager.clearSyncResult()
    }

    suspend fun consolidateMemories() {
        memorySyncManager.consolidateAllMemories()
    }
}
