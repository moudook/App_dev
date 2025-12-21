package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smarty.data.local.AIProvider
import java.util.UUID

/**
 * =============================================================================
 * AGENT EXECUTION TRACKING
 * =============================================================================
 *
 * Tracks each AI agent execution for:
 * - Token usage monitoring (input/output tokens)
 * - Tool call statistics
 * - Performance metrics (latency, success rate)
 * - Provider/model usage patterns
 * - Rate limit management
 *
 * Linked to ChatSession for conversation context.
 *
 * =============================================================================
 */
@Entity(
    tableName = "agent_executions",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["provider"]),
        Index(value = ["modelId"]),
        Index(value = ["executedAt"]),
        Index(value = ["status"])
    ]
)
data class AgentExecution(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /**
     * Link to the chat session this execution belongs to.
     */
    val sessionId: String,

    /**
     * AI provider used for this execution.
     */
    val provider: String,  // Stored as string for Room compatibility

    /**
     * Model ID used for this execution.
     */
    val modelId: String,

    /**
     * User's original prompt/message.
     */
    val userPrompt: String,

    /**
     * Agent's response (may be truncated for storage).
     */
    val response: String,

    /**
     * Execution status.
     */
    val status: ExecutionStatus = ExecutionStatus.SUCCESS,

    /**
     * Error message if status is FAILED.
     */
    val errorMessage: String? = null,

    /**
     * Number of input tokens consumed.
     * Estimated based on prompt length if not provided by API.
     */
    val inputTokens: Int = 0,

    /**
     * Number of output tokens generated.
     * Estimated based on response length if not provided by API.
     */
    val outputTokens: Int = 0,

    /**
     * Total tokens (input + output).
     */
    val totalTokens: Int = inputTokens + outputTokens,

    /**
     * Number of tool calls made during execution.
     */
    val toolCallCount: Int = 0,

    /**
     * JSON list of tool names called.
     * e.g., ["search_notes", "create_note"]
     */
    val toolsCalled: String = "[]",

    /**
     * Number of agent iterations (reasoning steps).
     */
    val iterations: Int = 1,

    /**
     * Execution latency in milliseconds.
     */
    val latencyMs: Long = 0,

    /**
     * When the execution started.
     */
    val executedAt: Long = System.currentTimeMillis(),

    /**
     * API key index used (for multi-key rotation tracking).
     */
    val keyIndex: Int = 0
) {
    /**
     * Get provider as enum.
     */
    fun getProviderEnum(): AIProvider? {
        return try {
            AIProvider.valueOf(provider)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Estimated cost based on token usage (rough approximation).
     * Returns 0 for free tier providers.
     */
    fun estimatedCostCents(): Double {
        return when (getProviderEnum()) {
            AIProvider.GROQ, AIProvider.CEREBRAS, AIProvider.OPENROUTER -> 0.0  // Free tier
            AIProvider.OPENAI -> (inputTokens * 0.00001 + outputTokens * 0.00003) * 100  // GPT-4o-mini pricing
            AIProvider.ANTHROPIC -> (inputTokens * 0.000003 + outputTokens * 0.000015) * 100  // Claude 3.5 Sonnet
            AIProvider.GEMINI -> 0.0  // Free tier
            else -> 0.0
        }
    }
}

/**
 * Status of an agent execution.
 */
enum class ExecutionStatus {
    SUCCESS,           // Completed successfully
    FAILED,            // Failed with error
    RATE_LIMITED,      // Hit rate limit
    TIMEOUT,           // Execution timed out
    CANCELLED          // User cancelled
}

/**
 * Daily usage summary per provider/model combination.
 * Used for rate limiting and quota tracking.
 */
@Entity(
    tableName = "provider_usage",
    primaryKeys = ["date", "provider", "modelId"],
    indices = [
        Index(value = ["date"]),
        Index(value = ["provider"])
    ]
)
data class ProviderUsage(
    /**
     * Date in YYYYMMDD format for easy querying.
     */
    val date: Int,

    /**
     * Provider name.
     */
    val provider: String,

    /**
     * Model ID.
     */
    val modelId: String,

    /**
     * Number of API calls made today.
     */
    val callCount: Int = 0,

    /**
     * Total input tokens consumed today.
     */
    val inputTokens: Int = 0,

    /**
     * Total output tokens generated today.
     */
    val outputTokens: Int = 0,

    /**
     * Total tokens (input + output) today.
     */
    val totalTokens: Int = inputTokens + outputTokens,

    /**
     * Number of successful executions.
     */
    val successCount: Int = 0,

    /**
     * Number of failed executions.
     */
    val failureCount: Int = 0,

    /**
     * Number of rate limit hits.
     */
    val rateLimitHits: Int = 0,

    /**
     * Total tool calls made today.
     */
    val toolCalls: Int = 0,

    /**
     * Average latency in milliseconds.
     */
    val avgLatencyMs: Long = 0,

    /**
     * Last updated timestamp.
     */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Get today's date as YYYYMMDD integer.
         */
        fun todayAsInt(): Int {
            val cal = java.util.Calendar.getInstance()
            return cal.get(java.util.Calendar.YEAR) * 10000 +
                    (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
        }
    }

    /**
     * Check if daily token limit is exceeded for a model.
     */
    fun isTokenLimitExceeded(dailyLimit: Int): Boolean {
        return dailyLimit > 0 && totalTokens >= dailyLimit
    }
}
