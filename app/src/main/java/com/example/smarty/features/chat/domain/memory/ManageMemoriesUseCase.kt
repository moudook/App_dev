package com.example.smarty.features.chat.domain.memory

import com.example.smarty.viewmodel.managers.MemoryFeatureManager

/**
 * Use case for managing (CRUD) AI memories.
 */
class ManageMemoriesUseCase(
    private val memoryFeatureManager: MemoryFeatureManager,
) {
    suspend fun store(
        content: String,
        scope: String? = null,
    ): Boolean {
        return memoryFeatureManager.storeMemory(content, scope)
    }

    suspend fun delete(id: String): Boolean {
        return memoryFeatureManager.deleteMemory(id)
    }

    suspend fun clearAll(): Boolean {
        return memoryFeatureManager.clearAllMemories()
    }
}
