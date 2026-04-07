package com.example.smarty.data.cache

import java.io.File

/**
 * Represents a cache entry with metadata for LRU eviction
 */
data class CacheEntry(
    val key: String,
    val file: File,
    val size: Long,
    val lastAccessTime: Long,
    val createdTime: Long,
    val type: CacheType,
)

/**
 * Types of cached content
 */
enum class CacheType {
    THUMBNAIL,
    WAVEFORM,
    METADATA,
    YOUTUBE,
}
