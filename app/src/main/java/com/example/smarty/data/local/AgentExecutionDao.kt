package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.AgentExecution
import com.example.smarty.data.model.ExecutionStatus
import com.example.smarty.data.model.ProviderUsage
import kotlinx.coroutines.flow.Flow

/**
 * DAO for agent execution tracking and provider usage.
 * Used for:
 * - Token usage monitoring
 * - Rate limit management
 * - Performance analytics
 * - Provider/model usage patterns
 */
@Dao
interface AgentExecutionDao {

    // ==================== Agent Execution Operations ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: AgentExecution)

    @Query("SELECT * FROM agent_executions WHERE id = :id")
    suspend fun getExecutionById(id: String): AgentExecution?

    @Query("SELECT * FROM agent_executions WHERE sessionId = :sessionId ORDER BY executedAt DESC")
    fun getExecutionsForSession(sessionId: String): Flow<List<AgentExecution>>

    @Query("SELECT * FROM agent_executions WHERE sessionId = :sessionId ORDER BY executedAt DESC")
    suspend fun getExecutionsForSessionOnce(sessionId: String): List<AgentExecution>

    @Query("SELECT * FROM agent_executions ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getRecentExecutions(limit: Int = 50): List<AgentExecution>

    @Query("SELECT * FROM agent_executions WHERE provider = :provider ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getExecutionsByProvider(provider: String, limit: Int = 50): List<AgentExecution>

    @Query("SELECT * FROM agent_executions WHERE status = :status ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getExecutionsByStatus(status: ExecutionStatus, limit: Int = 50): List<AgentExecution>

    /**
     * Get total tokens used today for a specific provider/model.
     */
    @Query("""
        SELECT COALESCE(SUM(totalTokens), 0) FROM agent_executions
        WHERE provider = :provider
        AND modelId = :modelId
        AND executedAt >= :todayStart
    """)
    suspend fun getTodayTokens(provider: String, modelId: String, todayStart: Long): Int

    /**
     * Get call count for the current minute (sliding window rate limiting).
     */
    @Query("""
        SELECT COUNT(*) FROM agent_executions
        WHERE provider = :provider
        AND executedAt >= :minuteAgo
    """)
    suspend fun getCallsInLastMinute(provider: String, minuteAgo: Long): Int

    /**
     * Get average latency for a provider/model combination.
     */
    @Query("""
        SELECT AVG(latencyMs) FROM agent_executions
        WHERE provider = :provider
        AND modelId = :modelId
        AND status = 'SUCCESS'
        AND executedAt >= :since
    """)
    suspend fun getAverageLatency(provider: String, modelId: String, since: Long): Long?

    /**
     * Get success rate for a provider/model.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) * 100
        FROM agent_executions
        WHERE provider = :provider
        AND modelId = :modelId
        AND executedAt >= :since
    """)
    suspend fun getSuccessRate(provider: String, modelId: String, since: Long): Float?

    // ==================== Provider Usage Operations ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUsage(usage: ProviderUsage)

    @Query("SELECT * FROM provider_usage WHERE date = :date AND provider = :provider AND modelId = :modelId")
    suspend fun getUsage(date: Int, provider: String, modelId: String): ProviderUsage?

    @Query("SELECT * FROM provider_usage WHERE date = :date ORDER BY totalTokens DESC")
    suspend fun getTodayUsage(date: Int): List<ProviderUsage>

    @Query("SELECT * FROM provider_usage WHERE provider = :provider ORDER BY date DESC LIMIT :days")
    suspend fun getUsageHistory(provider: String, days: Int = 7): List<ProviderUsage>

    /**
     * Increment usage counters for a provider/model.
     * Creates new record if not exists.
     */
    @Transaction
    suspend fun recordUsage(
        provider: String,
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        success: Boolean,
        toolCalls: Int,
        latencyMs: Long
    ) {
        val today = ProviderUsage.todayAsInt()
        val existing = getUsage(today, provider, modelId)

        if (existing != null) {
            val newCallCount = existing.callCount + 1
            val newSuccessCount = if (success) existing.successCount + 1 else existing.successCount
            val newFailureCount = if (!success) existing.failureCount + 1 else existing.failureCount
            val newAvgLatency = ((existing.avgLatencyMs * existing.callCount) + latencyMs) / newCallCount

            insertOrUpdateUsage(existing.copy(
                callCount = newCallCount,
                inputTokens = existing.inputTokens + inputTokens,
                outputTokens = existing.outputTokens + outputTokens,
                totalTokens = existing.totalTokens + inputTokens + outputTokens,
                successCount = newSuccessCount,
                failureCount = newFailureCount,
                toolCalls = existing.toolCalls + toolCalls,
                avgLatencyMs = newAvgLatency,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            insertOrUpdateUsage(ProviderUsage(
                date = today,
                provider = provider,
                modelId = modelId,
                callCount = 1,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
                successCount = if (success) 1 else 0,
                failureCount = if (!success) 1 else 0,
                toolCalls = toolCalls,
                avgLatencyMs = latencyMs
            ))
        }
    }

    /**
     * Record a rate limit hit.
     */
    @Transaction
    suspend fun recordRateLimitHit(provider: String, modelId: String) {
        val today = ProviderUsage.todayAsInt()
        val existing = getUsage(today, provider, modelId)

        if (existing != null) {
            insertOrUpdateUsage(existing.copy(
                rateLimitHits = existing.rateLimitHits + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            insertOrUpdateUsage(ProviderUsage(
                date = today,
                provider = provider,
                modelId = modelId,
                rateLimitHits = 1
            ))
        }
    }

    // ==================== Cleanup Operations ====================

    /**
     * Delete old executions (keep last N days).
     */
    @Query("DELETE FROM agent_executions WHERE executedAt < :olderThan")
    suspend fun pruneOldExecutions(olderThan: Long)

    /**
     * Delete old usage records (keep last N days).
     */
    @Query("DELETE FROM provider_usage WHERE date < :olderThan")
    suspend fun pruneOldUsage(olderThan: Int)

    /**
     * Delete all agent data.
     */
    @Query("DELETE FROM agent_executions")
    suspend fun deleteAllExecutions()

    @Query("DELETE FROM provider_usage")
    suspend fun deleteAllUsage()

    @Transaction
    suspend fun deleteAllAgentData() {
        deleteAllExecutions()
        deleteAllUsage()
    }
}
