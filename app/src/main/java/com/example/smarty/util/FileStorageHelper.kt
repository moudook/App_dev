package com.example.smarty.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Helper class for copying shared files to persistent internal storage.
 *
 * When files are shared from other apps, they come with temporary URI permissions
 * that expire when the app closes. This utility copies the file content to the
 * app's internal storage, ensuring the file remains accessible across app restarts.
 */
object FileStorageHelper {

    private const val TAG = "FileStorageHelper"

    // Subdirectories for different file types
    private const val DIR_ATTACHMENTS = "attachments"
    private const val DIR_IMAGES = "images"
    private const val DIR_AUDIO = "audio"
    private const val DIR_VIDEO = "video"
    private const val DIR_DOCUMENTS = "documents"
    private const val DIR_CACHE = "decompression_cache"

    /**
     * Copies a shared file URI to internal app storage and returns the new persistent URI.
     *
     * @param context Application context
     * @param sourceUri The original URI from the share intent
     * @param mimeType The MIME type of the file (used for directory selection)
     * @param originalFileName Optional original filename (extracted if not provided)
     * @return A new content:// URI from FileProvider, or null if copy failed
     */
    suspend fun copyToInternalStorage(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String? = null
    ): CopiedFileResult? = withContext(Dispatchers.IO) {
        try {
            // Get file info from source
            val fileName = originalFileName ?: getFileName(context, sourceUri) ?: generateFileName(mimeType)

            // Determine appropriate subdirectory based on mime type
            val subDir = getSubdirectory(mimeType)

            // Create destination directory
            val attachmentsDir = File(context.filesDir, subDir)
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }

            // Generate unique filename to avoid collisions
            val uniqueFileName = generateUniqueFileName(attachmentsDir, fileName)
            val destFile = File(attachmentsDir, uniqueFileName)

            // Copy the file content
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: return@withContext null

            // Get file size
            val fileSize = destFile.length()

            // Generate a content:// URI via FileProvider for the copied file
            val newUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )

