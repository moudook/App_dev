package com.example.smarty.core.domain.model

/**
 * Represents usage statistics for an AI provider connection.
 *
 * This is currently a plain model rather than a Room entity. The app no longer
 * reads or writes this table through Room, and keeping it registered as an
 * entity was tripping KSP during schema generation.
 */
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
