package com.example.smarty.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.zip.Deflater
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

    // =========================================================================
    // HIGH-PERFORMANCE COMPRESSION SETTINGS
    // =========================================================================
    // Buffer sizes adapt to device capabilities via ResourceManager.
    // Defaults are used when ResourceManager is not initialized.
    // =========================================================================

    private const val IMAGE_QUALITY = 80 // 0-100, 80 is good balance

    // Default buffer sizes (adapted at runtime via ResourceManager)
    private const val DEFAULT_FAST_BUFFER_SIZE = 65536        // 64KB
    private const val DEFAULT_BULK_BUFFER_SIZE = 131072       // 128KB
    private const val DEFAULT_NIO_BUFFER_SIZE = 262144        // 256KB
    private const val DEFAULT_MAX_IMAGE_DIMENSION = 2048
    private const val DEFAULT_LARGE_FILE_THRESHOLD = 5 * 1024 * 1024L  // 5MB

    // Adaptive getters - use ResourceManager when available
    private val FAST_BUFFER_SIZE: Int
        get() = try { ResourceManager.getOptimalBufferSize() } catch (e: Exception) { DEFAULT_FAST_BUFFER_SIZE }

    private val BULK_BUFFER_SIZE: Int
        get() = try { ResourceManager.getOptimalBufferSize() * 2 } catch (e: Exception) { DEFAULT_BULK_BUFFER_SIZE }

    private val NIO_BUFFER_SIZE: Int
        get() = try { ResourceManager.getOptimalNioBufferSize() } catch (e: Exception) { DEFAULT_NIO_BUFFER_SIZE }

    private val MAX_IMAGE_DIMENSION: Int
        get() = try { ResourceManager.getMaxImageDimension() } catch (e: Exception) { DEFAULT_MAX_IMAGE_DIMENSION }

    private val LARGE_FILE_THRESHOLD: Long
        get() = try { ResourceManager.getLargeFileThreshold() } catch (e: Exception) { DEFAULT_LARGE_FILE_THRESHOLD }

    // Concurrency control - adapts to device capabilities
    private val maxParallelOps: Int
        get() = try { ResourceManager.getMaxParallelOperations() } catch (e: Exception) { 4 }

    // BUG-016 FIX: Use lazy to create Semaphore ONCE, not on every access
    private val compressionSemaphore: Semaphore by lazy { Semaphore(maxParallelOps) }

    // File extensions for compressed files
    const val COMPRESSED_IMAGE_EXT = ".webp"
    const val COMPRESSED_FILE_EXT = ".gz"

    // Decompression state tracking for UI shimmer effects
    private val _decompressionStates = MutableStateFlow<Map<String, DecompressionState>>(emptyMap())
    val decompressionStates: StateFlow<Map<String, DecompressionState>> = _decompressionStates

    // Memory-aware LRU Cache for decompressed files
    // Adapts size limits based on device capabilities
    private val decompressedCache = object : LruCache<String, File>(getMaxCacheEntries()) {
        private var totalSize = 0L

        private fun getMaxCacheSizeBytes(): Long {
            return try { ResourceManager.getMaxCacheSize() } catch (e: Exception) { 50 * 1024 * 1024L }
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: File?, newValue: File?) {
            if (evicted && oldValue != null && oldValue.exists()) {
                totalSize -= oldValue.length()
                oldValue.delete()
                Log.d(TAG, "Evicted cached file: ${oldValue.name}")
            }
        }

        fun canAdd(size: Long): Boolean = (totalSize + size) <= getMaxCacheSizeBytes()

        fun addSize(size: Long) {
            totalSize += size
        }

        fun getCurrentSize(): Long = totalSize
    }

    private fun getMaxCacheEntries(): Int {
        return try {
            when (ResourceManager.getDeviceClass()) {
                ResourceManager.DeviceClass.EDGE -> 5
                ResourceManager.DeviceClass.LOW -> 10
                ResourceManager.DeviceClass.MEDIUM -> 15
                ResourceManager.DeviceClass.HIGH -> 20
                ResourceManager.DeviceClass.FLAGSHIP -> 30
            }
        } catch (e: Exception) { 20 }
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
     *
     * @param stripMetadata If true, removes all metadata before compression (default: true)
     */
    suspend fun compressFile(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String?,
        destDir: File,
        stripMetadata: Boolean = true
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        try {
            // Strip metadata first if enabled (for privacy and size reduction)
            val (processUri, tempFile) = if (stripMetadata && shouldStripMetadata(mimeType)) {
                stripMetadataFirst(context, sourceUri, mimeType, originalFileName)
            } else {
                Pair(sourceUri, null)
            }

            try {
                val result = when {
                    // Images → WebP compression (metadata already stripped in stripMetadataFirst)
                    mimeType?.startsWith("image/") == true -> {
                        compressImage(context, processUri, originalFileName, destDir)
                    }

                    // Videos → No compression (already compressed)
                    mimeType?.startsWith("video/") == true -> {
                        copyWithoutCompression(context, processUri, originalFileName, destDir, mimeType)
                    }

                    // Audio → No compression (already compressed)
                    mimeType?.startsWith("audio/") == true -> {
                        copyWithoutCompression(context, processUri, originalFileName, destDir, mimeType)
                    }

                    // PDF → No compression (already compressed binary format)
                    mimeType?.contains("pdf") == true -> {
                        copyWithoutCompression(context, processUri, originalFileName, destDir, mimeType)
                    }

                    // Documents and other files → GZIP compression
                    else -> {
                        compressWithGzip(context, processUri, originalFileName, destDir, mimeType)
                    }
                }

                result
            } finally {
                // Clean up temp file if created
                tempFile?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed: ${e.message}", e)
            // Fall back to copying without compression
            copyWithoutCompression(context, sourceUri, originalFileName, destDir, mimeType)
        }
    }

    /**
     * Check if metadata should be stripped for this file type
     */
    private fun shouldStripMetadata(mimeType: String?): Boolean {
        return mimeType?.let {
            it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/")
        } ?: false
    }

    /**
     * Strip metadata before compression for privacy and size reduction.
     * Returns a new URI to the stripped file and the temp file for cleanup.
     */
    private suspend fun stripMetadataFirst(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String?
    ): Pair<Uri, File?> = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "metadata_strip_temp")
            tempDir.mkdirs()

            val baseName = originalFileName?.substringBeforeLast(".")
                ?: "file_${System.currentTimeMillis()}"

            val result = MetadataStripper.stripMetadata(
                context = context,
                sourceUri = sourceUri,
                mimeType = mimeType,
                outputDir = tempDir,
                outputName = baseName
            )

            if (result.success && result.outputFile != null) {
                Log.d(TAG, "Metadata stripped: saved ${result.bytesSaved} bytes " +
                        "(${String.format("%.1f", result.reductionPercent)}%) in ${result.processingTimeMs}ms")
                Pair(Uri.fromFile(result.outputFile), result.outputFile)
            } else {
                // Stripping failed, use original
                Log.w(TAG, "Metadata strip failed: ${result.error}, using original")
                Pair(sourceUri, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata strip exception: ${e.message}, using original")
            Pair(sourceUri, null)
        }
    }

    /**
     * High-performance image compression to WebP format.
     * Optimizations:
     * - Subsampling for large images (reduces memory and speeds decode)
     * - Buffered output stream for faster writes
     * - Optimal WebP quality setting for size/quality balance
     */
    private suspend fun compressImage(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        compressionSemaphore.withPermit {
            // First pass: Get image dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IOException("Cannot open image stream")

            // Calculate optimal sample size for large images
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                MAX_IMAGE_DIMENSION,
                MAX_IMAGE_DIMENSION
            )

            // Second pass: Decode with optimal sampling
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val originalBitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(BufferedInputStream(stream, FAST_BUFFER_SIZE), null, decodeOptions)
            } ?: throw IOException("Cannot decode image")

            // LEAK FIX (LEAK-015): Use try-finally to ALWAYS recycle bitmap
            // Without this, any exception between decode and recycle leaks 4-16MB native memory
            try {
                // Generate output filename
                val baseName = originalFileName?.substringBeforeLast(".") ?: "image_${System.currentTimeMillis()}"
                val compressedFileName = "${baseName}$COMPRESSED_IMAGE_EXT"
                val compressedFile = File(destDir, compressedFileName)

                // Compress to WebP with buffered output
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }

                    BufferedOutputStream(FileOutputStream(compressedFile), FAST_BUFFER_SIZE).use { out ->
                    originalBitmap.compress(format, IMAGE_QUALITY, out)
                }

                val originalSize = getFileSize(context, sourceUri) ?: compressedFile.length()
                val compressedSize = compressedFile.length()
                val compressionRatio = if (originalSize > 0) {
                    ((originalSize - compressedSize) * 100.0 / originalSize)
                } else 0.0

                Log.d(TAG, "Image compressed: $originalFileName -> $compressedFileName " +
                        "(${formatSize(originalSize)} -> ${formatSize(compressedSize)}, " +
                        "${String.format("%.1f", compressionRatio)}% reduction, sample=$sampleSize)")

                CompressedFileResult(
                    compressedFile = compressedFile,
                    originalFileName = originalFileName ?: compressedFileName,
                    compressedFileName = compressedFileName,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    compressionType = CompressionType.WEBP,
                    mimeType = "image/webp"
                )
            } finally {
                // ALWAYS recycle bitmap to prevent native memory leak (4-16MB per image)
                if (!originalBitmap.isRecycled) {
                    originalBitmap.recycle()
                }
            }
        }
    }

    /**
     * Calculate optimal sample size for image subsampling.
     * Uses power-of-2 sampling for fastest decode performance.
     */
    private fun calculateSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var sampleSize = 1
        if (srcWidth > maxWidth || srcHeight > maxHeight) {
            val halfWidth = srcWidth / 2
            val halfHeight = srcHeight / 2
            while ((halfWidth / sampleSize) >= maxWidth && (halfHeight / sampleSize) >= maxHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * High-performance GZIP compression.
     * Optimizations:
     * - BEST_SPEED compression level (level 1) for 2-3x faster compression
     * - 64KB buffers for optimal I/O throughput
     * - Buffered streams to minimize system calls
     * - NIO FileChannel transfer for large files (5MB+)
     */
    private suspend fun compressWithGzip(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File,
        mimeType: String?
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        compressionSemaphore.withPermit {
            val originalSize = getFileSize(context, sourceUri) ?: 0L

            // Generate output filename
            val baseName = originalFileName ?: "file_${System.currentTimeMillis()}"
            val compressedFileName = "$baseName$COMPRESSED_FILE_EXT"
            val compressedFile = File(destDir, compressedFileName)

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Cannot open file stream")

            // Use faster compression with BEST_SPEED (level 1)
            // Trade-off: ~10-15% larger files but 2-3x faster compression
            val deflater = Deflater(Deflater.BEST_SPEED, true)

            try {
                if (originalSize > LARGE_FILE_THRESHOLD) {
                    // Use NIO for large files - zero-copy kernel optimization
                    compressWithNio(inputStream, compressedFile, deflater)
                } else {
                    // Standard buffered compression for smaller files
                    compressWithBufferedStream(inputStream, compressedFile, deflater)
                }
            } finally {
                deflater.end()
                inputStream.close()
            }

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
    }

    /**
     * Buffered GZIP compression for smaller files.
     */
    private fun compressWithBufferedStream(
        input: InputStream,
        destFile: File,
        deflater: Deflater
    ) {
        val bufferedInput = BufferedInputStream(input, FAST_BUFFER_SIZE)
        val gzipOut = object : GZIPOutputStream(
            BufferedOutputStream(FileOutputStream(destFile), FAST_BUFFER_SIZE)
        ) {
            init {
                // Replace default deflater with our fast one
                this.def = deflater
            }
        }

        gzipOut.use { out ->
            bufferedInput.use { inp ->
                val buffer = ByteArray(FAST_BUFFER_SIZE)
                var bytesRead: Int
                while (inp.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    /**
     * NIO-based GZIP compression for large files.
     * Uses FileChannel for optimized kernel-level transfers.
     */
    private fun compressWithNio(
        input: InputStream,
        destFile: File,
        deflater: Deflater
    ) {
        val gzipOut = object : GZIPOutputStream(
            BufferedOutputStream(FileOutputStream(destFile), BULK_BUFFER_SIZE)
        ) {
            init {
                this.def = deflater
            }
        }

        val readChannel = Channels.newChannel(BufferedInputStream(input, BULK_BUFFER_SIZE))
        val directBuffer = ByteBuffer.allocateDirect(NIO_BUFFER_SIZE)

        gzipOut.use { out ->
            readChannel.use { channel ->
                while (channel.read(directBuffer) != -1) {
                    directBuffer.flip()
                    val bytes = ByteArray(directBuffer.remaining())
                    directBuffer.get(bytes)
                    out.write(bytes)
                    directBuffer.clear()
                }
            }
        }
    }

    /**
     * High-performance file copy without compression.
     * Uses NIO FileChannel transfer for optimal kernel-level copy.
     */
    private suspend fun copyWithoutCompression(
        context: Context,
        sourceUri: Uri,
        originalFileName: String?,
        destDir: File,
        mimeType: String?
    ): CompressedFileResult = withContext(Dispatchers.IO) {
        val fileName = originalFileName ?: "file_${System.currentTimeMillis()}"
        val destFile = File(destDir, fileName)
        val originalSize = getFileSize(context, sourceUri) ?: 0L

        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open file stream")

        if (originalSize > LARGE_FILE_THRESHOLD) {
            // NIO transfer for large files
            copyWithNioChannel(inputStream, destFile)
        } else {
            // Buffered copy for smaller files
            BufferedOutputStream(FileOutputStream(destFile), FAST_BUFFER_SIZE).use { out ->
                BufferedInputStream(inputStream, FAST_BUFFER_SIZE).use { input ->
                    input.copyTo(out, FAST_BUFFER_SIZE)
                }
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

    /**
     * NIO-based file copy using FileChannel for zero-copy transfers.
     */
    private fun copyWithNioChannel(input: InputStream, destFile: File) {
        val readChannel = Channels.newChannel(BufferedInputStream(input, BULK_BUFFER_SIZE))
        FileOutputStream(destFile).channel.use { writeChannel ->
            readChannel.use { src ->
                val buffer = ByteBuffer.allocateDirect(NIO_BUFFER_SIZE)
                while (src.read(buffer) != -1) {
                    buffer.flip()
                    writeChannel.write(buffer)
                    buffer.clear()
                }
            }
        }
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
     * High-performance GZIP decompression.
     * Uses buffered streams with optimal buffer sizes.
     */
    private fun decompressGzip(compressedFile: File, cacheDir: File): File {
        // Remove .gz extension for decompressed filename
        val decompressedName = compressedFile.name.removeSuffix(COMPRESSED_FILE_EXT)
        val decompressedFile = File(cacheDir, "decompressed_$decompressedName")

        val gzipIn = GZIPInputStream(
            BufferedInputStream(FileInputStream(compressedFile), FAST_BUFFER_SIZE),
            FAST_BUFFER_SIZE
        )
        val bufOut = BufferedOutputStream(FileOutputStream(decompressedFile), FAST_BUFFER_SIZE)

        gzipIn.use { input ->
            bufOut.use { out ->
                val buffer = ByteArray(FAST_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
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
    // BATCH OPERATIONS - PARALLEL PROCESSING
    // =========================================================================

    /**
     * Input data for batch compression
     */
    data class BatchCompressionInput(
        val sourceUri: Uri,
        val mimeType: String?,
        val originalFileName: String?
    )

    /**
     * Compresses multiple files in parallel for maximum throughput.
     * Uses controlled parallelism to avoid memory pressure.
     *
     * @param inputs List of files to compress
     * @param context Application context
     * @param destDir Destination directory for compressed files
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return List of compression results (in same order as inputs)
     */
    suspend fun compressFilesParallel(
        inputs: List<BatchCompressionInput>,
        context: Context,
        destDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): List<CompressedFileResult> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val results = Array<CompressedFileResult?>(inputs.size) { null }
        var completedCount = 0
        val progressMutex = Mutex()

        // Process files in parallel with semaphore control
        coroutineScope {
            inputs.mapIndexed { index, input ->
                async {
                    val result = compressFile(
                        context = context,
                        sourceUri = input.sourceUri,
                        mimeType = input.mimeType,
                        originalFileName = input.originalFileName,
                        destDir = destDir
                    )
                    results[index] = result

                    // Update progress
                    progressMutex.withLock {
                        completedCount++
                        onProgress?.invoke(completedCount.toFloat() / inputs.size)
                    }

                    result
                }
            }.awaitAll()
        }

        results.filterNotNull()
    }

    /**
     * Fast file copy for multiple files in parallel.
     * Useful for backup operations where compression is not needed.
     */
    suspend fun copyFilesParallel(
        sources: List<Pair<Uri, String?>>, // Uri to filename pairs
        context: Context,
        destDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<File>()
        val resultsMutex = Mutex()
        var completedCount = 0

        coroutineScope {
            sources.map { (uri, fileName) ->
                async {
                    val destFileName = fileName ?: "file_${System.currentTimeMillis()}"
                    val destFile = File(destDir, destFileName)

                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        BufferedOutputStream(FileOutputStream(destFile), FAST_BUFFER_SIZE).use { out ->
                            BufferedInputStream(inputStream, FAST_BUFFER_SIZE).use { input ->
                                input.copyTo(out, FAST_BUFFER_SIZE)
                            }
                        }

                        resultsMutex.withLock {
                            results.add(destFile)
                            completedCount++
                            onProgress?.invoke(completedCount.toFloat() / sources.size)
                        }
                    }
                }
            }.awaitAll()
        }

        results
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
