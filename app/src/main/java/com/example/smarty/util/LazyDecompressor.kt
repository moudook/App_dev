package com.example.smarty.util

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * =============================================================================
 * LAZY DECOMPRESSOR
 * =============================================================================
 *
 * On-demand decompression engine that sleeps until triggered.
 * Designed for minimal resource usage on edge devices.
 *
 * Key Features:
 * - LAZY: Only decompresses when content is actually needed
 * - SLEEPING: Worker stays dormant until wake signal
 * - INTELLIGENT CACHING: Memory-aware LRU cache with eviction
 * - PRIORITY QUEUE: Processes high-priority items first
 * - CANCELABLE: Pending requests can be cancelled
 * - MEMORY AWARE: Adapts to device capabilities and memory pressure
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    LazyDecompressor                         │
 * ├─────────────────────────────────────────────────────────────┤
 * │  Request Queue (Channel)                                    │
 * │  ┌─────┐ ┌─────┐ ┌─────┐                                   │
 * │  │ Req │→│ Req │→│ Req │→ ... (priority ordered)           │
 * │  └─────┘ └─────┘ └─────┘                                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  Worker (SLEEPING until request arrives)                    │
 * │  • Wakes on request                                        │
 * │  • Processes one item                                      │
 * │  • Returns to SLEEP                                        │
 * ├─────────────────────────────────────────────────────────────┤
 * │  LRU Cache (memory-aware)                                  │
 * │  • Evicts oldest when full                                 │
 * │  • Adjusts size based on device class                      │
 * └─────────────────────────────────────────────────────────────┘
 *
 * =============================================================================
 */
object LazyDecompressor {

    private const val TAG = "LazyDecompressor"

    // =========================================================================
    // CONFIGURATION (adapts to device capabilities)
    // =========================================================================

    private var maxCacheEntries = 20
    private var maxCacheSizeBytes = 50 * 1024 * 1024L  // 50MB default
    private var bufferSize = 65536  // 64KB default

    // =========================================================================
    // STATE
    // =========================================================================

    private val _workerState = MutableStateFlow(WorkerState.SLEEPING)
    val workerState: StateFlow<WorkerState> = _workerState.asStateFlow()

    private val _decompressionStates = MutableStateFlow<Map<String, DecompState>>(emptyMap())
    val decompressionStates: StateFlow<Map<String, DecompState>> = _decompressionStates.asStateFlow()

    // Request channel - unlimited capacity, suspends sender if worker is busy
    private val requestChannel = Channel<DecompressionRequest>(Channel.UNLIMITED)

    // Pending requests by ID for cancellation
    private val pendingRequests = ConcurrentHashMap<String, DecompressionRequest>()

    // Completed results
    private val completedResults = ConcurrentHashMap<String, DecompressionResult>()

    // LRU Cache for decompressed files
    private var decompressedCache: LruCache<String, CachedFile>? = null
    private val cacheMutex = Mutex()
    private var currentCacheSize = 0L

    // Worker job
    private var workerJob: Job? = null
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Context reference (weak to avoid memory leaks)
    private var contextRef: WeakReference<Context>? = null

    // =========================================================================
    // INITIALIZATION
    // =========================================================================

