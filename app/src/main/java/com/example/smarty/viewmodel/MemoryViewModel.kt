package com.example.smarty.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.remote.AIService
import com.example.smarty.domain.usecase.GetMemoriesUseCase
import com.example.smarty.domain.usecase.ManageMemoriesUseCase
import com.example.smarty.domain.usecase.SyncMemoriesUseCase
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import com.example.smarty.viewmodel.managers.MemorySyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    // Managers from ServiceLocator to ensure singleton state across ViewModels
    private val memorySyncManager = com.example.smarty.di.ServiceLocator.provideMemorySyncManager(application)
    val memoryFeatureManager = com.example.smarty.di.ServiceLocator.provideMemoryFeatureManager(application)

    // Use Cases
    private val syncMemoriesUseCase = SyncMemoriesUseCase(memorySyncManager)
    private val getMemoriesUseCase = GetMemoriesUseCase(memoryFeatureManager)
    private val manageMemoriesUseCase = ManageMemoriesUseCase(memoryFeatureManager)

    // State
    val aiMemories = getMemoriesUseCase.allMemories
    val isMemorySyncInProgress = syncMemoriesUseCase.isSyncing
    val memorySyncResult = syncMemoriesUseCase.syncResult
    val unreadForMemoryCount = syncMemoriesUseCase.unreadCount

    fun syncAIMemoriesFromNotes() {
        viewModelScope.launch {
            syncMemoriesUseCase()
        }
    }

    fun clearMemorySyncResult() {
        syncMemoriesUseCase.clearResult()
    }

    fun deleteAIMemory(memory: com.example.smarty.data.model.AIMemory) {
        viewModelScope.launch {
            manageMemoriesUseCase.delete(memory.id)
        }
    }

    fun clearAllAIMemories() {
        viewModelScope.launch {
            manageMemoriesUseCase.clearAll()
        }
    }

    fun refreshUnreadForMemoryCount() {
        memorySyncManager.refreshUnreadCount()
    }
}
