package com.example.smarty.core.common.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized file utility functions used across the codebase.
 *
 * Previously duplicated in:
 * - MainActivity.getFileName / getFileSize
 * - BackupManager.getFileNameFromUri
 * - LocalBackupManager.getFileNameFromUri
 * - InputStreamScreen.getMimeTypeFromExtension / getFileInfo / createImageFile
 * - FileStorageHelper.getMimeTypeFromFile
 * - FileViewerHelper.getMimeTypeFromUri
 *
 * All callers should now import from this single source.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    // File Name Resolution

    /**
     * Resolves the display name of a file from its content URI.
     * Falls back to [Uri.getLastPathSegment] if the ContentResolver query fails.
     */
    fun getFileName(
        context: Context,
        uri: Uri,
    ): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else {
                    null
                }
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    // File Size

    /**
     * Resolves the file size in bytes from a content URI.
     * Returns `null` if the size cannot be determined.
     */
    fun getFileSize(
        context: Context,
        uri: Uri,
    ): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // MIME Type Detection

    /**
     * Detects MIME type from a file extension string.
     * Covers images, videos, audio, documents, archives, and other common types.
     * Falls back to `"application/octet-stream"` for unknown extensions.
     */
    fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heic"
            // Videos
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wma" -> "audio/x-ms-wma"
            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "rtf" -> "application/rtf"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "md" -> "text/markdown"
            // Archives
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            // Other
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /**
     * Detects MIME type from a [File] object using Android's [MimeTypeMap].
     * Falls back to `"application/octet-stream"` if detection fails.
     */
    fun getMimeTypeFromFile(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /**
     * Detects MIME type from a content URI via [Context.getContentResolver].
     * Falls back to extension-based detection if the ContentResolver returns
     * `null` or a generic type.
     */
    fun getMimeTypeFromUri(
        context: Context,
        uri: Uri,
    ): String {
        val contentResolverMime = context.contentResolver.getType(uri)
        return when {
            contentResolverMime != null &&
                contentResolverMime != "application/octet-stream" &&
                contentResolverMime != "binary/octet-stream" -> contentResolverMime
            else -> {
                val fileName = getFileName(context, uri) ?: ""
                getMimeTypeFromExtension(fileName)
            }
        }
    }

    // Temp File Creation

    /**
     * Creates a temporary image file for camera capture and returns its content URI
     * via [FileProvider]. Returns `null` if file creation fails.
     *
     * @param context Application or Activity context.
     * @param authority The FileProvider authority (defaults to `"${packageName}.fileprovider"`).
     */
    fun createImageFile(
        context: Context,
        authority: String = "${context.packageName}.fileprovider",
    ): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "SMARTY_$timeStamp"
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
            FileProvider.getUriForFile(context, authority, imageFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create image file", e)
            null
        }
    }
}
