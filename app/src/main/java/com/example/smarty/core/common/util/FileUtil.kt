package com.example.smarty.core.common.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * Utility class for file operations.
 * Provides high-performance file copy and utility functions.
 */
object FileUtil {
    private const val TAG = "FileUtil"
    
    // Buffer sizes optimized for mobile storage
    private const val FAST_BUFFER_SIZE = 8192
    private const val BULK_BUFFER_SIZE = 65536
    private const val NIO_BUFFER_SIZE = 1024 * 1024 // 1MB
    private val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024 // 10MB

    /**
     * Copy file from URI to destination file.
     * Uses optimized buffering and NIO for large files.
     *
     * @param context Android context
     * @param uri Source URI
     * @param destFile Destination file
     * @return true if copy succeeded, false otherwise
     */
    fun copyUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val fileSize = getFileSizeFromUri(context, uri) ?: 0L
                if (fileSize > LARGE_FILE_THRESHOLD) {
                    // NIO for large files
                    copyWithNioChannel(input, destFile)
                } else {
                    // Buffered copy for smaller files
                    BufferedOutputStream(FileOutputStream(destFile), FAST_BUFFER_SIZE).use { output ->
                        BufferedInputStream(input, FAST_BUFFER_SIZE).copyTo(output, FAST_BUFFER_SIZE)
                    }
                }
            }
            destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file from $uri to ${destFile.absolutePath}", e)
            false
        }
    }

    /**
     * NIO-based file copy for large files.
     */
    private fun copyWithNioChannel(input: java.io.InputStream, destFile: File) {
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

    /**
     * Get file size from URI.
     */
    fun getFileSizeFromUri(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size for $uri", e)
            null
        }
    }

    /**
     * Get file name from URI.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file name for $uri", e)
            null
        } ?: uri.lastPathSegment ?: "unknown_file"
    }

    /**
     * Get MIME type from URI.
     */
    fun getMimeTypeFromUri(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    /**
     * Create a temporary file in app's cache directory.
     */
    fun createTempFile(context: Context, prefix: String, suffix: String? = null): File {
        val cacheDir = context.cacheDir
        return File.createTempFile(prefix, suffix, cacheDir)
    }

    /**
     * Create a file in app's files directory.
     */
    fun createAppFile(context: Context, filename: String): File {
        return File(context.filesDir, filename)
    }

    /**
     * Delete file safely.
     */
    fun deleteFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Get human-readable file size string.
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
