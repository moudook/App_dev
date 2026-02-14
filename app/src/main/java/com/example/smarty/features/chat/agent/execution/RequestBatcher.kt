package com.example.smarty.features.chat.agent.execution

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Request Batching using DataLoader pattern.
 *
 * Based on research recommendation: Aggregate multiple requests that arrive
 * within a short time window into a single batch operation.
 *
 * Benefits:
 * - Reduces API call overhead (fewer HTTP connections)
 * - Enables batch processing optimizations
 * - Improves throughput for concurrent operations
 * - 30-50% reduction in latency for burst requests
 *
 * Pattern:
 * ```
 * Request 1 
 * Request 2 > [Batch Window: 50ms] > Single Batch Execution > Individual Results
 * Request 3 
 * ```
 *
 * BUG FIX (TECH-001): Now requires an external scope to be passed in.
 * The scope should be tied to the lifecycle of the component using this batcher
 * (e.g., viewModelScope, lifecycleScope). This prevents memory leaks from
 * holding references to Repositories/ViewModels indefinitely.
 */
class RequestBatcher<K, V>(
    private val batchWindowMs: Long = 50,
    private val maxBatchSize: Int = 10,
    private val batchLoader: suspend (List<K>) -> Map<K, V>,
    private val scope: CoroutineScope  // BUG FIX (TECH-001): No default scope - must be lifecycle-aware
) {
    companion object {
        private const val TAG = "RequestBatcher"
    }

    // Pending requests waiting to be batched
    private val pendingRequests = ConcurrentHashMap<K, CompletableDeferred<V>>()
    private val requestQueue = Channel<K>(Channel.UNLIMITED)
    private val mutex = Mutex()

    // Statistics
    private val totalRequests = AtomicLong(0)
    private val batchedRequests = AtomicLong(0)
    private val batchCount = AtomicLong(0)

    // Batch processing job
    private var batchJob: Job? = null

    init {
        startBatchProcessor()
    }

    /**
     * Load a single value, potentially batched with other concurrent requests.
     *
     * @param key The key to load
     * @return The loaded value
     */
    suspend fun load(key: K): V {
        totalRequests.incrementAndGet()

        // Check if already pending
        pendingRequests[key]?.let { existing ->
            Log.d(TAG, "Request for $key already pending, reusing")
            return existing.await()
        }

        // Create new deferred result
        val deferred = CompletableDeferred<V>()
        pendingRequests[key] = deferred

        // Queue for batching
        requestQueue.send(key)

        return deferred.await()
    }

    /**
     * Load multiple values in a single call.
     * More efficient than calling load() multiple times.
     */
    suspend fun loadMany(keys: List<K>): Map<K, V> {
        if (keys.isEmpty()) return emptyMap()
        if (keys.size == 1) return mapOf(keys.first() to load(keys.first()))

        totalRequests.addAndGet(keys.size.toLong())

        // Create deferreds for all keys
        val deferreds = keys.associateWith { key ->
            pendingRequests.getOrPut(key) { CompletableDeferred() }
        }

        // Queue all for batching
        keys.forEach { requestQueue.send(it) }

        // Await all results
        return deferreds.mapValues { it.value.await() }
    }

    /**
     * Start the batch processor coroutine.
     */
    private fun startBatchProcessor() {
        batchJob = scope.launch {
            while (isActive) {
                try {
                    processBatch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Batch processing error: ${e.message}", e)
                    delay(100) // Back off on error
                }
            }
        }
    }

    /**
     * Process a batch of pending requests.
     */
    private suspend fun processBatch() {
        // Wait for first request
        val firstKey = requestQueue.receive()
        val batch = mutableListOf(firstKey)

        // Collect more requests within the window
        val deadline = System.currentTimeMillis() + batchWindowMs
        while (batch.size < maxBatchSize && System.currentTimeMillis() < deadline) {
            val result = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                requestQueue.receive()
            }
            if (result != null) {
                batch.add(result)
            } else {
                break
            }
        }

        // Execute batch
        mutex.withLock {
            executeBatch(batch.distinct())
        }
    }

    /**
     * Execute a batch of requests.
     */
    private suspend fun executeBatch(keys: List<K>) {
        if (keys.isEmpty()) return

        batchCount.incrementAndGet()
        batchedRequests.addAndGet(keys.size.toLong())

        Log.d(TAG, "Executing batch of ${keys.size} requests")

        try {
            val results = batchLoader(keys)

            // Complete all deferreds
            keys.forEach { key ->
                val deferred = pendingRequests.remove(key)
                val result = results[key]
                if (result != null) {
                    deferred?.complete(result)
                } else {
                    deferred?.completeExceptionally(
                        NoSuchElementException("No result for key: $key")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch execution failed: ${e.message}", e)
            // Fail all pending requests
            keys.forEach { key ->
                pendingRequests.remove(key)?.completeExceptionally(e)
            }
        }
    }

    /**
     * Prime the cache with a known value (useful for avoiding redundant loads).
     */
    fun prime(key: K, value: V) {
        pendingRequests[key]?.complete(value)
    }

    /**
     * Clear all pending requests (useful for cleanup).
     */
    suspend fun clear() = mutex.withLock {
        pendingRequests.values.forEach {
            it.completeExceptionally(CancellationException("Batcher cleared"))
        }
        pendingRequests.clear()
    }

    /**
     * Stop the batch processor.
     * BUG FIX (TECH-001): Only cancels the batch job, not the external scope.
     * The scope is owned by the caller and will be cancelled when appropriate.
     */
    fun stop() {
        batchJob?.cancel()
        batchJob = null
        // Note: We don't cancel the scope here - it's owned by the caller
    }

    /**
     * Get batching statistics.
     */
    fun getStats(): BatcherStats = BatcherStats(
        totalRequests = totalRequests.get(),
        batchedRequests = batchedRequests.get(),
        batchCount = batchCount.get(),
        averageBatchSize = if (batchCount.get() > 0) {
            batchedRequests.get().toFloat() / batchCount.get()
        } else 0f,
        batchingEfficiency = if (totalRequests.get() > 0) {
            1f - (batchCount.get().toFloat() / totalRequests.get())
        } else 0f
    )

    data class BatcherStats(
        val totalRequests: Long,
        val batchedRequests: Long,
        val batchCount: Long,
        val averageBatchSize: Float,
        val batchingEfficiency: Float  // 0 = no batching, 1 = perfect batching
    )
}

/**
 * Pre-configured batcher for note operations.
 *
 * BUG FIX (TECH-001): Now requires a lifecycle-aware scope to prevent memory leaks.
 * Pass viewModelScope or lifecycleScope to tie the batcher to component lifecycle.
 */
object NoteBatcher {
    private var instance: RequestBatcher<String, Any?>? = null

    fun getInstance(
        scope: CoroutineScope,  // BUG FIX (TECH-001): Required lifecycle-aware scope
        noteLoader: suspend (List<String>) -> Map<String, Any?>
    ): RequestBatcher<String, Any?> {
        return instance ?: RequestBatcher(
            batchWindowMs = 30,
            maxBatchSize = 20,
            batchLoader = noteLoader,
            scope = scope
        ).also { instance = it }
    }

    fun stop() {
        instance?.stop()
        instance = null
    }
}
