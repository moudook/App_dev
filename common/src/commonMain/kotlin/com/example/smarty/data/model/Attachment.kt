package com.example.smarty.core.domain.model

// import android.net.Uri // REMOVED: Android dependency
// import java.util.UUID // REMOVED: Java dependency

/**
 * Represents a file attachment for a note
 * Used for in-memory attachment handling before note submission
 */
data class Attachment(
    val id: String, // = UUID.randomUUID().toString(), // Managed by caller
    val uri: String, // Changed from Uri to String for KMP
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
) {
    /**
     * Get the attachment type based on MIME type
     * Uses O(1) prefix matching for performance
     */
    fun getAttachmentType(): AttachmentType {
        return when {
            mimeType.startsWith("image/") -> AttachmentType.IMAGE
            mimeType.startsWith("video/") -> AttachmentType.VIDEO
            mimeType.startsWith("audio/") -> AttachmentType.AUDIO
            mimeType == "application/pdf" -> AttachmentType.DOCUMENT
            mimeType.contains("document") || mimeType.contains("word") -> AttachmentType.DOCUMENT
            mimeType.contains("sheet") || mimeType.contains("excel") || mimeType == "text/csv" -> AttachmentType.SPREADSHEET
            mimeType.contains("presentation") || mimeType.contains("powerpoint") -> AttachmentType.PRESENTATION
            mimeType == "application/vnd.android.package-archive" -> AttachmentType.APK
            mimeType.contains("zip") || mimeType.contains("rar") || mimeType.contains("7z") -> AttachmentType.ARCHIVE
            else -> AttachmentType.FILE
        }
    }
}

/**
 * Types of attachments for UI display
 */
enum class AttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    APK,
    ARCHIVE,
    FILE,
}
