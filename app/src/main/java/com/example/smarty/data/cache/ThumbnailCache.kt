package com.example.smarty.data.cache

import android.graphics.Bitmap
import java.io.File

/**
 * Specialized cache for image thumbnails
 * Wrapper around CacheManager for type-safe thumbnail operations
 */
class ThumbnailCache(
    private val cacheManager: CacheManager
) {
    /**
     * Get cached thumbnail file if exists
     */
    suspend fun get(url: String): File? {
        val file = cacheManager.getThumbnailFile(url)
        return if (file.exists()) file else null
    }

    /**
     * Cache a thumbnail bitmap
     */
    suspend fun put(url: String, bitmap: Bitmap): File? {
        return cacheManager.cacheThumbnail(url, bitmap)
    }

    /**
     * Remove a cached thumbnail
     */
    suspend fun remove(url: String) {
        val file = cacheManager.getThumbnailFile(url)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Get the cache file path for a URL (may or may not exist)
     */
    fun getCacheFile(url: String): File {
        return cacheManager.getThumbnailFile(url)
    }

    /**
     * Check if thumbnail exists in cache
     */
    fun exists(url: String): Boolean {
        return cacheManager.getThumbnailFile(url).exists()
    }
}
