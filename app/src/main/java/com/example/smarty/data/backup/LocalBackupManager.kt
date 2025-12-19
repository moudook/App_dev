package com.example.smarty.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.smarty.BuildConfig
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Manages local backup operations - creates shareable ZIP files stored on device.
 *
 * Features:
 * - Creates complete backups (database + preferences + attachments) as ZIP files
 * - Stores backups in app's internal storage (local_backups directory)
 * - Provides sharing via FileProvider
 * - Lists and deletes existing local backups
 */
class LocalBackupManager(
    private val context: Context,
    private val database: CogniDatabase,
    private val securePreferences: SecurePreferences
) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val _localBackupState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val localBackupState: StateFlow<BackupOperationState> = _localBackupState.asStateFlow()

    private val localBackupsDir: File
        get() = File(context.filesDir, LOCAL_BACKUPS_DIR).also { it.mkdirs() }

    companion object {
        private const val LOCAL_BACKUPS_DIR = "local_backups"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val IMAGES_DIR = "attachments/images"
        private const val FILES_DIR = "attachments/files"
        private const val PREFERENCES_FILENAME = "preferences.json"
        private const val METADATA_FILENAME = "local_metadata.json"

        // =====================================================================
        // HIGH-PERFORMANCE I/O SETTINGS
        // =====================================================================
        private const val FAST_BUFFER_SIZE = 65536        // 64KB
        private const val BULK_BUFFER_SIZE = 131072       // 128KB for large files
        private const val NIO_BUFFER_SIZE = 262144        // 256KB NIO direct buffer
        private const val LARGE_FILE_THRESHOLD = 5 * 1024 * 1024L  // 5MB
    }

    /**
     * Create a local backup ZIP file.
     *
     * @return Result with the created LocalBackupMetadata
     */
    suspend fun createLocalBackup(): Result<LocalBackupMetadata> = withContext(Dispatchers.IO) {
        try {
            _localBackupState.value = BackupOperationState.InProgress(0.05f, "Preparing backup...")

            // Get all notes and categories
            val notes = database.noteDao().getAllNotesOnce()
            val categories = database.categoryDao().getAllCategoriesOnce()

            _localBackupState.value = BackupOperationState.InProgress(0.1f, "Exporting database...")

            // Create temp directory for backup
            val tempDir = File(context.cacheDir, "local_backup_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                // Create attachments directories
                val imagesDir = File(tempDir, IMAGES_DIR)
                val filesDir = File(tempDir, FILES_DIR)
                imagesDir.mkdirs()
                filesDir.mkdirs()

                _localBackupState.value = BackupOperationState.InProgress(0.15f, "Copying attachments...")

                // Copy attachments and build backup notes with relative paths
                var attachmentCount = 0
                val noteBackups = notes.mapIndexed { index, note ->
                    val progress = 0.15f + (0.45f * index / notes.size.coerceAtLeast(1))
                    _localBackupState.value = BackupOperationState.InProgress(
                        progress,
                        "Copying attachments (${index + 1}/${notes.size})..."
                    )

                    var backupImagePath: String? = null
                    var backupFilePath: String? = null

                    // Copy image if exists
                    note.imageUri?.let { uriString ->
                        try {
                            val uri = Uri.parse(uriString)
                            val fileName = "img_${note.id}_${getFileNameFromUri(uri) ?: "image"}"
                            val destFile = File(imagesDir, fileName)
                            if (copyUriToFile(uri, destFile)) {
                                backupImagePath = "$IMAGES_DIR/$fileName"
                                attachmentCount++
                            }
                        } catch (e: Exception) {
                            // Continue without this attachment
                        }
                    }

                    // Copy file if exists
                    note.fileUri?.let { uriString ->
                        try {
                            val uri = Uri.parse(uriString)
                            val fileName = "file_${note.id}_${note.fileName ?: getFileNameFromUri(uri) ?: "file"}"
                            val destFile = File(filesDir, fileName)
                            if (copyUriToFile(uri, destFile)) {
                                backupFilePath = "$FILES_DIR/$fileName"
                                attachmentCount++
                            }
                        } catch (e: Exception) {
                            // Continue without this attachment
                        }
                    }

                    NoteBackup.fromNote(note, backupImagePath, backupFilePath)
                }

                _localBackupState.value = BackupOperationState.InProgress(0.6f, "Creating database export...")

                // Create database backup
                val categoryBackups = categories.map { CategoryBackup.fromCategory(it) }
                val databaseBackup = DatabaseBackup(noteBackups, categoryBackups)
                val databaseJson = gson.toJson(databaseBackup)
                File(tempDir, DatabaseBackup.DATABASE_FILENAME).writeText(databaseJson)

                _localBackupState.value = BackupOperationState.InProgress(0.65f, "Exporting preferences...")

                // Create preferences backup
                val preferencesBackup = createPreferencesBackup()
                val preferencesJson = gson.toJson(preferencesBackup)
                File(tempDir, PREFERENCES_FILENAME).writeText(preferencesJson)

                _localBackupState.value = BackupOperationState.InProgress(0.7f, "Creating manifest...")

                // Create manifest
                val manifest = BackupManifest(
                    appVersionCode = try { BuildConfig.VERSION_CODE } catch (e: Exception) { 1 },
                    appVersionName = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "1.0" },
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    noteCount = notes.size,
                    categoryCount = categories.size,
                    attachmentCount = attachmentCount
                )
                val manifestJson = gson.toJson(manifest)
                File(tempDir, BackupManifest.MANIFEST_FILENAME).writeText(manifestJson)

                _localBackupState.value = BackupOperationState.InProgress(0.75f, "Creating backup archive...")

                // Create ZIP file in local backups directory
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zipFileName = "cogni_backup_$timestamp.zip"
                val zipFile = File(localBackupsDir, zipFileName)

                createZipFile(tempDir, zipFile)

                _localBackupState.value = BackupOperationState.InProgress(0.9f, "Finalizing...")

                // Create metadata
                val metadata = LocalBackupMetadata(
                    fileName = zipFileName,
                    filePath = zipFile.absolutePath,
                    createdAt = System.currentTimeMillis(),
                    fileSize = zipFile.length(),
                    noteCount = notes.size,
                    categoryCount = categories.size,
                    attachmentCount = attachmentCount,
                    deviceName = manifest.deviceName,
                    appVersion = manifest.appVersionName
                )

                // Save metadata alongside the ZIP
                val metadataFile = File(localBackupsDir, "${zipFileName}.meta")
                metadataFile.writeText(gson.toJson(metadata))

                // Cleanup temp directory
                tempDir.deleteRecursively()

                _localBackupState.value = BackupOperationState.Success(
                    "Local backup created successfully"
                )

                Result.success(metadata)
            } finally {
                // Ensure cleanup even on error
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            _localBackupState.value = BackupOperationState.Error(
                e.message ?: "Local backup failed",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * List all local backups.
     */
    suspend fun listLocalBackups(): List<LocalBackupMetadata> = withContext(Dispatchers.IO) {
        val backups = mutableListOf<LocalBackupMetadata>()

        localBackupsDir.listFiles()?.filter { it.extension == "zip" }?.forEach { zipFile ->
            val metadataFile = File(localBackupsDir, "${zipFile.name}.meta")
            if (metadataFile.exists()) {
                try {
                    val metadata = gson.fromJson(metadataFile.readText(), LocalBackupMetadata::class.java)
                    // Update file path in case directory changed
                    backups.add(metadata.copy(filePath = zipFile.absolutePath))
                } catch (e: Exception) {
                    // If metadata is corrupted, create basic metadata from file
                    backups.add(createMetadataFromFile(zipFile))
                }
            } else {
                // No metadata file, create basic metadata
                backups.add(createMetadataFromFile(zipFile))
            }
        }

        // Sort by creation date, newest first
        backups.sortedByDescending { it.createdAt }
    }

    /**
     * Delete a local backup.
     */
    suspend fun deleteLocalBackup(metadata: LocalBackupMetadata): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(metadata.filePath)
            val metadataFile = File(localBackupsDir, "${metadata.fileName}.meta")

            zipFile.delete()
            metadataFile.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get a shareable URI for a local backup using FileProvider.
     */
    fun getShareableUri(metadata: LocalBackupMetadata): Uri {
        val file = File(metadata.filePath)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Create a share intent for a local backup.
     */
    fun createShareIntent(metadata: LocalBackupMetadata): Intent {
        val uri = getShareableUri(metadata)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cogni Backup - ${metadata.displayDate}")
            putExtra(Intent.EXTRA_TEXT, "Cogni backup from ${metadata.displayDate}\n" +
                    "Contains ${metadata.noteCount} notes and ${metadata.categoryCount} categories")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Reset local backup state to idle.
     */
    fun resetLocalBackupState() {
        _localBackupState.value = BackupOperationState.Idle
    }

    /**
     * Get total size of all local backups.
     */
    suspend fun getTotalLocalBackupSize(): Long = withContext(Dispatchers.IO) {
        localBackupsDir.listFiles()?.filter { it.extension == "zip" }
            ?.sumOf { it.length() } ?: 0L
    }

    // Helper functions

    private fun createMetadataFromFile(zipFile: File): LocalBackupMetadata {
        // Try to extract info from manifest inside ZIP
        var noteCount = 0
        var categoryCount = 0
        var attachmentCount = 0
        var deviceName = "Unknown"
        var appVersion = "Unknown"
        var createdAt = zipFile.lastModified()

        try {
            ZipFile(zipFile).use { zip ->
                val manifestEntry = zip.getEntry(BackupManifest.MANIFEST_FILENAME)
                if (manifestEntry != null) {
                    val manifest = gson.fromJson(
                        zip.getInputStream(manifestEntry).bufferedReader().readText(),
                        BackupManifest::class.java
                    )
                    noteCount = manifest.noteCount
                    categoryCount = manifest.categoryCount
                    attachmentCount = manifest.attachmentCount
                    deviceName = manifest.deviceName
                    appVersion = manifest.appVersionName
                    createdAt = manifest.createdAt
                }
            }
        } catch (e: Exception) {
            // Use defaults if manifest can't be read
        }

        return LocalBackupMetadata(
            fileName = zipFile.name,
            filePath = zipFile.absolutePath,
            createdAt = createdAt,
            fileSize = zipFile.length(),
            noteCount = noteCount,
            categoryCount = categoryCount,
            attachmentCount = attachmentCount,
            deviceName = deviceName,
            appVersion = appVersion
        )
    }

    private fun createPreferencesBackup(): PreferencesBackup {
        return PreferencesBackup(
            isDarkTheme = securePreferences.getDarkThemePreference(),
            autoBackupEnabled = securePreferences.isAutoBackupEnabled(),
            autoBackupIntervalDays = securePreferences.getAutoBackupIntervalDays(),
            isPinConfigured = securePreferences.isPinConfigured(),
            // Note: We don't backup API keys for security reasons
            encryptedGeminiKeys = null,
            encryptedHuggingFaceKeys = null,
            providerConfigs = AIProvider.entries.associate {
                it.name to securePreferences.isProviderEnabled(it)
            }
        )
    }

    /**
     * High-performance file copy from URI.
     * Uses buffered streams and NIO for large files.
     */
    private fun copyUriToFile(uri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val fileSize = getFileSizeFromUri(uri) ?: 0L
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
            // Try file:// URI directly with optimized copy
            try {
                val sourceFile = File(uri.path ?: return false)
                if (sourceFile.exists()) {
                    copyFileOptimized(sourceFile, destFile)
                    true
                } else false
            } catch (e2: Exception) {
                false
            }
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
     * Optimized file-to-file copy using NIO channels.
     */
    private fun copyFileOptimized(source: File, dest: File) {
        FileInputStream(source).channel.use { srcChannel ->
            FileOutputStream(dest).channel.use { destChannel ->
                srcChannel.transferTo(0, srcChannel.size(), destChannel)
            }
        }
    }

    /**
     * Gets file size from URI for optimization decisions.
     */
    private fun getFileSizeFromUri(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
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

    /**
     * High-performance ZIP file creation.
     * Uses BEST_SPEED compression for faster backup.
     */
    private fun createZipFile(sourceDir: File, zipFile: File) {
        val bufferedOut = BufferedOutputStream(FileOutputStream(zipFile), BULK_BUFFER_SIZE)
        val zipOut = ZipOutputStream(bufferedOut).apply {
            setLevel(Deflater.BEST_SPEED)
        }

        zipOut.use { zip ->
            val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
            val buffer = ByteArray(FAST_BUFFER_SIZE)

            files.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                zip.putNextEntry(ZipEntry(entryName))

                BufferedInputStream(FileInputStream(file), FAST_BUFFER_SIZE).use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        zip.write(buffer, 0, bytesRead)
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
