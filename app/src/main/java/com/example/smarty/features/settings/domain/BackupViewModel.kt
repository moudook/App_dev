package com.example.smarty.features.settings.domain

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.backup.BackupManager
import com.example.smarty.data.backup.BackupMetadata
import com.example.smarty.data.backup.BackupOperationState
import com.example.smarty.data.backup.LocalBackupManager
import com.example.smarty.data.backup.LocalBackupMetadata
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.remote.DriveService
import com.example.smarty.data.remote.GoogleAuthManager
import com.example.smarty.features.settings.domain.BackupFeatureManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing backup operations (both Google Drive and local).
 * Delegated to BackupFeatureManager for centralized logic.
 */
class BackupViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(application)
    }

    val authManager: GoogleAuthManager by lazy {
        GoogleAuthManager(application)
    }

    private val database: SmartyDatabase by lazy {
        SmartyDatabase.getDatabase(application)
    }

    private val driveService: DriveService by lazy {
        DriveService(application, authManager)
    }

    private val cloudBackupManager: BackupManager by lazy {
        BackupManager(application, database, securePreferences, driveService, authManager)
    }

    private val localBackupManager: LocalBackupManager by lazy {
        LocalBackupManager(application, database, securePreferences)
    }

    // Backup Feature Manager - Centralized logic
    private val backupFeatureManager: BackupFeatureManager by lazy {
        BackupFeatureManager(
            context = application,
            scope = viewModelScope,
            securePreferences = securePreferences,
            authManager = authManager,
            driveService = driveService,
            cloudBackupManager = cloudBackupManager,
            localBackupManager = localBackupManager,
        )
    }

    // Auth state
    val isSignedIn: StateFlow<Boolean> = backupFeatureManager.isSignedIn

    val signedInEmail: String?
        get() = authManager.getSignedInEmail()

    val signedInDisplayName: String?
        get() = authManager.getSignedInDisplayName()

    val signedInPhotoUrl: String?
        get() = authManager.getSignedInPhotoUrl()

    // Observation State
    val backupState: StateFlow<BackupOperationState> = backupFeatureManager.backupState
    val restoreState: StateFlow<BackupOperationState> = backupFeatureManager.restoreState
    val localBackupState: StateFlow<BackupOperationState> = backupFeatureManager.localBackupState
    val availableBackups: StateFlow<List<BackupMetadata>> = backupFeatureManager.availableCloudBackups
    val localBackups: StateFlow<List<LocalBackupMetadata>> = backupFeatureManager.availableLocalBackups
    val isLoadingBackups: StateFlow<Boolean> = backupFeatureManager.isLoadingCloudBackups
    val isLoadingLocalBackups: StateFlow<Boolean> = backupFeatureManager.isLoadingLocalBackups
    val lastBackupTime: StateFlow<Long> = backupFeatureManager.lastBackupTime
    val autoBackupEnabled: StateFlow<Boolean> = backupFeatureManager.autoBackupEnabled

    private val _autoBackupIntervalDays = MutableStateFlow(securePreferences.getAutoBackupIntervalDays())
    val autoBackupIntervalDays: StateFlow<Int> = _autoBackupIntervalDays.asStateFlow()

    init {
        // Initial load
        backupFeatureManager.loadCloudBackups()
        backupFeatureManager.loadLocalBackups()
    }

    fun getSignInIntent(): Intent {
        // Use the app-level signed in email as a hint if available
        val emailHint = securePreferences.getGoogleAccountEmail()
        return authManager.getSignInIntent(emailHint)
    }

    fun handleSignInResult(data: Intent?) {
        val result = authManager.handleSignInResult(data)
        if (result.isSuccess) {
            securePreferences.setGoogleAccountEmail(authManager.getSignedInEmail())
            backupFeatureManager.loadCloudBackups()
        }
    }

    fun signOut() {
        authManager.signOut {
            securePreferences.setGoogleAccountEmail(null)
            backupFeatureManager.setAutoBackup(false)
        }
    }

    fun createBackup() = backupFeatureManager.performCloudBackup()

    fun restoreBackup(metadata: BackupMetadata) = backupFeatureManager.restoreFromCloud(metadata)

    fun deleteBackup(metadata: BackupMetadata) {
        viewModelScope.launch {
            driveService.deleteBackup(metadata.driveFileId)
            backupFeatureManager.loadCloudBackups()
        }
    }

    fun loadAvailableBackups() = backupFeatureManager.loadCloudBackups()

    fun setAutoBackupEnabled(enabled: Boolean) {
        backupFeatureManager.setAutoBackup(enabled, _autoBackupIntervalDays.value)
    }

    fun setAutoBackupIntervalDays(days: Int) {
        _autoBackupIntervalDays.value = days
        if (autoBackupEnabled.value) {
            backupFeatureManager.setAutoBackup(true, days)
        }
    }

    fun resetBackupState() = backupFeatureManager.resetStates()

    fun resetRestoreState() = backupFeatureManager.resetStates()

    fun refreshLastBackupTime() { /* Handled reactively */ }

    fun createLocalBackup() = backupFeatureManager.createLocalBackup()

    fun loadLocalBackups() = backupFeatureManager.loadLocalBackups()

    fun deleteLocalBackup(metadata: LocalBackupMetadata) = backupFeatureManager.deleteLocalBackup(metadata)

    fun getLocalBackupShareIntent(metadata: LocalBackupMetadata) = backupFeatureManager.createLocalBackupShareIntent(metadata)

    fun resetLocalBackupState() = backupFeatureManager.resetStates()
}
