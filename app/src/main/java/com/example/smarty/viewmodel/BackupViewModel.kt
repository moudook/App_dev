package com.example.smarty.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.backup.BackupManager
import com.example.smarty.data.backup.BackupMetadata
import com.example.smarty.data.backup.BackupOperationState
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.DriveService
import com.example.smarty.data.remote.GoogleAuthManager
import com.example.smarty.data.worker.AutoBackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing Google Drive backup operations.
 *
 * Handles:
 * - Google Sign-In state
 * - Backup/restore operations
 * - Available backups listing
 * - Auto-backup scheduling
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val securePreferences = SecurePreferences.getInstance(application)
    val authManager = GoogleAuthManager(application)

    private val database = CogniDatabase.getDatabase(application)
    private val driveService = DriveService(application, authManager)
    private val backupManager = BackupManager(
        context = application,
        database = database,
        securePreferences = securePreferences,
        driveService = driveService
    )

    // Auth state
    val isSignedIn: StateFlow<Boolean> = authManager.isSignedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val signedInEmail: String?
        get() = authManager.getSignedInEmail()

    val signedInDisplayName: String?
        get() = authManager.getSignedInDisplayName()

    val signedInPhotoUrl: String?
        get() = authManager.getSignedInPhotoUrl()

    // Backup state
    val backupState: StateFlow<BackupOperationState> = backupManager.backupState
    val restoreState: StateFlow<BackupOperationState> = backupManager.restoreState

    // Available backups
    private val _availableBackups = MutableStateFlow<List<BackupMetadata>>(emptyList())
    val availableBackups: StateFlow<List<BackupMetadata>> = _availableBackups.asStateFlow()

    // Loading state for backup list
    private val _isLoadingBackups = MutableStateFlow(false)
    val isLoadingBackups: StateFlow<Boolean> = _isLoadingBackups.asStateFlow()

    // Settings
    private val _lastBackupTime = MutableStateFlow(securePreferences.getLastBackupTime())
    val lastBackupTime: StateFlow<Long> = _lastBackupTime.asStateFlow()

    private val _autoBackupEnabled = MutableStateFlow(securePreferences.isAutoBackupEnabled())
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    private val _autoBackupIntervalDays = MutableStateFlow(securePreferences.getAutoBackupIntervalDays())
    val autoBackupIntervalDays: StateFlow<Int> = _autoBackupIntervalDays.asStateFlow()

    init {
        // Load backups if signed in
        if (authManager.isSignedIn.value) {
            loadAvailableBackups()
        }
    }

    /**
     * Get the sign-in intent for launching Google Sign-In.
     */
    fun getSignInIntent(): Intent {
        return authManager.getSignInIntent()
    }

    /**
     * Handle the result from Google Sign-In activity.
     */
    fun handleSignInResult(data: Intent?) {
        val result = authManager.handleSignInResult(data)
        if (result.isSuccess) {
            securePreferences.setGoogleAccountEmail(authManager.getSignedInEmail())
            loadAvailableBackups()
        }
    }

    /**
     * Sign out from Google account.
     */
    fun signOut() {
        authManager.signOut {
            securePreferences.setGoogleAccountEmail(null)
            _availableBackups.value = emptyList()

            // Cancel auto-backup if enabled
            if (_autoBackupEnabled.value) {
                AutoBackupWorker.cancel(getApplication())
            }
        }
    }

    /**
     * Create a new backup and upload to Google Drive.
     */
    fun createBackup() {
        viewModelScope.launch {
            val result = backupManager.createBackup()
            if (result.isSuccess) {
                _lastBackupTime.value = System.currentTimeMillis()
                loadAvailableBackups()
            }
        }
    }

    /**
     * Restore from a backup.
     */
    fun restoreBackup(metadata: BackupMetadata) {
        viewModelScope.launch {
            backupManager.restoreBackup(metadata)
        }
    }

    /**
     * Delete a backup from Google Drive.
     */
    fun deleteBackup(metadata: BackupMetadata) {
        viewModelScope.launch {
            driveService.deleteBackup(metadata.driveFileId)
            loadAvailableBackups()
        }
    }

    /**
     * Load available backups from Google Drive.
     */
    fun loadAvailableBackups() {
        viewModelScope.launch {
            _isLoadingBackups.value = true
            val result = driveService.listBackups()
            _availableBackups.value = result.getOrDefault(emptyList())
            _isLoadingBackups.value = false
        }
    }

    /**
     * Enable or disable auto-backup.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        securePreferences.setAutoBackupEnabled(enabled)
        _autoBackupEnabled.value = enabled

        if (enabled) {
            AutoBackupWorker.schedule(getApplication(), _autoBackupIntervalDays.value)
        } else {
            AutoBackupWorker.cancel(getApplication())
        }
    }

    /**
     * Set auto-backup interval in days.
     */
    fun setAutoBackupIntervalDays(days: Int) {
        securePreferences.setAutoBackupIntervalDays(days)
        _autoBackupIntervalDays.value = days

        // Reschedule if enabled
        if (_autoBackupEnabled.value) {
            AutoBackupWorker.schedule(getApplication(), days)
        }
    }

    /**
     * Reset backup state to idle.
     */
    fun resetBackupState() {
        backupManager.resetBackupState()
    }

    /**
     * Reset restore state to idle.
     */
    fun resetRestoreState() {
        backupManager.resetRestoreState()
    }

    /**
     * Refresh last backup time from preferences.
     */
    fun refreshLastBackupTime() {
        _lastBackupTime.value = securePreferences.getLastBackupTime()
    }
}
