package com.example.smarty.core.common.util

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image caching utility for performance optimization
 * Reduces network calls and improves load times
 */
class ImageCache private constructor() {
    private val memoryCache: LruCache<String, Bitmap>

    init {
        // Use 1/8 of available memory for image cache
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        memoryCache =
            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(
                    key: String,
                    bitmap: Bitmap,
                ): Int = bitmap.byteCount / 1024
            }
    }

    fun get(url: String): Bitmap? = memoryCache.get(url)

    fun put(
        url: String,
        bitmap: Bitmap,
    ) {
        if (get(url) == null) {
            memoryCache.put(url, bitmap)
        }
    }

    fun remove(url: String) {
        memoryCache.remove(url)
    }

    fun clear() {
        memoryCache.evictAll()
    }

    companion object {
        @Volatile private var instance: ImageCache? = null

        fun getInstance(): ImageCache =
            instance ?: synchronized(this) {
                instance ?: ImageCache().also { instance = it }
            }
    }
}

/**
 * Debounce utility for search and input
 * Prevents excessive API calls
 */
class Debouncer(
    private val delayMs: Long = 300L,
) {
    private var lastExecutionTime = 0L

    suspend fun <T> execute(action: suspend () -> T): T? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastExecutionTime >= delayMs) {
            lastExecutionTime = currentTime
            return withContext(Dispatchers.Default) {
                try {
                    action()
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }
}

/**
 * Request throttling for API calls
 * Limits requests per time window
 */
class RateLimiter(
    private val maxRequests: Int = 10,
    private val timeWindowMs: Long = 60000L, // 1 minute
) {
    private val requestTimes = mutableListOf<Long>()

    @Synchronized
    fun <T> execute(action: () -> T): T? {
        val currentTime = System.currentTimeMillis()

        // Remove old requests outside time window
        requestTimes.removeAll { it < currentTime - timeWindowMs }

        // Check if under limit
        if (requestTimes.size < maxRequests) {
            requestTimes.add(currentTime)
            return try {
                action()
            } catch (e: Exception) {
                null
            }
        }

        // Rate limited
        return null
    }
}

/**
 * Lazy loading helper
 * Loads content only when needed
 */
class LazyLoader<T> {
    private var cachedValue: T? = null
    private var isLoading = false

    suspend fun load(loader: suspend () -> T): T? {
        if (cachedValue != null) {
            return cachedValue
        }

        if (isLoading) {
            return null // Already loading
        }

        isLoading = true
        return try {
            cachedValue =
                withContext(Dispatchers.Default) {
                    loader()
                }
            cachedValue
        } finally {
            isLoading = false
        }
    }

    fun getCached(): T? = cachedValue

    fun clear() {
        cachedValue = null
    }
}

/**
 * Pagination helper for large lists
 * Loads data in chunks
 */
class PaginationHelper<T>(
    private val pageSize: Int = 20,
) {
    private var currentPage = 0
    private var hasMore = true
    private val loadedItems = mutableListOf<T>()

    suspend fun loadPage(loader: suspend (page: Int, size: Int) -> List<T>): List<T> {
        if (!hasMore) return emptyList()

        val newItems =
            withContext(Dispatchers.Default) {
                loader(currentPage, pageSize)
            }

        if (newItems.size < pageSize) {
            hasMore = false
        }

        loadedItems.addAll(newItems)
        currentPage++

        return newItems
    }

    fun getAllLoaded(): List<T> = loadedItems.toList()

    fun hasMore(): Boolean = hasMore

    fun reset() {
        currentPage = 0
        hasMore = true
        loadedItems.clear()
    }
}

/**
 * Batch operation helper
 * Groups operations for efficiency
 */
class BatchProcessor<T, R>(
    private val batchSize: Int = 10,
    private val delayMs: Long = 100L,
) {
    private val queue = mutableListOf<T>()
    private var lastProcessTime = 0L

    suspend fun add(item: T): R? {
        queue.add(item)

        val currentTime = System.currentTimeMillis()
        if (queue.size >= batchSize || currentTime - lastProcessTime >= delayMs) {
            return processBatch()
        }

        return null
    }

    private suspend fun processBatch(): R? {
        if (queue.isEmpty()) return null

        val batch = queue.take(batchSize)
        queue.removeAll(batch)

        lastProcessTime = System.currentTimeMillis()

        return withContext(Dispatchers.Default) {
            // Process batch
            // Return result
            null as R?
        }
    }

    fun clear() {
        queue.clear()
    }
}

/**
 * Performance monitoring utility
 * Tracks operation execution times
 */
class PerformanceMonitor {
    private val timings = mutableMapOf<String, MutableList<Long>>()

    fun startTimer(key: String): Long = System.currentTimeMillis()

    fun endTimer(
        key: String,
        startTime: Long,
    ) {
        val duration = System.currentTimeMillis() - startTime
        timings.getOrPut(key) { mutableListOf() }.add(duration)
    }

    fun getAverageTime(key: String): Long {
        val times = timings[key] ?: return 0
        return if (times.isEmpty()) 0 else times.average().toLong()
    }

    fun getStats(): Map<String, Long> =
        timings.mapValues { (_, times) ->
            if (times.isEmpty()) 0 else times.average().toLong()
        }

    fun clear() {
        timings.clear()
    }

    companion object {
        @Volatile private var instance: PerformanceMonitor? = null

        fun getInstance(): PerformanceMonitor =
            instance ?: synchronized(this) {
                instance ?: PerformanceMonitor().also { instance = it }
            }
    }
}
