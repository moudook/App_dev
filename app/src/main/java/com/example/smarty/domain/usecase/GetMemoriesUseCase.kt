package com.example.smarty.domain.usecase

import com.example.smarty.data.model.AIMemory
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Use case for retrieving AI memories.
 */
class GetMemoriesUseCase(
    private val memoryFeatureManager: MemoryFeatureManager
) {
    val allMemories: StateFlow<List<AIMemory>> = memoryFeatureManager.allMemories

    suspend fun retrieve(query: String?, limit: Int = 10): List<AIMemory> {
        return memoryFeatureManager.retrieveMemories(query, limit)
    }
}
