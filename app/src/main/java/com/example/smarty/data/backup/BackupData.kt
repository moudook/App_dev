package com.example.smarty.data.backup

import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.google.gson.annotations.SerializedName

/**
 * Backup data models for Google Drive backup/restore functionality.
 *
 * These models are designed to be serializable to JSON for backup
 * and support version migrations for future compatibility.
 */

/**
 * Manifest file that describes the backup contents and version.
 * First file to read when restoring to verify compatibility.
 */
data class BackupManifest(
    @SerializedName("version")
    val version: Int = CURRENT_BACKUP_VERSION,

    @SerializedName("app_version_code")
    val appVersionCode: Int,

    @SerializedName("app_version_name")
    val appVersionName: String,

    @SerializedName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @SerializedName("device_name")
    val deviceName: String,

    @SerializedName("note_count")
    val noteCount: Int,

    @SerializedName("category_count")
    val categoryCount: Int,

    @SerializedName("attachment_count")
    val attachmentCount: Int,

    @SerializedName("has_preferences")
    val hasPreferences: Boolean = true,

    @SerializedName("has_attachments")
    val hasAttachments: Boolean = true
) {
    companion object {
        const val CURRENT_BACKUP_VERSION = 1
        const val MANIFEST_FILENAME = "manifest.json"
    }
}

/**
 * Serializable representation of a Note for backup.
 * Mirrors the Note entity but without Room annotations.
 */
data class NoteBackup(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("summary")
    val summary: String? = null,

    @SerializedName("source_url")
    val sourceUrl: String? = null,

    @SerializedName("image_uri")
    val imageUri: String? = null,

    @SerializedName("file_uri")
    val fileUri: String? = null,

    @SerializedName("file_name")
    val fileName: String? = null,

    @SerializedName("file_mime_type")
    val fileMimeType: String? = null,

    @SerializedName("file_size")
    val fileSize: Long? = null,

    @SerializedName("type")
    val type: String,  // NoteType as string for compatibility

    @SerializedName("category_id")
    val categoryId: String? = null,

    @SerializedName("category_name")
    val categoryName: String? = null,

    @SerializedName("why_saved")
    val whySaved: String? = null,

    @SerializedName("processing_status")
    val processingStatus: String,  // ProcessingStatus as string

    @SerializedName("created_at")
    val createdAt: Long,

    @SerializedName("updated_at")
    val updatedAt: Long,

    @SerializedName("is_archived")
    val isArchived: Boolean,

    @SerializedName("todo_content")
    val todoContent: String? = null,

    // Backup-specific: relative path to attachment in backup ZIP
    @SerializedName("backup_image_path")
    val backupImagePath: String? = null,

    @SerializedName("backup_file_path")
    val backupFilePath: String? = null
) {
    companion object {
        fun fromNote(note: Note, backupImagePath: String? = null, backupFilePath: String? = null): NoteBackup {
            return NoteBackup(
                id = note.id,
                title = note.title,
                content = note.content,
                summary = note.summary,
                sourceUrl = note.sourceUrl,
                imageUri = note.imageUri,
                fileUri = note.fileUri,
                fileName = note.fileName,
                fileMimeType = note.fileMimeType,
                fileSize = note.fileSize,
                type = note.type.name,
                categoryId = note.categoryId,
                categoryName = note.categoryName,
                whySaved = note.whySaved,
                processingStatus = note.processingStatus.name,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                isArchived = note.isArchived,
                todoContent = note.todoContent,
                backupImagePath = backupImagePath,
                backupFilePath = backupFilePath
            )
        }
    }

    fun toNote(restoredImageUri: String? = null, restoredFileUri: String? = null): Note {
        return Note(
            id = id,
            title = title,
            content = content,
            summary = summary,
            sourceUrl = sourceUrl,
            imageUri = restoredImageUri ?: imageUri,
            fileUri = restoredFileUri ?: fileUri,
            fileName = fileName,
            fileMimeType = fileMimeType,
            fileSize = fileSize,
            type = try { NoteType.valueOf(type) } catch (e: Exception) { NoteType.BRAIN_DUMP },
            categoryId = categoryId,
            categoryName = categoryName,
            whySaved = whySaved,
            processingStatus = try { ProcessingStatus.valueOf(processingStatus) } catch (e: Exception) { ProcessingStatus.COMPLETED },
            createdAt = createdAt,
            updatedAt = updatedAt,
            isArchived = isArchived,
            todoContent = todoContent
        )
    }
}

/**
 * Serializable representation of a Category for backup.
 */
data class CategoryBackup(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String? = null,

    @SerializedName("note_count")
    val noteCount: Int,

    @SerializedName("is_ai_generated")
    val isAiGenerated: Boolean,

    @SerializedName("created_at")
    val createdAt: Long,

    @SerializedName("last_updated")
    val lastUpdated: Long
) {
    companion object {
        fun fromCategory(category: Category): CategoryBackup {
            return CategoryBackup(
                id = category.id,
                name = category.name,
                description = category.description,
                noteCount = category.noteCount,
                isAiGenerated = category.isAiGenerated,
                createdAt = category.createdAt,
                lastUpdated = category.lastUpdated
            )
        }
    }

    fun toCategory(): Category {
        return Category(
            id = id,
            name = name,
            description = description,
            noteCount = noteCount,
            isAiGenerated = isAiGenerated,
            createdAt = createdAt,
            lastUpdated = lastUpdated
        )
    }
}

