package com.example.smarty.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages app cache with LRU eviction policy
 * 100 MB limit with automatic cleanup
 */
class CacheManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
        const val MAX_CACHE_SIZE_BYTES = 100L * 1024 * 1024  // 100 MB
        const val CACHE_DIR_NAME = "cogni_cache"

        // Sub-directories
        const val THUMBNAILS_DIR = "thumbnails"
        const val WAVEFORMS_DIR = "waveforms"
        const val METADATA_DIR = "metadata"
        const val YOUTUBE_DIR = "youtube"

        @Volatile
        private var INSTANCE: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CacheManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val mutex = Mutex()

    // LRU tracking - maps file path to last access time
    private val accessTimes = mutableMapOf<String, Long>()

    // Cache root directory
    val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    // Sub-directories
    val thumbnailsDir: File by lazy {
        File(cacheDir, THUMBNAILS_DIR).apply { mkdirs() }
    }

    val waveformsDir: File by lazy {
        File(cacheDir, WAVEFORMS_DIR).apply { mkdirs() }
    }

    val metadataDir: File by lazy {
        File(cacheDir, METADATA_DIR).apply { mkdirs() }
    }

    val youtubeDir: File by lazy {
        File(cacheDir, YOUTUBE_DIR).apply { mkdirs() }
    }

    /**
     * Get current total cache size in bytes
     */
    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        calculateDirectorySize(cacheDir)
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Evict oldest entries if cache exceeds max size
     */
    suspend fun evictIfNeeded() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var currentSize = getCacheSize()

            if (currentSize <= MAX_CACHE_SIZE_BYTES) {
                Log.d(TAG, "Cache size OK: ${currentSize / 1024}KB / ${MAX_CACHE_SIZE_BYTES / 1024}KB")
                return@withContext
            }

            Log.i(TAG, "Cache eviction needed. Current: ${currentSize / 1024}KB, Max: ${MAX_CACHE_SIZE_BYTES / 1024}KB")

            // Get all cache entries sorted by last access time (oldest first)
            val entries = getAllCacheEntries().sortedBy { it.lastAccessTime }

            for (entry in entries) {
                if (currentSize <= MAX_CACHE_SIZE_BYTES * 0.8) { // Target 80% of max
                    break
                }

                if (entry.file.delete()) {
                    currentSize -= entry.size
                    accessTimes.remove(entry.file.absolutePath)
                    Log.d(TAG, "Evicted: ${entry.key} (${entry.size / 1024}KB)")
                }
            }

            Log.i(TAG, "Cache eviction complete. New size: ${currentSize / 1024}KB")
        }
    }

    /**
     * Clear all cache
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            accessTimes.clear()
            Log.i(TAG, "Cache cleared")
        }
    }

    /**
     * Trim cache to a target size (for memory optimization when app is backgrounded)
     */
    suspend fun trimToSize(targetSizeBytes: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            var currentSize = getCacheSize()

            if (currentSize <= targetSizeBytes) {
                Log.d(TAG, "Cache already under target size: ${currentSize / 1024}KB <= ${targetSizeBytes / 1024}KB")
                return@withContext
            }

            Log.i(TAG, "Trimming cache from ${currentSize / 1024}KB to ${targetSizeBytes / 1024}KB")

            // Get all cache entries sorted by last access time (oldest first)
            val entries = getAllCacheEntries().sortedBy { it.lastAccessTime }

            for (entry in entries) {
                if (currentSize <= targetSizeBytes) {
                    break
                }

                if (entry.file.delete()) {
                    currentSize -= entry.size
                    accessTimes.remove(entry.file.absolutePath)
                    Log.d(TAG, "Trimmed: ${entry.key} (${entry.size / 1024}KB)")
                }
            }

            Log.i(TAG, "Cache trim complete. New size: ${currentSize / 1024}KB")
        }
    }

    /**
     * Clear entries older than maxAgeMs
     */
    suspend fun clearOldEntries(maxAgeMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cutoffTime = System.currentTimeMillis() - maxAgeMs
            var deletedCount = 0
            var deletedSize = 0L

            getAllCacheEntries()
                .filter { it.lastAccessTime < cutoffTime }
                .forEach { entry ->
                    if (entry.file.delete()) {
                        deletedCount++
                        deletedSize += entry.size
                        accessTimes.remove(entry.file.absolutePath)
                    }
                }

            Log.i(TAG, "Cleared $deletedCount old entries (${deletedSize / 1024}KB)")
        }
    }

    /**
     * Get all cache entries with metadata
     */
    private fun getAllCacheEntries(): List<CacheEntry> {
        val entries = mutableListOf<CacheEntry>()

        fun addEntriesFromDir(dir: File, type: CacheType) {
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                entries.add(
                    CacheEntry(
                        key = file.nameWithoutExtension,
                        file = file,
                        size = file.length(),
                        lastAccessTime = accessTimes[file.absolutePath] ?: file.lastModified(),
                        createdTime = file.lastModified(),
                        type = type
                    )
                )
            }
        }

        addEntriesFromDir(thumbnailsDir, CacheType.THUMBNAIL)
        addEntriesFromDir(waveformsDir, CacheType.WAVEFORM)
        addEntriesFromDir(metadataDir, CacheType.METADATA)
        addEntriesFromDir(youtubeDir, CacheType.YOUTUBE)

        return entries
    }

    // ========== Thumbnail Operations ==========

    /**
     * Cache a thumbnail bitmap
     */
    suspend fun cacheThumbnail(key: String, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        try {
            val safeKey = hashKey(key)
            val file = File(thumbnailsDir, "$safeKey.jpg")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            updateAccessTime(file)
            evictIfNeeded()

            Log.d(TAG, "Cached thumbnail: $key -> ${file.name}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache thumbnail: ${e.message}")
            null
        }
    }

    /**
     * Get cached thumbnail
     */
    suspend fun getThumbnail(key: String): Bitmap? = withContext(Dispatchers.IO) {
        val safeKey = hashKey(key)
        val file = File(thumbnailsDir, "$safeKey.jpg")

        if (file.exists()) {
            updateAccessTime(file)
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode thumbnail: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    /**
     * Get thumbnail file path (for Coil/image loaders)
     */
    fun getThumbnailFile(key: String): File {
        val safeKey = hashKey(key)
        return File(thumbnailsDir, "$safeKey.jpg")
    }

    // ========== Waveform Operations ==========

    /**
     * Cache waveform data
     */
    suspend fun cacheWaveform(audioId: String, data: List<Float>) = withContext(Dispatchers.IO) {
        try {
            val safeKey = hashKey(audioId)
            val file = File(waveformsDir, "$safeKey.waveform")

            file.writeText(data.joinToString(","))
            updateAccessTime(file)
            evictIfNeeded()

            Log.d(TAG, "Cached waveform: $audioId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache waveform: ${e.message}")
        }
    }

    /**
     * Get cached waveform data
     */
    suspend fun getWaveform(audioId: String): List<Float>? = withContext(Dispatchers.IO) {
        val safeKey = hashKey(audioId)
        val file = File(waveformsDir, "$safeKey.waveform")

        if (file.exists()) {
            updateAccessTime(file)
            try {
                file.readText().split(",").mapNotNull { it.toFloatOrNull() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read waveform: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    // ========== Metadata Operations ==========

    /**
     * Cache JSON metadata
     */
    suspend fun cacheMetadata(key: String, json: String) = withContext(Dispatchers.IO) {
        try {
            val safeKey = hashKey(key)
            val file = File(metadataDir, "$safeKey.json")

            file.writeText(json)
            updateAccessTime(file)
            evictIfNeeded()

            Log.d(TAG, "Cached metadata: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache metadata: ${e.message}")
        }
    }

    /**
     * Get cached metadata
     */
    suspend fun getMetadata(key: String): String? = withContext(Dispatchers.IO) {
        val safeKey = hashKey(key)
        val file = File(metadataDir, "$safeKey.json")

        if (file.exists()) {
            updateAccessTime(file)
            try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read metadata: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    // ========== YouTube Operations ==========

    /**
     * Cache YouTube-related data
     */
    suspend fun cacheYouTubeData(videoId: String, data: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(youtubeDir, "$videoId.json")
            file.writeText(data)
            updateAccessTime(file)
            evictIfNeeded()
            Log.d(TAG, "Cached YouTube data: $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache YouTube data: ${e.message}")
        }
    }

    /**
     * Get cached YouTube data
     */
    suspend fun getYouTubeData(videoId: String): String? = withContext(Dispatchers.IO) {
        val file = File(youtubeDir, "$videoId.json")

        if (file.exists()) {
            updateAccessTime(file)
            try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read YouTube data: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    // ========== Helper Methods ==========

    private fun updateAccessTime(file: File) {
        accessTimes[file.absolutePath] = System.currentTimeMillis()
    }

    /**
     * Hash a key to create a safe filename
     */
    private fun hashKey(key: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(key.toByteArray())
            hash.fold("") { str, byte -> str + "%02x".format(byte) }
        } catch (e: Exception) {
            // Fallback to sanitized key
            key.replace(Regex("[^a-zA-Z0-9]"), "_").take(64)
        }
    }
}
