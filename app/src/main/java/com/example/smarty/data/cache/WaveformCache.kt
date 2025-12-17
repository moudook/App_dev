package com.example.smarty.data.cache

/**
 * Cache for audio waveform data
 * Stores pre-computed amplitude arrays for quick visualization
 */
class WaveformCache(
    private val cacheManager: CacheManager
) {
    /**
     * Get cached waveform data for an audio URI
     */
    suspend fun get(audioUri: String): List<Float>? {
        return cacheManager.getWaveform(audioUri)
    }

    /**
     * Cache waveform data for an audio URI
     */
    suspend fun put(audioUri: String, waveform: List<Float>) {
        cacheManager.cacheWaveform(audioUri, waveform)
    }

    /**
     * Remove cached waveform for an audio URI
     */
    suspend fun remove(audioUri: String) {
        // Generate the same key that CacheManager uses
        val file = java.io.File(cacheManager.waveformsDir, hashKey(audioUri) + ".waveform")
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Check if waveform exists in cache
     */
    suspend fun exists(audioUri: String): Boolean {
        return get(audioUri) != null
    }

    private fun hashKey(key: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val hash = digest.digest(key.toByteArray())
            hash.fold("") { str, byte -> str + "%02x".format(byte) }
        } catch (e: Exception) {
            key.replace(Regex("[^a-zA-Z0-9]"), "_").take(64)
        }
    }
}