/**
 * Backup-safe preferences (excludes sensitive data like API keys).
 * API keys are encrypted separately.
 */
data class PreferencesBackup(
    @SerializedName("is_dark_theme")
    val isDarkTheme: Boolean,

    @SerializedName("auto_backup_enabled")
    val autoBackupEnabled: Boolean,

    @SerializedName("auto_backup_interval_days")
    val autoBackupIntervalDays: Int,

    @SerializedName("is_pin_configured")
    val isPinConfigured: Boolean,

    // Encrypted API keys (base64 encoded)
    @SerializedName("encrypted_gemini_keys")
    val encryptedGeminiKeys: List<String>? = null,

    @SerializedName("encrypted_hugging_face_keys")
    val encryptedHuggingFaceKeys: List<String>? = null,

    @SerializedName("provider_configs")
    val providerConfigs: Map<String, Boolean>? = null
)

/**
 * Complete database backup containing all notes and categories.
 */
data class DatabaseBackup(
    @SerializedName("notes")
    val notes: List<NoteBackup>,

    @SerializedName("categories")
    val categories: List<CategoryBackup>
) {
    companion object {
        const val DATABASE_FILENAME = "database.json"
    }
}

/**
 * Metadata about a backup file stored in Google Drive.
 * Used for listing available backups.
 */
data class BackupMetadata(
    val driveFileId: String,
    val fileName: String,
    val createdAt: Long,
    val fileSize: Long,
    val noteCount: Int,
    val categoryCount: Int,
    val deviceName: String,
    val appVersion: String
) {
    val displayDate: String
        get() {
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(createdAt))
        }

    val displaySize: String
        get() {
            return when {
                fileSize < 1024 -> "$fileSize B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                else -> String.format("%.1f MB", fileSize / (1024.0 * 1024.0))
            }
        }
}

/**
 * Represents the state of a backup/restore operation.
 */
sealed class BackupOperationState {
    data object Idle : BackupOperationState()

    data class InProgress(
        val progress: Float,  // 0.0 to 1.0
        val stage: String     // Human-readable stage description
    ) : BackupOperationState()

    data class Success(
        val message: String,
        val metadata: BackupMetadata? = null
    ) : BackupOperationState()

    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : BackupOperationState()
}

/**
 * Configuration for auto-backup scheduling.
 */
data class AutoBackupConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = DEFAULT_BACKUP_INTERVAL_DAYS,
    val lastBackupTime: Long = 0L,
    val nextScheduledBackup: Long = 0L
) {
    val isBackupDue: Boolean
        get() = System.currentTimeMillis() - lastBackupTime >= intervalDays * 24 * 60 * 60 * 1000L

    val daysSinceLastBackup: Int
        get() {
            if (lastBackupTime == 0L) return -1
            val diff = System.currentTimeMillis() - lastBackupTime
            return (diff / (24 * 60 * 60 * 1000L)).toInt()
        }

    companion object {
        const val DEFAULT_BACKUP_INTERVAL_DAYS = 100
        val INTERVAL_OPTIONS = listOf(30, 60, 100, 180, 365)
    }
}

/**
 * Metadata about a local backup file stored on device.
 * Used for listing, sharing, and deleting local backups.
 */
data class LocalBackupMetadata(
    val fileName: String,
    val filePath: String,
    val createdAt: Long,
    val fileSize: Long,
    val noteCount: Int,
    val categoryCount: Int,
    val attachmentCount: Int,
    val deviceName: String,
    val appVersion: String
) {
    val displayDate: String
        get() {
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(createdAt))
        }

    val displaySize: String
        get() {
            return when {
                fileSize < 1024 -> "$fileSize B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                else -> String.format("%.1f MB", fileSize / (1024.0 * 1024.0))
            }
        }
}

/**
 * Storage statistics for the backup system.
 * Sprint 6: Storage management optimization.
 */
data class BackupStorageStats(
    val backupCount: Int,
    val totalBackupSize: Long,
    val maxBackupCount: Int,
    val maxTotalSize: Long,
    val orphanedTempSize: Long,
    val isOverCountLimit: Boolean,
    val isOverSizeLimit: Boolean
) {
    val displayTotalSize: String
        get() = when {
            totalBackupSize < 1024 -> "$totalBackupSize B"
            totalBackupSize < 1024 * 1024 -> "${totalBackupSize / 1024} KB"
            else -> String.format("%.1f MB", totalBackupSize / (1024.0 * 1024.0))
        }

    val displayMaxSize: String
        get() = String.format("%.0f MB", maxTotalSize / (1024.0 * 1024.0))

    val displayOrphanedSize: String
        get() = when {
            orphanedTempSize < 1024 -> "$orphanedTempSize B"
            orphanedTempSize < 1024 * 1024 -> "${orphanedTempSize / 1024} KB"
            else -> String.format("%.1f MB", orphanedTempSize / (1024.0 * 1024.0))
        }

    val sizeUsagePercent: Float
        get() = (totalBackupSize.toFloat() / maxTotalSize).coerceIn(0f, 1f)
}
