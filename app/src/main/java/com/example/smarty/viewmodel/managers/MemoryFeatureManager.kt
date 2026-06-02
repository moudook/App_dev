package com.example.smarty.viewmodel.managers

import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import com.example.smarty.features.chat.domain.memory.AIMemoryDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MemoryFeatureManager(
    private val aiMemoryDao: AIMemoryDao,
) {
    private val _allMemories = MutableStateFlow<List<AIMemory>>(emptyList())
    val allMemories: StateFlow<List<AIMemory>> = _allMemories.asStateFlow()

    suspend fun retrieveMemories(
        query: String?,
        limit: Int,
    ): List<AIMemory> {
        return aiMemoryDao.getAllMemories() // Assuming getAllMemories exists, or we need to add it or use another method
    }

    suspend fun storeMemory(memory: AIMemory) {
        aiMemoryDao.insertMemory(memory)
    }

    suspend fun storeMemory(
        content: String,
        scope: String? = null,
    ): Boolean {
        // Create a new AIMemory object
        val memory =
            AIMemory(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                content = content,
                scope = scope,
                type = MemoryType.FACT, // Default type
                timestamp = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
                relevance = 1.0f, // Default relevance
            )
        aiMemoryDao.insertMemory(memory)
        return true
    }

    suspend fun deleteMemory(memory: AIMemory) {
        aiMemoryDao.deleteMemory(memory)
    }

    suspend fun deleteMemory(id: String): Boolean {
        aiMemoryDao.deleteMemoryById(id)
        return true
    }

    suspend fun clearAllMemories(): Boolean {
        aiMemoryDao.clearAllMemories()
        return true
    }
}
