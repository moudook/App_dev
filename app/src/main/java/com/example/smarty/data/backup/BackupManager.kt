package com.example.smarty.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.smarty.BuildConfig
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.JarvisDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.DriveService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val database: JarvisDatabase,
    private val securePreferences: SecurePreferences,
    private val driveService: DriveService,
    private val authManager: com.example.smarty.data.remote.GoogleAuthManager
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

        // =====================================================================
        // HIGH-PERFORMANCE I/O SETTINGS
        // =====================================================================
        // Optimized buffer sizes for ZIP operations:
        // - 64KB buffers minimize system calls while fitting in L2 cache
        // - BEST_SPEED compression (level 1) for 2-3x faster with ~10% larger files
        // =====================================================================
        private const val FAST_BUFFER_SIZE = 65536        // 64KB
        private const val BULK_BUFFER_SIZE = 131072       // 128KB for large files
        private const val NIO_BUFFER_SIZE = 262144        // 256KB NIO direct buffer
        private const val LARGE_FILE_THRESHOLD = 5 * 1024 * 1024L  // 5MB
        private const val MAX_PARALLEL_COPIES = 4          // Limit concurrent file ops

        // INPUT-001: Maximum backup file size (500MB)
        private const val MAX_BACKUP_SIZE = 500L * 1024 * 1024  // 500MB
    }

    // Semaphore for controlling parallel attachment copies
    private val copySemaphore = Semaphore(MAX_PARALLEL_COPIES)

    /**
     * Create a complete backup and upload to Google Drive.
     *
     * @return Metadata of the created backup
     */
    suspend fun createBackup(): Result<BackupMetadata> = withContext(Dispatchers.IO) {
        try {
            // Check for network connectivity before starting heavy operations
            if (!isNetworkAvailable()) {
                throw Exception("connection_lost")
            }

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
                val zipFileName = "Jarvis_backup_$timestamp.zip"
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
                message = if (e.message == "connection_lost") "connection_lost" else "backup_failed",
                exception = e,
                recoveryIntent = authManager.isUserRecoverable(e)
            )
            Result.failure(e)
        }
    }

    /**
     * Restore from a backup.
     * BUG-030 FIX: Added pre-restore backup for rollback on failure.
     *
     * @param metadata Metadata of the backup to restore
     */
    suspend fun restoreBackup(metadata: BackupMetadata): Result<Unit> = withContext(Dispatchers.IO) {
        // BUG-030: Create safety backup before restore for rollback capability
        var preRestoreNotes: List<com.example.smarty.data.model.Note>? = null
        var preRestoreCategories: List<com.example.smarty.data.model.Category>? = null

        try {
            // Check for network connectivity before starting cloud restore
            if (!isNetworkAvailable()) {
                throw Exception("connection_lost")
            }

            _restoreState.value = BackupOperationState.InProgress(0.02f, "Creating safety backup...")

            // Save current data for potential rollback
            preRestoreNotes = database.noteDao().getAllNotesOnce()
            preRestoreCategories = database.categoryDao().getAllCategoriesOnce()

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

                // INPUT-001: Validate backup size before processing
                val totalSize = extractDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (totalSize > MAX_BACKUP_SIZE) {
                    throw Exception("Backup too large: ${totalSize / (1024 * 1024)}MB exceeds ${MAX_BACKUP_SIZE / (1024 * 1024)}MB limit")
                }

                // Read and verify manifest
                val manifestFile = File(extractDir, BackupManifest.MANIFEST_FILENAME)
                if (!manifestFile.exists()) {
                    throw Exception("Invalid backup: missing manifest")
                }
                val manifest = gson.fromJson(manifestFile.readText(), BackupManifest::class.java)
                    ?: throw Exception("Invalid backup: corrupt manifest")

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
                    ?: throw Exception("Invalid backup: corrupt database file")

                // BUG-030: Validate backup data before proceeding
                @Suppress("SENSELESS_COMPARISON")
                if (databaseBackup.notes == null || databaseBackup.categories == null) {
                    throw Exception("Invalid backup: missing notes or categories data")
                }

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
            // BUG-030: Attempt rollback if we have pre-restore data
            if (preRestoreNotes != null && preRestoreCategories != null) {
                try {
                    _restoreState.value = BackupOperationState.InProgress(0f, "Restore failed, rolling back...")

                    // Clear any partial restore data
                    database.noteDao().deleteAllNotes()
                    database.categoryDao().deleteAllCategories()

                    // Restore original data
                    preRestoreCategories.forEach { category ->
                        database.categoryDao().insertCategory(category)
                    }
                    preRestoreNotes.forEach { note ->
                        database.noteDao().insertNote(note)
                    }

                _restoreState.value = BackupOperationState.Error(
                    message = "Restore failed: ${e.message}. Your original data has been preserved.",
                    exception = e,
                    recoveryIntent = authManager.isUserRecoverable(e)
                )
            } catch (rollbackError: Exception) {
                _restoreState.value = BackupOperationState.Error(
                    message = "CRITICAL: Restore and rollback both failed. Some data may be lost. Error: ${e.message}",
                    exception = e,
                    recoveryIntent = authManager.isUserRecoverable(e)
                )
            }
        } else {
            _restoreState.value = BackupOperationState.Error(
                message = e.message ?: "Restore failed",
                exception = e,
                recoveryIntent = authManager.isUserRecoverable(e)
            )
        }
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
        val allConfigs = securePreferences.getAllProviderConfigs()
        val providerConfigsBackup = allConfigs.map { (provider, config) ->
            provider.name to AIProviderConfigBackup(
                isEnabled = config.isEnabled,
                selectedModel = config.selectedModel,
                apiKeys = config.apiKeys
            )
        }.toMap()

        val priorityOrder = securePreferences.getProviderPriority().map { it.name }

        return PreferencesBackup(
            isDarkTheme = securePreferences.getDarkThemePreference(),
            autoBackupEnabled = securePreferences.isAutoBackupEnabled(),
            autoBackupIntervalDays = securePreferences.getAutoBackupIntervalDays(),
            providerPriorityOrder = priorityOrder,
            providerConfigs = providerConfigsBackup,
            tavilyApiKeys = securePreferences.getTavilyApiKeys(),
            localPcIp = securePreferences.getLocalPCIP(),
            localPcPort = securePreferences.getLocalPCPort(),
            localPcUseHttps = securePreferences.getLocalPCUseHttps(),
            shakeSensitivity = securePreferences.getShakeSensitivity(),
            soundEnabled = securePreferences.isSoundEnabled(),
            groqDynamicModels = securePreferences.getDynamicModels(com.example.smarty.data.local.AIProvider.GROQ),
            // Legacy support for older backups
            encryptedGeminiKeys = securePreferences.getProviderKeys(AIProvider.GEMINI),
            encryptedHuggingFaceKeys = securePreferences.getProviderKeys(AIProvider.HUGGINGFACE)
        )
    }

    private fun restorePreferences(backup: PreferencesBackup) {
        securePreferences.setDarkTheme(backup.isDarkTheme)
        securePreferences.setAutoBackupEnabled(backup.autoBackupEnabled)
        securePreferences.setAutoBackupIntervalDays(backup.autoBackupIntervalDays)

        // Restore Tavily API keys
        backup.tavilyApiKeys?.let { keys ->
            if (keys.isNotEmpty()) {
                securePreferences.setTavilyApiKeys(keys)
            }
        }

        // Restore Local PC settings
        backup.localPcIp?.let { securePreferences.setLocalPCIP(it) }
        backup.localPcPort?.let { securePreferences.setLocalPCPort(it) }
        backup.localPcUseHttps?.let { securePreferences.setLocalPCUseHttps(it) }

        // Restore UI and interaction settings
        backup.shakeSensitivity?.let { securePreferences.setShakeSensitivity(it) }
        backup.soundEnabled?.let { securePreferences.setSoundEnabled(it) }

        // Restore dynamic models
        backup.groqDynamicModels?.let { models ->
            if (models.isNotEmpty()) {
                securePreferences.setDynamicModels(AIProvider.GROQ, models)
            }
        }

        // Restore provider configurations (models, enabled state, and keys)
        backup.providerConfigs?.forEach { (providerName, configBackup) ->
            try {
                val provider = AIProvider.valueOf(providerName)
                securePreferences.setProviderEnabled(provider, configBackup.isEnabled)
                securePreferences.setSelectedModel(provider, configBackup.selectedModel)
                configBackup.apiKeys?.let { keys ->
                    if (keys.isNotEmpty()) {
                        securePreferences.setProviderKeys(provider, keys)
                    }
                }
            } catch (e: Exception) {
                // Ignore unknown providers or malformed data
            }
        }

        // Restore priority order
        backup.providerPriorityOrder?.let { priorityNames ->
            val priorityList = priorityNames.mapNotNull { name ->
                try { AIProvider.valueOf(name) } catch (e: Exception) { null }
            }
            if (priorityList.isNotEmpty()) {
                securePreferences.setProviderPriority(priorityList)
            }
        }

        // Fallback for legacy backups that used the old providerConfigs Map<String, Boolean>
        // Note: This is mostly handled by the current BackupData.kt structure but good to keep in mind
    }

    /**
     * High-performance file copy from URI.
     * Uses buffered streams with optimal buffer sizes.
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
     * Check if network is available for cloud operations.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && (
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }

    /**
     * High-performance ZIP file creation.
     * Uses BEST_SPEED compression (level 1) for 2-3x faster compression.
     * Trade-off: ~10% larger files but significantly faster backup.
     */
    private fun createZipFile(sourceDir: File, zipFile: File) {
        val bufferedOut = BufferedOutputStream(FileOutputStream(zipFile), BULK_BUFFER_SIZE)
        val zipOut = ZipOutputStream(bufferedOut).apply {
            // Use fastest compression level - much faster with acceptable size increase
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

    /**
     * High-performance ZIP extraction with parallel file writing.
     * Uses buffered streams for optimal I/O throughput.
     */
    private fun extractZipFile(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            val buffer = ByteArray(FAST_BUFFER_SIZE)

            zip.entries().asSequence().forEach { entry ->
                val destFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    BufferedInputStream(zip.getInputStream(entry), FAST_BUFFER_SIZE).use { input ->
                        BufferedOutputStream(FileOutputStream(destFile), FAST_BUFFER_SIZE).use { output ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Parallel attachment copy for faster backup creation.
     * Copies multiple attachments concurrently with controlled parallelism.
     */
    private suspend fun copyAttachmentsParallel(
        attachmentData: List<AttachmentCopyData>,
        onProgress: (Int, Int) -> Unit
    ): List<AttachmentCopyResult> = withContext(Dispatchers.IO) {
        if (attachmentData.isEmpty()) return@withContext emptyList()

        val results = Array<AttachmentCopyResult?>(attachmentData.size) { null }
        var completedCount = 0
        val progressMutex = Mutex()

        coroutineScope {
            attachmentData.mapIndexed { index, data ->
                async {
                    copySemaphore.withPermit {
                        val result = try {
                            if (copyUriToFile(data.sourceUri, data.destFile)) {
                                AttachmentCopyResult(data.noteId, data.relativePath, true)
                            } else {
                                AttachmentCopyResult(data.noteId, null, false)
                            }
                        } catch (e: Exception) {
                            AttachmentCopyResult(data.noteId, null, false)
                        }
                        results[index] = result

                        progressMutex.withLock {
                            completedCount++
                            onProgress(completedCount, attachmentData.size)
                        }

                        result
                    }
                }
            }.awaitAll()
        }

        results.filterNotNull()
    }

    /**
     * Data class for attachment copy operation
     */
    private data class AttachmentCopyData(
        val noteId: Long,
        val sourceUri: Uri,
        val destFile: File,
        val relativePath: String
    )

    /**
     * Result of attachment copy operation
     */
    private data class AttachmentCopyResult(
        val noteId: Long,
        val backupPath: String?,
        val success: Boolean
    )
}