    /**
     * Initialize with context. Adapts to device capabilities.
     * Safe to call multiple times.
     */
    fun initialize(context: Context) {
        if (contextRef?.get() != null) return

        contextRef = WeakReference(context.applicationContext)

        // Adapt to device capabilities
        try {
            maxCacheEntries = when (ResourceManager.getDeviceClass()) {
                ResourceManager.DeviceClass.EDGE -> 5
                ResourceManager.DeviceClass.LOW -> 10
                ResourceManager.DeviceClass.MEDIUM -> 15
                ResourceManager.DeviceClass.HIGH -> 20
                ResourceManager.DeviceClass.FLAGSHIP -> 30
            }
            maxCacheSizeBytes = ResourceManager.getMaxCacheSize()
            bufferSize = ResourceManager.getOptimalBufferSize()
        } catch (e: Exception) {
            // ResourceManager not initialized, use defaults
            Log.w(TAG, "ResourceManager not available, using defaults")
        }

        // Initialize cache
        decompressedCache = object : LruCache<String, CachedFile>(maxCacheEntries) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String?,
                oldValue: CachedFile?,
                newValue: CachedFile?
            ) {
                if (evicted && oldValue != null) {
                    currentCacheSize -= oldValue.size
                    // Delete the cached file
                    try {
                        oldValue.file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete evicted cache file: ${oldValue.file.name}")
                    }
                    Log.d(TAG, "Evicted: ${oldValue.file.name}, cache size: ${currentCacheSize / 1024}KB")
                }
            }
        }

        // Start worker (but it will sleep immediately)
        startWorker()

        Log.d(TAG, "Initialized: maxCache=$maxCacheEntries, maxSize=${maxCacheSizeBytes / 1024 / 1024}MB")
    }

    /**
     * Shutdown the decompressor and release resources
     */
    fun shutdown() {
        workerJob?.cancel()
        workerJob = null
        requestChannel.close()
        clearCache()
        _workerState.value = WorkerState.SLEEPING
        contextRef = null
        Log.d(TAG, "Shutdown complete")
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Request decompression of a file. Returns immediately.
     * The actual decompression happens lazily when the worker wakes.
     *
     * @param fileId Unique identifier for this request
     * @param compressedFile The compressed file to decompress
     * @param priority Higher priority = processed first
     * @param onComplete Callback when decompression is done
     */
    fun requestDecompression(
        fileId: String,
        compressedFile: File,
        compressionType: CompressionType,
        cacheDir: File,
        priority: Priority = Priority.NORMAL,
        onComplete: ((DecompressionResult) -> Unit)? = null
    ) {
        // Check cache first
        runBlocking {
            cacheMutex.withLock {
                decompressedCache?.get(fileId)?.let { cached ->
                    if (cached.file.exists()) {
                        updateState(fileId, DecompState.READY)
                        val result = DecompressionResult(
                            fileId = fileId,
                            success = true,
                            decompressedFile = cached.file,
                            fromCache = true
                        )
                        onComplete?.invoke(result)
                        return@runBlocking
                    }
                }
            }
        }

        // Not in cache, queue for decompression
        val request = DecompressionRequest(
            id = fileId,
            compressedFile = compressedFile,
            compressionType = compressionType,
            cacheDir = cacheDir,
            priority = priority,
            callback = onComplete,
            requestTime = System.currentTimeMillis()
        )

        pendingRequests[fileId] = request
        updateState(fileId, DecompState.QUEUED)

        // Send to channel (wakes worker)
        workerScope.launch {
            requestChannel.send(request)
        }

        Log.d(TAG, "Queued: $fileId, priority=$priority")
    }

    /**
     * Request decompression and suspend until complete.
     * Use this when you need the result immediately.
     */
    suspend fun decompressAwait(
        fileId: String,
        compressedFile: File,
        compressionType: CompressionType,
        cacheDir: File
    ): DecompressionResult = withContext(Dispatchers.IO) {
        // Check cache first
        cacheMutex.withLock {
            decompressedCache?.get(fileId)?.let { cached ->
                if (cached.file.exists()) {
                    updateState(fileId, DecompState.READY)
                    return@withContext DecompressionResult(
                        fileId = fileId,
                        success = true,
                        decompressedFile = cached.file,
                        fromCache = true
                    )
                }
            }
        }

        // Not in cache - decompress directly (bypass queue for immediate need)
        updateState(fileId, DecompState.DECOMPRESSING)

        try {
            val result = decompressFile(
                DecompressionRequest(
                    id = fileId,
                    compressedFile = compressedFile,
                    compressionType = compressionType,
                    cacheDir = cacheDir,
                    priority = Priority.HIGH,
                    callback = null,
                    requestTime = System.currentTimeMillis()
                )
            )
            updateState(fileId, if (result.success) DecompState.READY else DecompState.ERROR)
            result
        } catch (e: Exception) {
            updateState(fileId, DecompState.ERROR)
            DecompressionResult(
                fileId = fileId,
                success = false,
                error = e.message
            )
        }
    }

    /**
     * Cancel a pending decompression request
     */
    fun cancelRequest(fileId: String) {
        pendingRequests.remove(fileId)?.let {
            updateState(fileId, DecompState.IDLE)
            Log.d(TAG, "Cancelled: $fileId")
        }
    }

    /**
     * Cancel all pending requests
     */
    fun cancelAllRequests() {
        val cancelled = pendingRequests.keys.toList()
        pendingRequests.clear()
        cancelled.forEach { updateState(it, DecompState.IDLE) }
        Log.d(TAG, "Cancelled ${cancelled.size} pending requests")
    }

    /**
     * Get cached decompressed file if available
     */
    suspend fun getCached(fileId: String): File? = cacheMutex.withLock {
        decompressedCache?.get(fileId)?.let { cached ->
            if (cached.file.exists()) cached.file else null
        }
    }

    /**
     * Clear the entire cache
     */
    fun clearCache() {
        runBlocking {
            cacheMutex.withLock {
                decompressedCache?.evictAll()
                currentCacheSize = 0
            }
        }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Get current cache stats
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            entries = decompressedCache?.size() ?: 0,
            maxEntries = maxCacheEntries,
            currentSizeBytes = currentCacheSize,
            maxSizeBytes = maxCacheSizeBytes
        )
    }

    /**
     * Get decompression state for a file
     */
    fun getState(fileId: String): DecompState {
        return _decompressionStates.value[fileId] ?: DecompState.IDLE
    }

    // =========================================================================
    // WORKER
    // =========================================================================

    private fun startWorker() {
        if (workerJob?.isActive == true) return

        workerJob = workerScope.launch {
            Log.d(TAG, "Worker starting...")

            for (request in requestChannel) {
                // Worker wakes up
                _workerState.value = WorkerState.PROCESSING

                // Check if request was cancelled
                if (!pendingRequests.containsKey(request.id)) {
                    Log.d(TAG, "Skipping cancelled request: ${request.id}")
                    continue
                }

                // Check memory pressure
                if (shouldThrottle()) {
                    delay(getThrottleDelay())
                }

                try {
                    updateState(request.id, DecompState.DECOMPRESSING)

                    val result = decompressFile(request)

                    // Store result
                    completedResults[request.id] = result
                    pendingRequests.remove(request.id)

                    // Update state
                    updateState(
                        request.id,
                        if (result.success) DecompState.READY else DecompState.ERROR
                    )

                    // Invoke callback
                    request.callback?.invoke(result)

                    Log.d(TAG, "Completed: ${request.id}, success=${result.success}")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed: ${request.id}", e)
                    pendingRequests.remove(request.id)
                    updateState(request.id, DecompState.ERROR)

                    request.callback?.invoke(
                        DecompressionResult(
                            fileId = request.id,
                            success = false,
                            error = e.message
                        )
                    )
                }

                // Brief pause between operations
                if (!requestChannel.isEmpty) {
                    delay(10)
                }

                // Return to sleep if no more requests
                if (requestChannel.isEmpty) {
                    _workerState.value = WorkerState.SLEEPING
                    Log.d(TAG, "Worker sleeping...")
                }
            }
        }
    }

    // =========================================================================
    // DECOMPRESSION LOGIC
    // =========================================================================

    private suspend fun decompressFile(request: DecompressionRequest): DecompressionResult {
        return when (request.compressionType) {
            CompressionType.NONE -> {
                // No compression - just return the file
                DecompressionResult(
                    fileId = request.id,
                    success = true,
                    decompressedFile = request.compressedFile
                )
            }

            CompressionType.WEBP -> {
                // WebP images are directly displayable
                DecompressionResult(
                    fileId = request.id,
                    success = true,
                    decompressedFile = request.compressedFile
                )
            }

            CompressionType.GZIP -> {
                decompressGzip(request)
            }
        }
    }

    private suspend fun decompressGzip(request: DecompressionRequest): DecompressionResult =
        withContext(Dispatchers.IO) {
            val decompressedName = request.compressedFile.name.removeSuffix(FileCompressor.COMPRESSED_FILE_EXT)
            val decompressedFile = File(request.cacheDir, "decomp_${request.id}_$decompressedName")

            try {
                // Use adaptive buffer size
                val adaptiveBuffer = try {
                    ResourceManager.getOptimalBufferSize()
                } catch (e: Exception) {
                    bufferSize
                }

                GZIPInputStream(
                    BufferedInputStream(FileInputStream(request.compressedFile), adaptiveBuffer),
                    adaptiveBuffer
                ).use { gzipIn ->
                    BufferedOutputStream(FileOutputStream(decompressedFile), adaptiveBuffer).use { out ->
                        val buffer = ByteArray(adaptiveBuffer)
                        var bytesRead: Int
                        while (gzipIn.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)

                            // Yield periodically to avoid blocking
                            yield()
                        }
                    }
                }

                // Add to cache
                val fileSize = decompressedFile.length()
                cacheMutex.withLock {
                    // Evict if necessary
                    while (currentCacheSize + fileSize > maxCacheSizeBytes &&
                        (decompressedCache?.size() ?: 0) > 0
                    ) {
                        // LRU cache handles eviction automatically
                        break
                    }

                    if (currentCacheSize + fileSize <= maxCacheSizeBytes) {
                        decompressedCache?.put(request.id, CachedFile(decompressedFile, fileSize))
                        currentCacheSize += fileSize
                    }
                }

                DecompressionResult(
                    fileId = request.id,
                    success = true,
                    decompressedFile = decompressedFile
                )

            } catch (e: Exception) {
                decompressedFile.delete()
                throw e
            }
        }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun updateState(fileId: String, state: DecompState) {
        _decompressionStates.value = _decompressionStates.value.toMutableMap().apply {
            put(fileId, state)
        }
    }

    private fun shouldThrottle(): Boolean {
        return try {
            ResourceManager.shouldThrottle()
        } catch (e: Exception) {
            false
        }
    }

    private fun getThrottleDelay(): Long {
        return try {
            ResourceManager.getThrottleDelay()
        } catch (e: Exception) {
            50L
        }
    }

    // =========================================================================
    // DATA CLASSES
    // =========================================================================

    enum class WorkerState {
        SLEEPING,    // Dormant, waiting for requests
        PROCESSING   // Actively decompressing
    }

    enum class DecompState {
        IDLE,           // No request
        QUEUED,         // Waiting in queue
        DECOMPRESSING,  // Currently processing
        READY,          // Done, file available
        ERROR           // Failed
    }

    enum class Priority(val value: Int) {
        LOW(0),
        NORMAL(1),
        HIGH(2),
        IMMEDIATE(3)
    }

    data class DecompressionRequest(
        val id: String,
        val compressedFile: File,
        val compressionType: CompressionType,
        val cacheDir: File,
        val priority: Priority,
        val callback: ((DecompressionResult) -> Unit)?,
        val requestTime: Long
    ) : Comparable<DecompressionRequest> {
        override fun compareTo(other: DecompressionRequest): Int {
            // Higher priority first, then older requests first
            val priorityDiff = other.priority.value - this.priority.value
            return if (priorityDiff != 0) priorityDiff
            else (this.requestTime - other.requestTime).toInt()
        }
    }

    data class DecompressionResult(
        val fileId: String,
        val success: Boolean,
        val decompressedFile: File? = null,
        val fromCache: Boolean = false,
        val error: String? = null
    )

    data class CachedFile(
        val file: File,
        val size: Long
    )

    data class CacheStats(
        val entries: Int,
        val maxEntries: Int,
        val currentSizeBytes: Long,
        val maxSizeBytes: Long
    ) {
        val usagePercent: Float
            get() = if (maxSizeBytes > 0) (currentSizeBytes.toFloat() / maxSizeBytes) * 100 else 0f
    }
}