            CopiedFileResult(
                uri = newUri.toString(),
                localPath = destFile.absolutePath,
                fileName = uniqueFileName,
                fileSize = fileSize,
                mimeType = mimeType ?: getMimeTypeFromFile(destFile)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Copies a file and returns a file:// URI (for cases where FileProvider isn't needed)
     */
    suspend fun copyToInternalStorageAsFile(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String? = null
    ): CopiedFileResult? = withContext(Dispatchers.IO) {
        try {
            val fileName = originalFileName ?: getFileName(context, sourceUri) ?: generateFileName(mimeType)
            val subDir = getSubdirectory(mimeType)

            val attachmentsDir = File(context.filesDir, subDir)
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }

            val uniqueFileName = generateUniqueFileName(attachmentsDir, fileName)
            val destFile = File(attachmentsDir, uniqueFileName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: return@withContext null

            val fileSize = destFile.length()

            // Return file:// URI for internal use
            CopiedFileResult(
                uri = Uri.fromFile(destFile).toString(),
                localPath = destFile.absolutePath,
                fileName = uniqueFileName,
                fileSize = fileSize,
                mimeType = mimeType ?: getMimeTypeFromFile(destFile)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Compresses and stores a file to internal storage.
     * Uses optimal compression based on file type:
     * - Images → WebP (26-34% smaller)
     * - Videos/Audio → No compression (already compressed)
     * - Documents/Other → GZIP
     *
     * @param context Application context
     * @param sourceUri The original URI from the share intent
     * @param mimeType The MIME type of the file
     * @param originalFileName Optional original filename
     * @return CompressedStorageResult with file info and compression details
     */
    suspend fun compressAndStore(
        context: Context,
        sourceUri: Uri,
        mimeType: String?,
        originalFileName: String? = null
    ): CompressedStorageResult? = withContext(Dispatchers.IO) {
        try {
            val fileName = originalFileName ?: getFileName(context, sourceUri) ?: generateFileName(mimeType)
            val subDir = getSubdirectory(mimeType)

            val destDir = File(context.filesDir, subDir)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Use FileCompressor for intelligent compression
            val compressedResult = FileCompressor.compressFile(
                context = context,
                sourceUri = sourceUri,
                mimeType = mimeType,
                originalFileName = fileName,
                destDir = destDir
            )

            Log.d(TAG, "File compressed and stored: ${compressedResult.originalFileName} -> " +
                    "${compressedResult.compressedFileName} (${compressedResult.compressionType}, " +
                    "saved ${formatFileSize(compressedResult.savedBytes)})")

            CompressedStorageResult(
                uri = Uri.fromFile(compressedResult.compressedFile).toString(),
                localPath = compressedResult.compressedFile.absolutePath,
                fileName = compressedResult.originalFileName,
                compressedFileName = compressedResult.compressedFileName,
                originalSize = compressedResult.originalSize,
                compressedSize = compressedResult.compressedSize,
                compressionType = compressedResult.compressionType,
                mimeType = compressedResult.mimeType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress and store file: ${e.message}", e)
            // Fall back to simple copy without compression
            copyToInternalStorageAsFile(context, sourceUri, mimeType, originalFileName)?.let { copied ->
                CompressedStorageResult(
                    uri = copied.uri,
                    localPath = copied.localPath,
                    fileName = copied.fileName,
                    compressedFileName = copied.fileName,
                    originalSize = copied.fileSize,
                    compressedSize = copied.fileSize,
                    compressionType = CompressionType.NONE,
                    mimeType = copied.mimeType
                )
            }
        }
    }

    /**
     * Gets the decompression cache directory.
     */
    fun getDecompressionCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, DIR_CACHE)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    /**
     * Checks if a URI is already a local file (doesn't need copying)
     */
    fun isLocalFile(uri: String): Boolean {
        return uri.startsWith("file://") ||
               uri.startsWith("/") ||
               uri.contains("${DIR_ATTACHMENTS}/") ||
               uri.contains("${DIR_IMAGES}/") ||
               uri.contains("${DIR_AUDIO}/") ||
               uri.contains("${DIR_VIDEO}/") ||
               uri.contains("${DIR_DOCUMENTS}/")
    }

    /**
     * Checks if the file at the given URI exists and is accessible
     */
    fun isFileAccessible(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            when {
                uriString.startsWith("file://") -> {
                    File(uri.path ?: return false).exists()
                }
                uriString.startsWith("/") -> {
                    File(uriString).exists()
                }
                else -> {
                    // Content URI - try to open it
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deletes a file from internal storage (for cleanup when note is deleted)
     */
    suspend fun deleteFile(context: Context, uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            when {
                uriString.startsWith("file://") -> {
                    File(uri.path ?: return@withContext false).delete()
                }
                uriString.startsWith("/") -> {
                    File(uriString).delete()
                }
                uriString.contains(context.packageName) -> {
                    // It's a FileProvider URI - extract path
                    // This is a simplification; you may need to handle this differently
                    val file = getFileFromProviderUri(context, uri)
                    file?.delete() ?: false
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets total size of all stored attachments (for storage management)
     */
    suspend fun getTotalStorageUsed(context: Context): Long = withContext(Dispatchers.IO) {
        val baseDir = context.filesDir
        listOf(DIR_ATTACHMENTS, DIR_IMAGES, DIR_AUDIO, DIR_VIDEO, DIR_DOCUMENTS)
            .map { File(baseDir, it) }
            .filter { it.exists() }
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .sumOf { it.length() }
    }

    // ========================== Private Helpers ==========================

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun generateFileName(mimeType: String?): String {
        val extension = mimeType?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        } ?: "bin"
        return "file_${UUID.randomUUID().toString().take(8)}.$extension"
    }

    private fun generateUniqueFileName(directory: File, originalName: String): String {
        val baseName = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")

        var candidate = originalName
        var counter = 1

        while (File(directory, candidate).exists()) {
            candidate = if (extension.isNotEmpty()) {
                "${baseName}_$counter.$extension"
            } else {
                "${baseName}_$counter"
            }
            counter++
        }

        return candidate
    }

    private fun getSubdirectory(mimeType: String?): String {
        return when {
            mimeType == null -> DIR_ATTACHMENTS
            mimeType.startsWith("image/") -> DIR_IMAGES
            mimeType.startsWith("audio/") -> DIR_AUDIO
            mimeType.startsWith("video/") -> DIR_VIDEO
            mimeType.startsWith("application/pdf") ||
            mimeType.contains("document") ||
            mimeType.contains("text/") -> DIR_DOCUMENTS
            else -> DIR_ATTACHMENTS
        }
    }

    private fun getMimeTypeFromFile(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun getFileFromProviderUri(context: Context, uri: Uri): File? {
        // This is a simplified approach - FileProvider URIs encode the path
        // For our own FileProvider, we can decode based on our known paths
        return try {
            val path = uri.path ?: return null
            // Our FileProvider paths start with the path name from file_paths.xml
            val baseDir = context.filesDir
            when {
                path.contains("/attachments/") -> File(baseDir, "attachments/${path.substringAfterLast("/attachments/")}")
                path.contains("/images/") -> File(baseDir, "images/${path.substringAfterLast("/images/")}")
                path.contains("/audio/") -> File(baseDir, "audio/${path.substringAfterLast("/audio/")}")
                path.contains("/video/") -> File(baseDir, "video/${path.substringAfterLast("/video/")}")
                path.contains("/documents/") -> File(baseDir, "documents/${path.substringAfterLast("/documents/")}")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Result of copying a file to internal storage
 */
data class CopiedFileResult(
    val uri: String,           // Content URI or file URI for the copied file
    val localPath: String,     // Absolute path to the file
    val fileName: String,      // Name of the copied file
    val fileSize: Long,        // Size in bytes
    val mimeType: String       // MIME type
)

/**
 * Result of compressing and storing a file
 */
data class CompressedStorageResult(
    val uri: String,                    // URI for the compressed file
    val localPath: String,              // Absolute path to the compressed file
    val fileName: String,               // Original filename
    val compressedFileName: String,     // Compressed filename (may have different extension)
    val originalSize: Long,             // Original file size in bytes
    val compressedSize: Long,           // Compressed file size in bytes
    val compressionType: CompressionType, // Type of compression used
    val mimeType: String                // MIME type
) {
    /** Compression ratio as percentage (0-100) */
    val compressionRatio: Double
        get() = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize)
        } else 0.0

    /** Bytes saved by compression */
    val savedBytes: Long
        get() = originalSize - compressedSize

    /** Whether the file was actually compressed */
    val isCompressed: Boolean
        get() = compressionType != CompressionType.NONE
}
