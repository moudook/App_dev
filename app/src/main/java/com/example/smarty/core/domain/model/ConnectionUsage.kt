package com.example.smarty.core.domain.model

import androidx.room.Entity

/**
 * Represents usage statistics for an AI provider connection.
 * Used for rate limiting and usage tracking.
 */
@Entity(tableName = "provider_usage", primaryKeys = ["date", "provider", "modelId"])
data class ConnectionUsage(
    val date: Long,
    val provider: String,
    val modelId: String,
    val callCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val rateLimitHits: Int = 0,
    val toolCalls: Int = 0,
    val avgLatencyMs: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
