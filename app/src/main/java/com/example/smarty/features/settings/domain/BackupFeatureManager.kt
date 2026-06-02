package com.example.smarty.features.settings.domain

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.smarty.data.backup.*
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.DriveService
import com.example.smarty.data.remote.GoogleAuthManager
import com.example.smarty.data.worker.AutoBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Centralized manager for all Backup and Restore operations (Cloud & Local).
 * Hybridizes logic for:
 * - Google Sign-In and Cloud storage connectivity
 * - Automated and manual cloud backups (Drive)
 * - Local ZIP backup creation and sharing
 * - Restore operations with safety rollback capability
 * - Auto-backup scheduling
 *
 * This manager ensures the AI and UI use identical paths for data preservation.
 */
class BackupFeatureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val securePreferences: SecurePreferences,
    private val authManager: GoogleAuthManager,
    private val driveService: DriveService,
    private val cloudBackupManager: BackupManager,
    private val localBackupManager: LocalBackupManager,
) {
    companion object {
        private const val TAG = "BackupFeatureManager"
    }

    // --- Cloud Backup State ---
    val backupState: StateFlow<BackupOperationState> = cloudBackupManager.backupState
    val restoreState: StateFlow<BackupOperationState> = cloudBackupManager.restoreState
    val isSignedIn: StateFlow<Boolean> =
        authManager.isSignedIn
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    private val _availableCloudBackups = MutableStateFlow<List<BackupMetadata>>(emptyList())
    val availableCloudBackups: StateFlow<List<BackupMetadata>> = _availableCloudBackups.asStateFlow()

    private val _isLoadingCloudBackups = MutableStateFlow(false)
    val isLoadingCloudBackups: StateFlow<Boolean> = _isLoadingCloudBackups.asStateFlow()

    private val _isLoadingLocalBackups = MutableStateFlow(false)
    val isLoadingLocalBackups: StateFlow<Boolean> = _isLoadingLocalBackups.asStateFlow()

    // --- Local Backup State ---
    val localBackupState: StateFlow<BackupOperationState> = localBackupManager.localBackupState

    private val _availableLocalBackups = MutableStateFlow<List<LocalBackupMetadata>>(emptyList())
    val availableLocalBackups: StateFlow<List<LocalBackupMetadata>> = _availableLocalBackups.asStateFlow()

    // --- Settings State ---
    private val _lastBackupTime = MutableStateFlow(securePreferences.getLastBackupTime())
    val lastBackupTime: StateFlow<Long> = _lastBackupTime.asStateFlow()

    private val _autoBackupEnabled = MutableStateFlow(securePreferences.isAutoBackupEnabled())
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    /**
     * Trigger a cloud backup immediately.
     */
    fun performCloudBackup() {
        scope.launch {
            Log.i(TAG, "Initiating cloud backup")

            // Ensure fresh session
            authManager.silentSignIn()
            if (!authManager.isSignedIn.value) {
                Log.w(TAG, "Cloud backup aborted: Not signed in")
                return@launch
            }

            val result = cloudBackupManager.createBackup()
            if (result.isSuccess) {
                _lastBackupTime.value = System.currentTimeMillis()
                loadCloudBackups()
            }
        }
    }

    /**
     * Restore from a specific cloud backup.
     */
    fun restoreFromCloud(metadata: BackupMetadata) {
        scope.launch {
            Log.i(TAG, "Initiating cloud restore: ${metadata.driveFileId}")

            // Ensure fresh session
            authManager.silentSignIn()
            if (!authManager.isSignedIn.value) {
                Log.w(TAG, "Cloud restore aborted: Not signed in")
                return@launch
            }

            cloudBackupManager.restoreBackup(metadata)
        }
    }

    /**
     * Load available backups from Google Drive.
     */
    fun loadCloudBackups() {
        scope.launch {
            _isLoadingCloudBackups.value = true
            try {
                // Ensure we have a fresh token/session
                authManager.silentSignIn()

                if (!authManager.isSignedIn.value) {
                    Log.w(TAG, "Cannot load backups: Not signed in after silent sign-in attempt")
                    return@launch
                }

                val result = driveService.listBackups()
                _availableCloudBackups.value = result.getOrDefault(emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cloud backups: ${e.message}")
            } finally {
                _isLoadingCloudBackups.value = false
            }
        }
    }

    /**
     * Create a local ZIP backup.
     */
    fun createLocalBackup() {
        scope.launch {
            Log.i(TAG, "Creating local backup ZIP")
            val result = localBackupManager.createLocalBackup()
            if (result.isSuccess) {
                loadLocalBackups()
            }
        }
    }

    /**
     * Load available local backups.
     */
    fun loadLocalBackups() {
        scope.launch {
            _isLoadingLocalBackups.value = true
            try {
                _availableLocalBackups.value = localBackupManager.listLocalBackups()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load local backups: ${e.message}")
            } finally {
                _isLoadingLocalBackups.value = false
            }
        }
    }

    /**
     * Delete a local backup file.
     */
    fun deleteLocalBackup(metadata: LocalBackupMetadata) {
        scope.launch {
            localBackupManager.deleteLocalBackup(metadata)
            loadLocalBackups()
        }
    }

    /**
     * Create a share intent for a local backup file.
     */
    fun createLocalBackupShareIntent(metadata: LocalBackupMetadata): Intent = localBackupManager.createShareIntent(metadata)

    /**
     * Configure auto-backup settings.
     */
    fun setAutoBackup(
        enabled: Boolean,
        intervalDays: Int = 1,
    ) {
        securePreferences.setAutoBackupEnabled(enabled)
        securePreferences.setAutoBackupIntervalDays(intervalDays)
        _autoBackupEnabled.value = enabled

        if (enabled) {
            AutoBackupWorker.schedule(context, intervalDays)
            Log.i(TAG, "Auto-backup scheduled every $intervalDays days")
        } else {
            AutoBackupWorker.cancel(context)
            Log.i(TAG, "Auto-backup disabled")
        }
    }

    /**
     * Reset operation states.
     */
    fun resetStates() {
        cloudBackupManager.resetBackupState()
        cloudBackupManager.resetRestoreState()
        localBackupManager.resetLocalBackupState()
    }
}
