package com.example.smarty.data.worker

import android.content.Context
import androidx.work.*
import com.example.smarty.data.backup.AutoBackupConfig
import com.example.smarty.data.backup.BackupManager
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.remote.DriveService
import com.example.smarty.data.remote.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for scheduled auto-backups.
 *
 * Runs periodically (default 100 days) to backup data to Google Drive.
 * Only runs when:
 * - Device is connected to network
 * - Battery is not low
 * - User is signed in to Google
 * - Auto-backup is enabled
 */
class AutoBackupWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val WORK_NAME = "Smarty_auto_backup"
        private const val TAG = "AutoBackupWorker"

        /**
         * Schedule periodic auto-backup.
         *
         * @param context Application context
         * @param intervalDays Days between backups (default 100)
         */
        fun schedule(
            context: Context,
            intervalDays: Int = AutoBackupConfig.DEFAULT_BACKUP_INTERVAL_DAYS,
        ) {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<AutoBackupWorker>(
                    intervalDays.toLong(),
                    TimeUnit.DAYS,
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .addTag(TAG)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            )
        }

        /**
         * Cancel scheduled auto-backup.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Run backup immediately (for testing or manual trigger).
         */
        fun runNow(context: Context) {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<AutoBackupWorker>()
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        /**
         * Check if auto-backup is currently scheduled.
         */
        suspend fun isScheduled(context: Context): Boolean =
            withContext(Dispatchers.IO) {
                val workInfos =
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(WORK_NAME)
                        .get()

                workInfos.any { !it.state.isFinished }
            }
    }

    override suspend fun doWork(): Result {
        return try {
            // Check if auto-backup is still enabled
            val securePreferences = SecurePreferences.getInstance(context)
            if (!securePreferences.isAutoBackupEnabled()) {
                return Result.success()
            }

            // Check if backup is due
            if (!securePreferences.isBackupDue()) {
                return Result.success()
            }

            // Initialize components
            val authManager = GoogleAuthManager(context)

            // Attempt silent sign-in to refresh token
            authManager.silentSignIn()

            // Check if user is signed in
            if (!authManager.isSignedIn.value) {
                return Result.retry()
            }

            val database = SmartyDatabase.getDatabase(context)
            val driveService = DriveService(context, authManager)
            val backupManager =
                BackupManager(
                    context = context,
                    database = database,
                    securePreferences = securePreferences,
                    driveService = driveService,
                    authManager = authManager,
                )

            // Perform backup
            val result = backupManager.createBackup()

            if (result.isSuccess) {
                Result.success()
            } else {
                // Retry on failure
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
