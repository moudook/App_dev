package com.example.smarty.agent.execution

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Parallel tool execution engine for optimized agent operations.
 *
 * Classifies tools by their side effects and executes them optimally:
 * - **Read-only tools**: Execute in parallel (search, list, get operations)
 * - **Write tools**: Execute sequentially (create, update, delete operations)
 *
 * This provides 2-3x speedup for multi-tool operations while maintaining
 * data consistency.
 *
 * Example optimization:
 * ```
 * // Before (sequential): ~3 seconds
 * search_notes("work") -> list_categories() -> get_note("123")
 *
 * // After (parallel): ~1 second
 * parallel { search_notes("work"), list_categories(), get_note("123") }
 * ```
 */
class ParallelToolExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxParallelism: Int = 4
) {
    companion object {
        private const val TAG = "ParallelToolExecutor"

        // Read-only tools that can be executed in parallel
        // BUG FIX (Issue #35): Added missing tools to classification
        private val READ_ONLY_TOOLS = setOf(
            // Search operations
            "search_notes",
            "smart_search",
            "search_audio_notes",
            "search_image_notes",
            "search_document_notes",
            // List operations
            "list_categories",
            "get_category_notes",
            "list_todos",  // BUG FIX: Was missing
            // Get operations
            "get_note",
            "summarize_note",
            "get_user_patterns",
            // Calendar queries
            "search_calendar",
            "get_events",  // BUG FIX: Newly added tool
            // External queries
            "web_search",
            "deep_research"  // BUG FIX: Was missing - it's a read-only research tool
        )

        // Write tools that must execute sequentially
        private val WRITE_TOOLS = setOf(
            // Note CRUD
            "create_note",
            "update_note",
            "delete_note",
            "archive_note",
            "unarchive_note",
            // Todo operations
            "add_todos",
            "toggle_todo",
            "delete_todo",
            // Calendar mutations
            "create_event",
            "delete_event",
            "create_timer",
            "cancel_timer",
            // Batch operations
            "batch_operations",
            // Audio control
            "play_audio"
        )
    }

    // Mutex for sequential write operations
    private val writeMutex = Mutex()

    // Statistics - FIX: Use atomic types for thread safety
    private val parallelExecutions = AtomicInteger(0)
    private val sequentialExecutions = AtomicInteger(0)
    private val totalTimeSaved = AtomicLong(0L)

    /**
     * Represents a tool call to be executed.
     */
    data class ToolCall(
        val name: String,
        val arguments: Map<String, Any?>,
        val id: String = java.util.UUID.randomUUID().toString()
    )

    /**
     * Result of a tool execution.
     */
    data class ToolResult(
        val toolCall: ToolCall,
        val success: Boolean,
        val result: Any?,
        val error: String? = null,
        val executionTimeMs: Long = 0
    )

    /**
     * Classify a tool by its side effects.
     */
    fun classifyTool(toolName: String): ToolType {
        return when {
            toolName in READ_ONLY_TOOLS -> ToolType.READ_ONLY
            toolName in WRITE_TOOLS -> ToolType.WRITE
            else -> ToolType.UNKNOWN // Default to sequential for safety
        }
    }

    /**
     * Execute multiple tool calls with optimal parallelism.
     *
     * @param toolCalls List of tool calls to execute
     * @param executor Function that actually executes a single tool
     * @return List of results in the same order as input
     */
    suspend fun executeOptimal(
        toolCalls: List<ToolCall>,
        executor: suspend (ToolCall) -> Any?
    ): List<ToolResult> = coroutineScope {
        if (toolCalls.isEmpty()) return@coroutineScope emptyList()
        if (toolCalls.size == 1) {
            return@coroutineScope listOf(executeSingle(toolCalls.first(), executor))
        }

        val startTime = System.currentTimeMillis()

        // Partition by tool type
        val (readOnly, write) = toolCalls.partition {
            classifyTool(it.name) == ToolType.READ_ONLY
        }

        Log.d(TAG, "Executing ${readOnly.size} read-only tools in parallel, ${write.size} write tools sequentially")

        // Execute read-only tools in parallel
        val parallelResults = if (readOnly.isNotEmpty()) {
            parallelExecutions.incrementAndGet()
            readOnly
                .chunked(maxParallelism)
                .flatMap { chunk ->
                    chunk.map { toolCall ->
                        async(dispatcher) {
                            executeSingle(toolCall, executor)
                        }
                    }.awaitAll()
                }
        } else {
            emptyList()
        }

        // Execute write tools sequentially with mutex
        val sequentialResults = if (write.isNotEmpty()) {
            sequentialExecutions.incrementAndGet()
            writeMutex.withLock {
                write.map { toolCall ->
                    executeSingle(toolCall, executor)
                }
            }
        } else {
            emptyList()
        }

        // Calculate time saved
        val actualTime = System.currentTimeMillis() - startTime
        val estimatedSequentialTime = (parallelResults + sequentialResults).sumOf { it.executionTimeMs }
        val timeSaved = estimatedSequentialTime - actualTime
        if (timeSaved > 0) {
            totalTimeSaved.addAndGet(timeSaved)
            Log.d(TAG, "Parallel execution saved ~${timeSaved}ms (actual: ${actualTime}ms vs sequential: ${estimatedSequentialTime}ms)")
        }

        // Combine results preserving original order
        // FIX: Handle potential missing results gracefully instead of !!
        val resultMap = (parallelResults + sequentialResults).associateBy { it.toolCall.id }
        toolCalls.map { toolCall ->
            resultMap[toolCall.id] ?: ToolResult(
                toolCall = toolCall,
                success = false,
                result = null,
                error = "Tool execution result not found"
            )
        }
    }

    /**
     * Execute a single tool call with timing.
     */
    private suspend fun executeSingle(
        toolCall: ToolCall,
        executor: suspend (ToolCall) -> Any?
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        return try {
            val result = executor(toolCall)
            ToolResult(
                toolCall = toolCall,
                success = true,
                result = result,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${toolCall.name}", e)
            ToolResult(
                toolCall = toolCall,
                success = false,
                result = null,
                error = e.message,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Check if multiple tools can be executed in parallel.
     */
    fun canParallelize(toolNames: List<String>): Boolean {
        return toolNames.all { classifyTool(it) == ToolType.READ_ONLY }
    }

    /**
     * Get execution statistics.
     */
    fun getStats(): ExecutionStats = ExecutionStats(
        parallelBatches = parallelExecutions.get(),
        sequentialBatches = sequentialExecutions.get(),
        totalTimeSavedMs = totalTimeSaved.get()
    )

    /**
     * Reset execution statistics.
     */
    fun resetStats() {
        parallelExecutions.set(0)
        sequentialExecutions.set(0)
        totalTimeSaved.set(0L)
    }

    /**
     * Tool classification type.
     */
    enum class ToolType {
        READ_ONLY,  // Can execute in parallel
        WRITE,      // Must execute sequentially
        UNKNOWN     // Default to sequential for safety
    }

    /**
     * Execution statistics.
     */
    data class ExecutionStats(
        val parallelBatches: Int,
        val sequentialBatches: Int,
        val totalTimeSavedMs: Long
    )
}
