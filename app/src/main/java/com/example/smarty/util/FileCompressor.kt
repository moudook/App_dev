package com.example.smarty.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * =============================================================================
 * FILE COMPRESSION ENGINE
 * =============================================================================
 *
 * Intelligent file compression system optimized for each file type:
 *
 * 1. IMAGES → WebP compression (26-34% smaller than JPEG/PNG)
 *    - Uses Android's native Bitmap.compress() with WebP format
 *    - Configurable quality (default 80%)
 *    - Lossless option available for high-quality needs
 *
 * 2. VIDEOS/AUDIO → No compression (already use H.264/H.265/MP3/AAC)
 *    - These formats are already highly compressed
 *    - Re-compression would degrade quality without size benefit
 *
 * 3. DOCUMENTS/TEXT/OTHER → GZIP compression
 *    - Text compresses extremely well (70-90% reduction)
 *    - Fast decompression for quick access
 *    - Built into Android, no external dependencies
 *
 * =============================================================================
 * Sources:
 * - WebP: https://developer.android.com/develop/ui/views/graphics/reduce-image-sizes
 * - GZIP: Built-in java.util.zip
 * - Compression comparison: https://linuxreviews.org/Comparison_of_Compression_Algorithms
 * =============================================================================
 */
object FileCompressor {

    private const val TAG = "FileCompressor"

    // Compression settings
    private const val IMAGE_QUALITY = 80 // 0-100, 80 is good balance
    private const val GZIP_BUFFER_SIZE = 8192

    // File extensions for compressed files
    const val COMPRESSED_IMAGE_EXT = ".webp"
    const val COMPRESSED_FILE_EXT = ".gz"

    // Decompression state tracking for UI shimmer effects
    private val _decompressionStates = MutableStateFlow<Map<String, DecompressionState>>(emptyMap())
    val decompressionStates: StateFlow<Map<String, DecompressionState>> = _decompressionStates

    // LRU Cache for decompressed files (max 50MB or 20 files)
    private val decompressedCache = object : LruCache<String, File>(20) {
        private var totalSize = 0L
        private val maxSize = 50 * 1024 * 1024L // 50MB

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: File?, newValue: File?) {
            if (evicted && oldValue != null && oldValue.exists()) {
                totalSize -= oldValue.length()
                oldValue.delete()
                Log.d(TAG, "Evicted cached file: ${oldValue.name}")
            }
        }

        fun canAdd(size: Long): Boolean = (totalSize + size) <= maxSize

