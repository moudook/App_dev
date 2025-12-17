package com.example.smarty.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.smarty.BuildConfig
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.DriveService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Orchestrates backup and restore operations.
 *
 * Handles:
 * - Creating complete backups (database + preferences + attachments)
 * - Uploading backups to Google Drive
 * - Downloading and restoring from backups
 * - Progress tracking for all operations
 */
class BackupManager(
    private val context: Context,
    private val database: CogniDatabase,
    private val securePreferences: SecurePreferences,
    private val driveService: DriveService
) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val _backupState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val backupState: StateFlow<BackupOperationState> = _backupState.asStateFlow()

    private val _restoreState = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val restoreState: StateFlow<BackupOperationState> = _restoreState.asStateFlow()

    companion object {
        private const val ATTACHMENTS_DIR = "attachments"
        private const val IMAGES_DIR = "attachments/images"
        private const val FILES_DIR = "attachments/files"
        private const val PREFERENCES_FILENAME = "preferences.json"
    }

    /**
     * Create a complete backup and upload to Google Drive.
     *
     * @return Metadata of the created backup
     */
    suspend fun createBackup(): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        try {
            _backupState.value = BackupOperationState.InProgress(0.05f, "Preparing backup...")

            // Get all notes and categories
            val notes = database.noteDao().getAllNotesOnce()
            val categories = database.categoryDao().getAllCategoriesOnce()

            _backupState.value = BackupOperationState.InProgress(0.1f, "Exporting database...")

            // Create temp directory for backup
            val tempDir = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                // Create attachments directories
                val imagesDir = File(tempDir, IMAGES_DIR)
                val filesDir = File(tempDir, FILES_DIR)
                imagesDir.mkdirs()
                filesDir.mkdirs()

                _backupState.value = BackupOperationState.InProgress(0.15f, "Copying attachments...")

                // Copy attachments and build backup notes with relative paths
                var attachmentCount = 0
                val noteBackups = notes.mapIndexed { index, note ->
                    val progress = 0.15f + (0.35f * index / notes.size.coerceAtLeast(1))
                    _backupState.value = BackupOperationState.InProgress(
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

                _backupState.value = BackupOperationState.InProgress(0.5f, "Creating database export...")

                // Create database backup
                val categoryBackups = categories.map { CategoryBackup.fromCategory(it) }
                val databaseBackup = DatabaseBackup(noteBackups, categoryBackups)
                val databaseJson = gson.toJson(databaseBackup)
                File(tempDir, DatabaseBackup.DATABASE_FILENAME).writeText(databaseJson)

                _backupState.value = BackupOperationState.InProgress(0.55f, "Exporting preferences...")

                // Create preferences backup
                val preferencesBackup = createPreferencesBackup()
                val preferencesJson = gson.toJson(preferencesBackup)
                File(tempDir, PREFERENCES_FILENAME).writeText(preferencesJson)

                _backupState.value = BackupOperationState.InProgress(0.6f, "Creating manifest...")

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

                _backupState.value = BackupOperationState.InProgress(0.65f, "Creating backup archive...")

                // Create ZIP file
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zipFileName = "cogni_backup_$timestamp.zip"
                val zipFile = File(context.cacheDir, zipFileName)

                createZipFile(tempDir, zipFile)

                _backupState.value = BackupOperationState.InProgress(0.75f, "Uploading to Google Drive...")

                // Upload to Drive
                val fileId = driveService.uploadBackupWithMetadata(
                    localFile = zipFile,
                    fileName = zipFileName,
                    manifest = manifest
                ) { uploadProgress ->
                    val totalProgress = 0.75f + (0.2f * uploadProgress)
                    _backupState.value = BackupOperationState.InProgress(
                        totalProgress,
                        "Uploading to Google Drive..."
                    )
                }.getOrThrow()

                _backupState.value = BackupOperationState.InProgress(0.95f, "Cleaning up old backups...")

                // Clean up old backups
                driveService.deleteOldBackups()

                // Update last backup time
                securePreferences.setLastBackupTime(System.currentTimeMillis())

                // Create metadata for result
                val metadata = BackupMetadata(
                    driveFileId = fileId,
                    fileName = zipFileName,
                    createdAt = System.currentTimeMillis(),
                    fileSize = zipFile.length(),
                    noteCount = notes.size,
                    categoryCount = categories.size,
                    deviceName = manifest.deviceName,
                    appVersion = manifest.appVersionName
                )

                // Cleanup local files
                zipFile.delete()
                tempDir.deleteRecursively()

                _backupState.value = BackupOperationState.Success(
                    "Backup completed successfully",
                    metadata
                )

                Result.success(metadata)
            } finally {
                // Ensure cleanup even on error
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            _backupState.value = BackupOperationState.Error(
                e.message ?: "Backup failed",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Restore from a backup.
     *
     * @param metadata Metadata of the backup to restore
     */
    suspend fun restoreBackup(metadata: BackupMetadata): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _restoreState.value = BackupOperationState.InProgress(0.05f, "Downloading backup...")

            // Download backup file
            val downloadFile = File(context.cacheDir, "restore_${System.currentTimeMillis()}.zip")
            driveService.downloadBackup(
                fileId = metadata.driveFileId,
                destinationFile = downloadFile
            ) { progress ->
                val totalProgress = 0.05f + (0.25f * progress)
                _restoreState.value = BackupOperationState.InProgress(
                    totalProgress,
                    "Downloading backup..."
                )
            }.getOrThrow()

            _restoreState.value = BackupOperationState.InProgress(0.3f, "Extracting backup...")

            // Extract ZIP
            val extractDir = File(context.cacheDir, "restore_extract_${System.currentTimeMillis()}")
            extractDir.mkdirs()

            try {
                extractZipFile(downloadFile, extractDir)

                _restoreState.value = BackupOperationState.InProgress(0.4f, "Verifying backup...")

                // Read and verify manifest
                val manifestFile = File(extractDir, BackupManifest.MANIFEST_FILENAME)
                if (!manifestFile.exists()) {
                    throw Exception("Invalid backup: missing manifest")
                }
                val manifest = gson.fromJson(manifestFile.readText(), BackupManifest::class.java)

                if (manifest.version > BackupManifest.CURRENT_BACKUP_VERSION) {
                    throw Exception("Backup version ${manifest.version} is newer than supported version ${BackupManifest.CURRENT_BACKUP_VERSION}")
                }

                _restoreState.value = BackupOperationState.InProgress(0.45f, "Reading database...")

                // Read database backup
                val databaseFile = File(extractDir, DatabaseBackup.DATABASE_FILENAME)
                if (!databaseFile.exists()) {
                    throw Exception("Invalid backup: missing database")
                }
                val databaseBackup = gson.fromJson(databaseFile.readText(), DatabaseBackup::class.java)

                _restoreState.value = BackupOperationState.InProgress(0.5f, "Clearing existing data...")

                // Clear existing database
                database.noteDao().deleteAllNotes()
                database.categoryDao().deleteAllCategories()

                _restoreState.value = BackupOperationState.InProgress(0.55f, "Restoring categories...")

                // Restore categories first (notes depend on them)
                databaseBackup.categories.forEach { categoryBackup ->
                    database.categoryDao().insertCategory(categoryBackup.toCategory())
                }

                _restoreState.value = BackupOperationState.InProgress(0.6f, "Restoring notes and attachments...")

                // Restore notes with attachments
                val attachmentsDir = File(context.filesDir, "restored_attachments")
                attachmentsDir.mkdirs()

                databaseBackup.notes.forEachIndexed { index, noteBackup ->
                    val progress = 0.6f + (0.3f * index / databaseBackup.notes.size.coerceAtLeast(1))
                    _restoreState.value = BackupOperationState.InProgress(
                        progress,
                        "Restoring notes (${index + 1}/${databaseBackup.notes.size})..."
                    )

                    var restoredImageUri: String? = null
                    var restoredFileUri: String? = null

                    // Restore image attachment
                    noteBackup.backupImagePath?.let { relativePath ->
                        val sourceFile = File(extractDir, relativePath)
                        if (sourceFile.exists()) {
                            val destFile = File(attachmentsDir, "img_${noteBackup.id}_${sourceFile.name}")
                            sourceFile.copyTo(destFile, overwrite = true)
                            restoredImageUri = Uri.fromFile(destFile).toString()
                        }
                    }

                    // Restore file attachment
                    noteBackup.backupFilePath?.let { relativePath ->
                        val sourceFile = File(extractDir, relativePath)
                        if (sourceFile.exists()) {
                            val destFile = File(attachmentsDir, "file_${noteBackup.id}_${sourceFile.name}")
                            sourceFile.copyTo(destFile, overwrite = true)
                            restoredFileUri = Uri.fromFile(destFile).toString()
                        }
                    }

                    val note = noteBackup.toNote(restoredImageUri, restoredFileUri)
                    database.noteDao().insertNote(note)
                }

                _restoreState.value = BackupOperationState.InProgress(0.9f, "Restoring preferences...")

                // Restore preferences
                val preferencesFile = File(extractDir, PREFERENCES_FILENAME)
                if (preferencesFile.exists()) {
                    val preferencesBackup = gson.fromJson(
                        preferencesFile.readText(),
                        PreferencesBackup::class.java
                    )
                    restorePreferences(preferencesBackup)
                }

                _restoreState.value = BackupOperationState.InProgress(0.95f, "Cleaning up...")

                // Cleanup
                downloadFile.delete()
                extractDir.deleteRecursively()

                _restoreState.value = BackupOperationState.Success(
                    "Restore completed successfully! ${databaseBackup.notes.size} notes and ${databaseBackup.categories.size} categories restored."
                )

                Result.success(Unit)
            } finally {
                downloadFile.delete()
                extractDir.deleteRecursively()
            }
        } catch (e: Exception) {
            _restoreState.value = BackupOperationState.Error(
                e.message ?: "Restore failed",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Reset backup state to idle.
     */
    fun resetBackupState() {
        _backupState.value = BackupOperationState.Idle
    }

    /**
     * Reset restore state to idle.
     */
    fun resetRestoreState() {
        _restoreState.value = BackupOperationState.Idle
    }

    // Helper functions

    private fun createPreferencesBackup(): PreferencesBackup {
        return PreferencesBackup(
            isDarkTheme = securePreferences.getDarkThemePreference(),
            autoBackupEnabled = securePreferences.isAutoBackupEnabled(),
            autoBackupIntervalDays = securePreferences.getAutoBackupIntervalDays(),
            isPinConfigured = securePreferences.isPinConfigured(),
            // Note: We don't backup API keys for security reasons
            // Users need to re-enter them after restore
            encryptedGeminiKeys = null,
            encryptedHuggingFaceKeys = null,
            providerConfigs = AIProvider.entries.associate {
                it.name to securePreferences.isProviderEnabled(it)
            }
        )
    }

    private fun restorePreferences(backup: PreferencesBackup) {
        securePreferences.setDarkTheme(backup.isDarkTheme)
        securePreferences.setAutoBackupEnabled(backup.autoBackupEnabled)
        securePreferences.setAutoBackupIntervalDays(backup.autoBackupIntervalDays)

        // Restore provider enabled states
        backup.providerConfigs?.forEach { (providerName, enabled) ->
            try {
                val provider = AIProvider.valueOf(providerName)
                securePreferences.setProviderEnabled(provider, enabled)
            } catch (e: Exception) {
                // Ignore unknown providers
            }
        }
    }

    private fun copyUriToFile(uri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            false
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

    private fun createZipFile(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zipOut.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }
    }

    private fun extractZipFile(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
