package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.AgentExecution
import com.example.smarty.data.model.ExecutionStatus
import com.example.smarty.data.model.ConnectionUsage
import kotlinx.coroutines.flow.Flow

/**
 * DAO for agent execution tracking and connection usage.
 * Used for:
 * - Token usage monitoring
 * - Rate limit management
 * - Performance analytics
 * - Connection/model usage patterns
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

    @Query("SELECT * FROM agent_executions WHERE connection = :connection ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getExecutionsByConnection(connection: String, limit: Int = 50): List<AgentExecution>

    @Query("SELECT * FROM agent_executions WHERE status = :status ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getExecutionsByStatus(status: ExecutionStatus, limit: Int = 50): List<AgentExecution>

    /**
     * Get total tokens used today for a specific connection/model.
     */
    @Query("""
        SELECT COALESCE(SUM(totalTokens), 0) FROM agent_executions
        WHERE connection = :connection
        AND modelId = :modelId
        AND executedAt >= :todayStart
    """)
    suspend fun getTodayTokens(connection: String, modelId: String, todayStart: Long): Int

    /**
     * Get call count for the current minute (sliding window rate limiting).
     */
    @Query("""
        SELECT COUNT(*) FROM agent_executions
        WHERE connection = :connection
        AND executedAt >= :minuteAgo
    """)
    suspend fun getCallsInLastMinute(connection: String, minuteAgo: Long): Int

    /**
     * Get average latency for a connection/model combination.
     */
    @Query("""
        SELECT AVG(latencyMs) FROM agent_executions
        WHERE connection = :connection
        AND modelId = :modelId
        AND status = 'SUCCESS'
        AND executedAt >= :since
    """)
    suspend fun getAverageLatency(connection: String, modelId: String, since: Long): Long?

    /**
     * Get success rate for a connection/model.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) * 100
        FROM agent_executions
        WHERE connection = :connection
        AND modelId = :modelId
        AND executedAt >= :since
    """)
    suspend fun getSuccessRate(connection: String, modelId: String, since: Long): Float?

    // ==================== Connection Usage Operations ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUsage(usage: ConnectionUsage)

    @Query("SELECT * FROM connection_usage WHERE date = :date AND connection = :connection AND modelId = :modelId")
    suspend fun getUsage(date: Int, connection: String, modelId: String): ConnectionUsage?

    @Query("SELECT * FROM connection_usage WHERE date = :date ORDER BY totalTokens DESC")
    suspend fun getTodayUsage(date: Int): List<ConnectionUsage>

    @Query("SELECT * FROM connection_usage WHERE connection = :connection ORDER BY date DESC LIMIT :days")
    suspend fun getUsageHistory(connection: String, days: Int = 7): List<ConnectionUsage>

    /**
     * Increment usage counters for a connection/model.
     * Creates new record if not exists.
     */
    @Transaction
    suspend fun recordUsage(
        connection: String,
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        success: Boolean,
        toolCalls: Int,
        latencyMs: Long
    ) {
        val today = ConnectionUsage.todayAsInt()
        val existing = getUsage(today, connection, modelId)

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
            insertOrUpdateUsage(ConnectionUsage(
                date = today,
                connection = connection,
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
    suspend fun recordRateLimitHit(connection: String, modelId: String) {
        val today = ConnectionUsage.todayAsInt()
        val existing = getUsage(today, connection, modelId)

        if (existing != null) {
            insertOrUpdateUsage(existing.copy(
                rateLimitHits = existing.rateLimitHits + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            insertOrUpdateUsage(ConnectionUsage(
                date = today,
                connection = connection,
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
    @Query("DELETE FROM connection_usage WHERE date < :olderThan")
    suspend fun pruneOldUsage(olderThan: Int)

    /**
     * Delete all agent data.
     */
    @Query("DELETE FROM agent_executions")
    suspend fun deleteAllExecutions()

    @Query("DELETE FROM connection_usage")
    suspend fun deleteAllUsage()

    @Transaction
    suspend fun deleteAllAgentData() {
        deleteAllExecutions()
        deleteAllUsage()
    }
}
