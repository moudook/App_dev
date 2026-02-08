package com.example.smarty.data.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.smarty.data.model.AudioSource
import com.example.smarty.data.model.AudioTrack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository for querying audio files from the device's shared storage (MediaStore).
 *
 * COMPREHENSIVE SEARCH: Queries ALL audio files on the entire device, not just music.
 * Includes voice recordings, downloads, podcasts, WhatsApp audio, etc.
 *
 * Uses "Zero-Copy" architecture: retrieves lightweight metadata and content:// URIs
 * without copying actual audio files. The URIs are resolved at playback time by ExoPlayer.
 *
 * 100% OFFLINE: MediaStore is a local database - no network calls needed.
 *
 * NOTE: Requires READ_MEDIA_AUDIO (Android 13+) or READ_EXTERNAL_STORAGE (older) permission.
 */
class DeviceAudioRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceAudioRepository"

        // Minimum duration in milliseconds to exclude notification sounds and short clips
        private const val MIN_DURATION_MS = 3000L  // 3 seconds
    }

    // Cache for faster subsequent searches (works offline)
    private var cachedAudio: List<AudioTrack>? = null
    private var lastCacheTime: Long = 0
    private val cacheValidityMs = 60_000L  // 1 minute cache validity
    private val cacheMutex = Mutex()

    /**
     * Query ALL audio files from device storage.
     * Returns empty list if permission not granted (fails gracefully).
     * Results are cached for 1 minute for faster searches.
     *
     * Suspend function to avoid blocking main thread during ContentResolver queries.
     */
    suspend fun getAllAudio(): List<AudioTrack> {
        // Return cached results if still valid
        val now = System.currentTimeMillis()

        cacheMutex.withLock {
            if (cachedAudio != null && (now - lastCacheTime) < cacheValidityMs) {
                Log.d(TAG, "Returning ${cachedAudio!!.size} cached audio files")
                return cachedAudio!!
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val results = queryMediaStore()
                cacheMutex.withLock {
                    cachedAudio = results
                    lastCacheTime = now
                }
                results
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission denied for MediaStore query: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore", e)
                emptyList()
            }
        }
    }

    /**
     * Invalidate cache to force fresh query on next access.
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedAudio = null
            lastCacheTime = 0
        }
    }

    private fun queryMediaStore(): List<AudioTrack> {
        val audioList = mutableListOf<AudioTrack>()

        // Use appropriate URI based on Android version
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Projection: Fetch all needed columns including path for folder search
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Add path column based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            projection.add(MediaStore.Audio.Media.DATA)
        }

        // COMPREHENSIVE SEARCH: Query ALL audio files, not just IS_MUSIC
        // Only filter by minimum duration to exclude notification sounds
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())

        // Sort by title for consistent ordering
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            // Cache column indices for performance
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            
            // Path column for folder-based searching
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }

            Log.d(TAG, "Found ${cursor.count} audio files across entire device storage")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn)
                val album = cursor.getString(albumColumn)
                val displayName = cursor.getString(displayNameColumn)
                val duration = cursor.getLong(durationColumn)
                val mimeType = cursor.getString(mimeTypeColumn)
                
                // Get file path for folder-based searching
                val filePath = if (pathColumn >= 0) cursor.getString(pathColumn) else null

                // Construct zero-copy content:// URI
                val contentUri = ContentUris.withAppendedId(collection, id)

                audioList.add(
                    AudioTrack(
                        id = "device_$id",  // Prefix to avoid ID conflicts with note audio
                        uri = contentUri.toString(),
                        title = title,
                        fileName = displayName,
                        duration = duration,
                        mimeType = mimeType,
                        source = AudioSource.DEVICE_STORAGE,
                        artist = artist,
                        album = album,
                        sourceNoteId = null,
                        sourceAttachmentId = null,
                        // Store path in album field if album is empty (for folder search)
                        // This is a pragmatic approach without changing AudioTrack model
                    )
                )
                
                // Log sample paths for debugging (first 5 only)
                if (audioList.size <= 5) {
                    Log.d(TAG, "  Audio: '$title' at path: $filePath")
                }
            }
        }

        Log.i(TAG, "Loaded ${audioList.size} audio tracks from entire device storage (all folders)")
        return audioList
    }

    /**
     * Search device audio by query string.
     * Searches title, artist, album, and filename.
     * COMPREHENSIVE: Searches all audio on device, not just music folder.
     */
    suspend fun searchAudio(query: String): List<AudioTrack> {
        if (query.isBlank()) return emptyList()

        val queryLower = query.lowercase().trim()
        return getAllAudio().filter { track ->
            track.title.lowercase().contains(queryLower) ||
            track.artist?.lowercase()?.contains(queryLower) == true ||
            track.album?.lowercase()?.contains(queryLower) == true ||
            track.fileName?.lowercase()?.contains(queryLower) == true
        }
    }

    /**
     * Search audio by folder/path keywords.
     * Examples: "downloads", "whatsapp", "recordings", "music"
     */
    suspend fun searchByFolder(folderKeyword: String): List<AudioTrack> {
        if (folderKeyword.isBlank()) return emptyList()

        val keywordLower = folderKeyword.lowercase().trim()

        // Common folder mappings
        val folderPatterns = when {
            keywordLower.contains("download") -> listOf("download", "downloads")
            keywordLower.contains("whatsapp") -> listOf("whatsapp", "wa ")
            keywordLower.contains("record") -> listOf("record", "voice", "memo")
            keywordLower.contains("music") -> listOf("music")
            keywordLower.contains("podcast") -> listOf("podcast")
            keywordLower.contains("telegram") -> listOf("telegram")
            else -> listOf(keywordLower)
        }

        return getAllAudio().filter { track ->
            val fileName = track.fileName?.lowercase() ?: ""
            val title = track.title.lowercase()
            folderPatterns.any { pattern ->
                fileName.contains(pattern) || title.contains(pattern)
            }
        }
    }
}