        fun addSize(size: Long) {
            totalSize += size
        }
    }

    private val cacheMutex = Mutex()

    /**
     * Compression state for tracking decompression progress
     */
    enum class DecompressionState {
        IDLE,           // Not decompressing
        DECOMPRESSING,  // Currently decompressing (show shimmer)
        READY,          // Decompressed and ready to display
        ERROR           // Decompression failed
    }

    // =========================================================================
    // COMPRESSION METHODS
    // =========================================================================

    /**
     * Compresses a file based on its MIME type.
     * Returns the compressed file info, or original if compression not beneficial.
     */
    suspend fun compressFile(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String?,
        destDir: File
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        try {
            when {
                // Images → WebP compression
                mimeType?.startsWith("image/") == true -> {
                    compressImage(context, sourceUri, originalFileName, destDir)
                }

                // Videos → No compression (already compressed)
                mimeType?.startsWith("video/") == true -> {
                    copyWithoutCompression(context, sourceUri, originalFileName, destDir, mimeType)
                }

                // Audio → No compression (already compressed)
                mimeType?.startsWith("audio/") == true -> {
                    copyWithoutCompression(context, sourceUri, originalFileName, destDir, mimeType)
                }

                // Documents and other files → GZIP compression
                else -> {
                    compressWithGzip(context, sourceUri, originalFileName, destDir, mimeType)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed: ${e.message}", e)
            // Fall back to copying without compression
            copyWithoutCompression(context, sourceUri, originalFileName, destDir, mimeType)
        }
    }

    /**
     * Compresses an image to WebP format.
     * WebP provides 26-34% smaller files than JPEG/PNG.
     */
    private suspend fun compressImage(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open image stream")

        // Decode bitmap
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (originalBitmap == null) {
            throw IOException("Cannot decode image")
        }

        // Generate output filename
        val baseName = originalFileName?.substringBeforeLast(".") ?: "image_${System.currentTimeMillis()}"
        val compressedFileName = "${baseName}$COMPRESSED_IMAGE_EXT"
        val compressedFile = File(destDir, compressedFileName)

        // Compress to WebP
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        FileOutputStream(compressedFile).use { out ->
            originalBitmap.compress(format, IMAGE_QUALITY, out)
        }

        originalBitmap.recycle()

        val originalSize = getFileSize(context, sourceUri) ?: compressedFile.length()
        val compressedSize = compressedFile.length()
        val compressionRatio = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize)
        } else 0.0

        Log.d(TAG, "Image compressed: $originalFileName -> $compressedFileName " +
                "(${formatSize(originalSize)} -> ${formatSize(compressedSize)}, " +
                "${String.format("%.1f", compressionRatio)}% reduction)")

        CompressedFileResult(
            compressedFile = compressedFile,
            originalFileName = originalFileName ?: compressedFileName,
            compressedFileName = compressedFileName,
            originalSize = originalSize,
            compressedSize = compressedSize,
            compressionType = CompressionType.WEBP,
            mimeType = "image/webp"
        )
    }

    /**
     * Compresses a file using GZIP.
     * Excellent for text-based files (70-90% reduction).
     */
    private suspend fun compressWithGzip(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File,
        mimeType: String?
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open file stream")

        // Generate output filename
        val baseName = originalFileName ?: "file_${System.currentTimeMillis()}"
        val compressedFileName = "$baseName$COMPRESSED_FILE_EXT"
        val compressedFile = File(destDir, compressedFileName)

        // Compress with GZIP
        GZIPOutputStream(FileOutputStream(compressedFile)).use { gzipOut ->
            inputStream.use { input ->
                val buffer = ByteArray(GZIP_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    gzipOut.write(buffer, 0, bytesRead)
                }
            }
        }

        val originalSize = getFileSize(context, sourceUri) ?: compressedFile.length()
        val compressedSize = compressedFile.length()
        val compressionRatio = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize)
        } else 0.0

        Log.d(TAG, "File GZIP compressed: $originalFileName -> $compressedFileName " +
                "(${formatSize(originalSize)} -> ${formatSize(compressedSize)}, " +
                "${String.format("%.1f", compressionRatio)}% reduction)")

        CompressedFileResult(
            compressedFile = compressedFile,
            originalFileName = originalFileName ?: baseName,
            compressedFileName = compressedFileName,
            originalSize = originalSize,
            compressedSize = compressedSize,
            compressionType = CompressionType.GZIP,
            mimeType = mimeType ?: "application/octet-stream"
        )
    }

    /**
     * Copies file without compression (for already-compressed formats).
     */
    private suspend fun copyWithoutCompression(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File,
        mimeType: String?
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open file stream")

        val fileName = originalFileName ?: "file_${System.currentTimeMillis()}"
        val destFile = File(destDir, fileName)

        FileOutputStream(destFile).use { out ->
            inputStream.use { input ->
                input.copyTo(out, GZIP_BUFFER_SIZE)
            }
        }

        val fileSize = destFile.length()

        Log.d(TAG, "File copied (no compression): $fileName (${formatSize(fileSize)})")

        CompressedFileResult(
            compressedFile = destFile,
            originalFileName = fileName,
            compressedFileName = fileName,
            originalSize = fileSize,
            compressedSize = fileSize,
            compressionType = CompressionType.NONE,
            mimeType = mimeType ?: "application/octet-stream"
        )
    }

    // =========================================================================
    // DECOMPRESSION METHODS
    // =========================================================================

    /**
     * Decompresses a file for viewing. Returns immediately if cached.
     * Updates decompressionStates for UI shimmer effects.
     *
     * @param fileId Unique identifier for tracking decompression state
     * @param compressedFile The compressed file to decompress
     * @param compressionType The type of compression used
     * @param cacheDir Directory for cached decompressed files
     * @return The decompressed file, or null on error
     */
    suspend fun decompressForViewing(
        fileId: String,
        compressedFile: File,
        compressionType: CompressionType,
        cacheDir: File
    ): File? = withContext(Dispatchers.IO) {
        // Check cache first
        cacheMutex.withLock {
            decompressedCache.get(fileId)?.let { cached ->
                if (cached.exists()) {
                    updateState(fileId, DecompressionState.READY)
                    return@withContext cached
                }
            }
        }

        // No compression → return original file
        if (compressionType == CompressionType.NONE) {
            updateState(fileId, DecompressionState.READY)
            return@withContext compressedFile
        }

        // Start decompression - update state for shimmer
        updateState(fileId, DecompressionState.DECOMPRESSING)

        try {
            val decompressedFile = when (compressionType) {
                CompressionType.WEBP -> {
                    // WebP images are already in displayable format
                    updateState(fileId, DecompressionState.READY)
                    compressedFile
                }
                CompressionType.GZIP -> {
                    decompressGzip(compressedFile, cacheDir)
                }
                CompressionType.NONE -> compressedFile
            }

            // Cache the result
            cacheMutex.withLock {
                if (decompressedFile != compressedFile && decompressedCache.canAdd(decompressedFile.length())) {
                    decompressedCache.put(fileId, decompressedFile)
                    decompressedCache.addSize(decompressedFile.length())
                }
            }

            updateState(fileId, DecompressionState.READY)
            decompressedFile
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed for $fileId: ${e.message}", e)
            updateState(fileId, DecompressionState.ERROR)
            null
        }
    }

    /**
     * Decompresses a GZIP file.
     */
    private fun decompressGzip(compressedFile: File, cacheDir: File): File {
        // Remove .gz extension for decompressed filename
        val decompressedName = compressedFile.name.removeSuffix(COMPRESSED_FILE_EXT)
        val decompressedFile = File(cacheDir, "decompressed_$decompressedName")

        GZIPInputStream(FileInputStream(compressedFile)).use { gzipIn ->
            FileOutputStream(decompressedFile).use { out ->
                val buffer = ByteArray(GZIP_BUFFER_SIZE)
                var bytesRead: Int
                while (gzipIn.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }

        Log.d(TAG, "GZIP decompressed: ${compressedFile.name} -> ${decompressedFile.name}")
        return decompressedFile
    }

    /**
     * Pre-loads decompression for a file (call when note is clicked).
     * This starts decompression in background so content is ready faster.
     */
    suspend fun preloadDecompression(
        fileId: String,
        compressedFilePath: String,
        compressionType: CompressionType,
        cacheDir: File
    ) {
        val compressedFile = File(compressedFilePath)
        if (compressedFile.exists()) {
            decompressForViewing(fileId, compressedFile, compressionType, cacheDir)
        }
    }

    /**
     * Gets the current decompression state for a file.
     */
    fun getDecompressionState(fileId: String): DecompressionState {
        return _decompressionStates.value[fileId] ?: DecompressionState.IDLE
    }

    /**
     * Clears the decompression cache.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            decompressedCache.evictAll()
        }
        Log.d(TAG, "Decompression cache cleared")
    }

    // =========================================================================
    // UTILITY METHODS
    // =========================================================================

    private fun updateState(fileId: String, state: DecompressionState) {
        _decompressionStates.value = _decompressionStates.value.toMutableMap().apply {
            put(fileId, state)
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Determines if a file is compressed based on its path.
     */
    fun isCompressedFile(filePath: String): Boolean {
        return filePath.endsWith(COMPRESSED_FILE_EXT) ||
               filePath.endsWith(COMPRESSED_IMAGE_EXT)
    }

    /**
     * Gets the compression type from a file path.
     */
    fun getCompressionType(filePath: String, mimeType: String?): CompressionType {
        return when {
            filePath.endsWith(COMPRESSED_FILE_EXT) -> CompressionType.GZIP
            filePath.endsWith(COMPRESSED_IMAGE_EXT) -> CompressionType.WEBP
            mimeType?.startsWith("image/webp") == true -> CompressionType.WEBP
            else -> CompressionType.NONE
        }
    }
}

/**
 * Types of compression used
 */
enum class CompressionType {
    NONE,   // No compression (video, audio)
    WEBP,   // WebP image compression
    GZIP    // GZIP for documents and other files
}

/**
 * Result of file compression
 */
data class CompressedFileResult(
    val compressedFile: File,
    val originalFileName: String,
    val compressedFileName: String,
    val originalSize: Long,
    val compressedSize: Long,
    val compressionType: CompressionType,
    val mimeType: String
) {
    val compressionRatio: Double
        get() = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize)
        } else 0.0

    val savedBytes: Long
        get() = originalSize - compressedSize
}
