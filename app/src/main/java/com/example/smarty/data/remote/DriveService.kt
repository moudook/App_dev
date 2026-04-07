@file:Suppress("DEPRECATION")

package com.example.smarty.data.remote

import android.content.Context
import com.example.smarty.data.backup.BackupManifest
import com.example.smarty.data.backup.BackupMetadata
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.google.api.services.drive.model.File as DriveFile

/**
 * Service for interacting with Google Drive API.
 *
 * Handles all Drive operations for backup/restore:
 * - Creating and managing the app's backup folder
 * - Uploading backup files
 * - Listing available backups
 * - Downloading backups for restore
 * - Deleting old backups
 */
class DriveService(
    private val context: Context,
    private val authManager: GoogleAuthManager,
) {
    private val gson = Gson()

    companion object {
        private const val APP_FOLDER_NAME = "Smarty Backups"
        private const val BACKUP_MIME_TYPE = "application/zip"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        // Backup retention
        const val DEFAULT_KEEP_BACKUP_COUNT = 5
    }

    /**
     * Get the Drive service instance using the current signed-in account.
     */
    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential =
            GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_APPDATA),
            ).apply {
                selectedAccount = account.account
            }

        // Set timeouts for the transport to prevent hanging during network loss
        val transport =
            NetHttpTransport.Builder()
                .build()

        return Drive.Builder(
            transport,
            GsonFactory.getDefaultInstance(),
            credential,
        )
            .setApplicationName("Smarty")
            .build()
    }

    /**
     * Get or create the app's backup folder in Google Drive.
     *
     * @return Folder ID or null if failed
     */
    suspend fun getOrCreateAppFolder(): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                val driveService = getDriveService(account)

                // Search for existing folder
                val query = "name = '$APP_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
                val result =
                    driveService.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name)")
                        .execute()

                if (result.files.isNotEmpty()) {
                    return@withContext Result.success(result.files[0].id)
                }

                // Create new folder
                val folderMetadata =
                    DriveFile().apply {
                        name = APP_FOLDER_NAME
                        mimeType = FOLDER_MIME_TYPE
                    }

                val folder =
                    driveService.files().create(folderMetadata)
                        .setFields("id")
                        .execute()

                Result.success(folder.id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Upload a backup file to Google Drive.
     *
     * @param localFile The backup ZIP file to upload
     * @param fileName Name for the file in Drive
     * @param progressCallback Called with progress updates (0.0 to 1.0)
     * @return File ID of the uploaded file
     */
    suspend fun uploadBackup(
        localFile: File,
        fileName: String,
        progressCallback: (Float) -> Unit = {},
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                progressCallback(0.05f)

                val folderId = getOrCreateAppFolder().getOrThrow()
                progressCallback(0.15f)

                val driveService = getDriveService(account)

                val fileMetadata =
                    DriveFile().apply {
                        name = fileName
                        parents = listOf(folderId)
                    }

                val mediaContent = FileContent(BACKUP_MIME_TYPE, localFile)

                // Upload with progress tracking
                progressCallback(0.2f)

                val uploadedFile =
                    driveService.files().create(fileMetadata, mediaContent)
                        .setFields("id, name, size, createdTime")
                        .execute()

                progressCallback(1.0f)

                Result.success(uploadedFile.id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * List all available backups in the app's Drive folder.
     *
     * @return List of backup metadata sorted by date (newest first)
     */
    suspend fun listBackups(): Result<List<BackupMetadata>> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                val folderId =
                    getOrCreateAppFolder().getOrElse {
                        return@withContext Result.success(emptyList())
                    }

                val driveService = getDriveService(account)

                val query = "'$folderId' in parents and mimeType = '$BACKUP_MIME_TYPE' and trashed = false"
                val result =
                    driveService.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setFields("files(id, name, size, createdTime, properties)")
                        .setOrderBy("createdTime desc")
                        .execute()

                val backups =
                    result.files.mapNotNull { file ->
                        try {
                            // Parse backup filename to extract metadata
                            // Format: Smarty_backup_YYYYMMDD_HHmmss.zip
                            val createdTime = file.createdTime?.value ?: System.currentTimeMillis()

                            // Try to read properties, fallback to defaults
                            val noteCount = file.properties?.get("noteCount")?.toIntOrNull() ?: 0
                            val categoryCount = file.properties?.get("categoryCount")?.toIntOrNull() ?: 0
                            val deviceName = file.properties?.get("deviceName") ?: "Unknown"
                            val appVersion = file.properties?.get("appVersion") ?: "Unknown"

                            BackupMetadata(
                                driveFileId = file.id,
                                fileName = file.name,
                                createdAt = createdTime,
                                fileSize = file.getSize() ?: 0L,
                                noteCount = noteCount,
                                categoryCount = categoryCount,
                                deviceName = deviceName,
                                appVersion = appVersion,
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }

                Result.success(backups)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Download a backup file from Google Drive.
     *
     * @param fileId The Drive file ID to download
     * @param destinationFile Local file to write to
     * @param progressCallback Called with progress updates (0.0 to 1.0)
     */
    suspend fun downloadBackup(
        fileId: String,
        destinationFile: File,
        progressCallback: (Float) -> Unit = {},
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                progressCallback(0.05f)

                val driveService = getDriveService(account)

                // Get file metadata first for size
                val fileMeta =
                    driveService.files().get(fileId)
                        .setFields("size")
                        .execute()

                val totalSize = fileMeta.getSize() ?: 0L
                progressCallback(0.1f)

                // Download the file
                FileOutputStream(destinationFile).use { outputStream ->
                    driveService.files().get(fileId)
                        .executeMediaAndDownloadTo(outputStream)
                }

                progressCallback(1.0f)

                Result.success(destinationFile)
            } catch (e: Exception) {
                // Clean up partial download
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                Result.failure(e)
            }
        }

    /**
     * Delete old backups, keeping only the most recent ones.
     *
     * @param keepCount Number of backups to keep
     */
    suspend fun deleteOldBackups(keepCount: Int = DEFAULT_KEEP_BACKUP_COUNT): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                val backups = listBackups().getOrThrow()

                if (backups.size <= keepCount) {
                    return@withContext Result.success(0)
                }

                val driveService = getDriveService(account)

                // Delete oldest backups (list is sorted newest first)
                val toDelete = backups.drop(keepCount)
                var deletedCount = 0

                toDelete.forEach { backup ->
                    try {
                        driveService.files().delete(backup.driveFileId).execute()
                        deletedCount++
                    } catch (e: Exception) {
                        // Continue deleting others even if one fails
                    }
                }

                Result.success(deletedCount)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Delete a specific backup by file ID.
     */
    suspend fun deleteBackup(fileId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                val driveService = getDriveService(account)
                driveService.files().delete(fileId).execute()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Upload a backup file with custom properties for easier listing.
     */
    suspend fun uploadBackupWithMetadata(
        localFile: File,
        fileName: String,
        manifest: BackupManifest,
        progressCallback: (Float) -> Unit = {},
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val account =
                    authManager.getCurrentAccount()
                        ?: return@withContext Result.failure(Exception("Not signed in"))

                progressCallback(0.05f)

                val folderId = getOrCreateAppFolder().getOrThrow()
                progressCallback(0.15f)

                val driveService = getDriveService(account)

                // Create file with properties for easier listing
                val fileMetadata =
                    DriveFile().apply {
                        name = fileName
                        parents = listOf(folderId)
                        properties =
                            mapOf(
                                "noteCount" to manifest.noteCount.toString(),
                                "categoryCount" to manifest.categoryCount.toString(),
                                "deviceName" to manifest.deviceName,
                                "appVersion" to manifest.appVersionName,
                                "backupVersion" to manifest.version.toString(),
                            )
                    }

                val mediaContent = FileContent(BACKUP_MIME_TYPE, localFile)
                progressCallback(0.2f)

                val uploadedFile =
                    driveService.files().create(fileMetadata, mediaContent)
                        .setFields("id, name, size, createdTime, properties")
                        .execute()

                progressCallback(1.0f)

                Result.success(uploadedFile.id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Check if the user has any backups in Drive.
     */
    suspend fun hasBackups(): Boolean {
        return listBackups().getOrNull()?.isNotEmpty() == true
    }

    /**
     * Get the most recent backup metadata.
     */
    suspend fun getLatestBackup(): BackupMetadata? {
        return listBackups().getOrNull()?.firstOrNull()
    }
}
