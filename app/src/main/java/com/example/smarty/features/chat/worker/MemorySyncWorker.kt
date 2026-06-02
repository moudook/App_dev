package com.example.smarty.features.chat.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.features.chat.domain.memory.MemorySyncManager
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that automatically syncs AI memories from notes.
 * This ensures behavioral insights are extracted even if the user doesn't manually sync.
 * Typically runs daily to keep the AI's personal context up-to-date.
 */
class MemorySyncWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val TAG = "MemorySyncWorker"
        private const val WORK_NAME = "automated_memory_sync_work"

        /**
         * Schedule the memory sync to run once every 24 hours.
         */
        fun schedule(context: Context) {
            // Schedule to run daily
            val workRequest =
                PeriodicWorkRequestBuilder<MemorySyncWorker>(
                    1,
                    TimeUnit.DAYS,
                ).setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest,
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
            val database = SmartyDatabase.getDatabase(applicationContext)
            val noteDao = database.noteDao()
            val categoryDao = database.categoryDao()
            val calendarDao = database.calendarDao()
            val noteVersionDao = database.noteVersionDao()
            val aiMemoryDao = database.aiMemoryDao()

            val repository =
                com.example.smarty.data.repository.SmartyRepository(
                    noteDao = noteDao,
                    categoryDao = categoryDao,
                    calendarDao = calendarDao,
                    noteVersionDao = noteVersionDao,
                    context = applicationContext,
                )

            val securePreferences = SecurePreferences.getInstance(applicationContext)
            val aiService =
                com.example.smarty.di.ServiceLocator
                    .provideAIService(applicationContext as android.app.Application)

            // Create a temporary scope for the manager - usually it would be application scope
            // For the worker, we can use the worker's coroutine scope context but wrapped
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

            val noteOperationsManager =
                com.example.smarty.features.notes.domain.NoteOperationsManager(
                    repository = repository,
                    aiService = aiService,
                    context = applicationContext,
                    scope = scope,
                )

            val memoryFeatureManager =
                com.example.smarty.viewmodel.managers
                    .MemoryFeatureManager(aiMemoryDao)

            val memorySyncManager =
                MemorySyncManager(
                    memoryFeatureManager = memoryFeatureManager,
                    noteOperationsManager = noteOperationsManager,
                )

            // Extract behavioral insights from unread notes
            memorySyncManager.syncMemoriesFromNotes()

            // Since our recreated syncMemoriesFromNotes is currently void/mock, we assume success or check side effects
            // If we update SyncResult flow in proper impl, we would observe it.
            // For now, return success.
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Memory sync worker failed: ${e.message}", e)
            return Result.retry()
        }
    }
}
