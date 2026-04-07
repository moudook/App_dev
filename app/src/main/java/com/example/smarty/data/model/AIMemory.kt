package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a discrete unit of memory for the AI agent.
 */
@Entity(tableName = "ai_memories")
data class AIMemory(
    @PrimaryKey
    val id: String,
    val content: String,
    val type: MemoryType,
    val timestamp: Long,
    val lastUsedAt: Long,
    val usageCount: Int = 0,
    val confidence: Float = 1.0f,
    val scope: String? = null,
    val relevance: Float = 1.0f,
)
