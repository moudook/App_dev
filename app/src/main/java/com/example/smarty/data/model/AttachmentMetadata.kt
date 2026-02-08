package com.example.smarty.data.model

/**
 * Lightweight metadata about attachments for AI processing.
 * Contains only essential information (no file content) to keep tokens minimal.
 */
data class AttachmentMetadata(
    val fileName: String,
    val mimeType: String,
    val fileType: String  // Human-readable type: "Image", "Video", "PDF", "Audio", etc.
) {
    companion object {
        /**
         * Create metadata from an Attachment
         */
        fun fromAttachment(attachment: Attachment): AttachmentMetadata {
            val fileType = when (attachment.getAttachmentType()) {
                AttachmentType.IMAGE -> "Image"
                AttachmentType.VIDEO -> "Video"
                AttachmentType.AUDIO -> "Audio"
                AttachmentType.DOCUMENT -> "Document"
                AttachmentType.SPREADSHEET -> "Spreadsheet"
                AttachmentType.PRESENTATION -> "Presentation"
                AttachmentType.ARCHIVE -> "Archive"
                AttachmentType.APK -> "App"
                AttachmentType.FILE -> "File"
            }
            return AttachmentMetadata(
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                fileType = fileType
            )
        }

        /**
         * Create metadata from a NoteAttachment
         */
        fun fromNoteAttachment(attachment: NoteAttachment): AttachmentMetadata {
            val fileType = inferFileType(attachment.mimeType)
            return AttachmentMetadata(
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                fileType = fileType
            )
        }

        /**
         * Infer human-readable file type from MIME type
         */
        private fun inferFileType(mimeType: String): String {
            return when {
                mimeType.startsWith("image/") -> "Image"
                mimeType.startsWith("video/") -> "Video"
                mimeType.startsWith("audio/") -> "Audio"
                mimeType == "application/pdf" -> "Document"
                mimeType.contains("word") || mimeType.contains("document") -> "Document"
                mimeType.contains("excel") || mimeType.contains("spreadsheet") -> "Spreadsheet"
                mimeType.contains("powerpoint") || mimeType.contains("presentation") -> "Presentation"
                mimeType.contains("zip") || mimeType.contains("rar") || mimeType.contains("tar") -> "Archive"
                mimeType == "application/vnd.android.package-archive" -> "App"
                else -> "File"
            }
        }
    }
}
