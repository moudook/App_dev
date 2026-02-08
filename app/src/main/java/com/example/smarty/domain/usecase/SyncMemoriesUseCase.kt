package com.example.smarty.domain.usecase

import com.example.smarty.viewmodel.managers.MemorySyncManager
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
    val syncResult: StateFlow<String?> = memorySyncManager.syncResult
    val unreadCount: StateFlow<Int> = memorySyncManager.unreadCount

    suspend operator fun invoke(): MemorySyncManager.SyncResult {
        return memorySyncManager.syncMemoriesFromNotes()
    }

    fun clearResult() {
        memorySyncManager.clearSyncResult()
    }

    suspend fun consolidateMemories() {
        memorySyncManager.consolidateAllMemories()
    }
}
