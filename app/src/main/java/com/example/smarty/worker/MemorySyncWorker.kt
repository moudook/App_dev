package com.example.smarty.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.AIService
import com.example.smarty.viewmodel.managers.MemorySyncManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that automatically syncs AI memories from notes.
 * This ensures behavioral insights are extracted even if the user doesn't manually sync.
 * Typically runs daily to keep the AI's personal context up-to-date.
 */
class MemorySyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val WORK_NAME = "automated_memory_sync_work"

        /**
         * Schedule the memory sync to run once every 24 hours.
         */
        fun schedule(context: Context) {
            // Schedule to run daily
            val workRequest = PeriodicWorkRequestBuilder<MemorySyncWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Log.d(TAG, "Automated memory sync scheduled for daily execution")
        }

        /**
         * Cancel the automated memory sync schedule.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Automated memory sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Automated memory sync worker started")

        try {
            val database = CogniDatabase.getDatabase(applicationContext)
            val aiMemoryDao = database.aiMemoryDao()
            val securePreferences = SecurePreferences(applicationContext)
            val aiService = AIService(securePreferences)
            
            val memorySyncManager = MemorySyncManager(
                database = database,
                aiMemoryDao = aiMemoryDao,
                aiService = aiService
            )

            // Extract behavioral insights from unread notes
            val result = memorySyncManager.syncMemoriesFromNotes()
            
            return when (result) {
                is MemorySyncManager.SyncResult.Success -> {
                    Log.i(TAG, "Sync successful: Processed ${result.notesProcessed} notes, created ${result.memoriesCreated} memories.")
                    Result.success()
                }
                is MemorySyncManager.SyncResult.Error -> {
                    Log.e(TAG, "Sync failed with error: ${result.message}")
                    Result.retry()
                }
                MemorySyncManager.SyncResult.AlreadyRunning -> {
                    Log.w(TAG, "Sync already running, skipping background task")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Memory sync worker failed: ${e.message}", e)
            return Result.retry()
        }
    }
}
