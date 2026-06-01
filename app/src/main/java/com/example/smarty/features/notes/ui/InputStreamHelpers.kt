package com.example.smarty.features.notes.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.smarty.core.domain.model.Attachment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun getMimeTypeFromExtension(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wma" -> "audio/x-ms-wma"
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
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}

internal fun getFileInfo(context: Context, uri: Uri): Attachment? {
    return try {
        var fileName: String? = null
        var fileSize: Long = 0
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }
        val safeName = fileName
            ?: uri.lastPathSegment
            ?: "Unknown_${System.currentTimeMillis()}"
        val contentResolverMime = context.contentResolver.getType(uri)
        val mimeType = when {
            contentResolverMime != null &&
            contentResolverMime != "application/octet-stream" &&
            contentResolverMime != "binary/octet-stream" -> contentResolverMime
            else -> getMimeTypeFromExtension(safeName)
        }
        android.util.Log.d("AttachmentPicker", "File: $safeName, MIME: $mimeType (ContentResolver: $contentResolverMime)")
        Attachment(
            id = java.util.UUID.randomUUID().toString(),
            uri = uri.toString(),
            fileName = safeName,
            mimeType = mimeType,
            fileSize = fileSize
        )
    } catch (e: Exception) {
        android.util.Log.e("AttachmentPicker", "Failed to get file info: ${e.message}", e)
        null
    }
}

internal fun createImageFile(context: Context): Uri? {
    return try {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "SMARTY_${timeStamp}"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    } catch (e: Exception) {
        null
    }
}
