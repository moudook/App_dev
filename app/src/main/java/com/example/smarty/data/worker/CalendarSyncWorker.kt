package com.example.smarty.data.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.smarty.calendar.GoogleCalendarSyncManager
import com.example.smarty.data.local.JarvisDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.repository.JarvisRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that automatically synchronizes Google Calendar events.
 * This ensures the local calendar database stays in sync with the device's Google Calendar.
 */
class CalendarSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CalendarSyncWorker"
        private const val WORK_NAME = "periodic_calendar_sync_work"

        /**
         * Schedule the calendar sync to run periodically.
         * Default interval: 30 minutes
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Periodic calendar sync scheduled every 30 minutes")
        }

        /**
         * Cancel the periodic calendar sync.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Periodic calendar sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Calendar sync worker started")

        val securePreferences = SecurePreferences.getInstance(context)
        if (!securePreferences.isSyncToGoogleCalendarEnabled()) {
            Log.d(TAG, "Calendar sync is disabled in settings, skipping.")
            return Result.success()
        }

        try {
            val database = JarvisDatabase.getDatabase(context)
            val repository = JarvisRepository(
                noteDao = database.noteDao(),
                categoryDao = database.categoryDao(),
                calendarDao = database.calendarDao(),
                noteVersionDao = database.noteVersionDao()
            )

            val syncManager = GoogleCalendarSyncManager(context, repository)

            if (!syncManager.hasCalendarPermission()) {
                Log.w(TAG, "Calendar permission not granted, cannot sync.")
                return Result.failure()
            }

            val targetCalendarId = securePreferences.getTargetGoogleCalendarId()
            val syncedCount = if (targetCalendarId != -1L) {
                Log.d(TAG, "Syncing specific calendar: $targetCalendarId")
                syncManager.syncCalendar(targetCalendarId)
            } else {
                Log.d(TAG, "Syncing all visible Google calendars")
                syncManager.syncAllGoogleCalendars()
            }

            Log.i(TAG, "Calendar sync completed. Synced $syncedCount events.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Calendar sync failed: ${e.message}", e)
            return Result.retry()
        }
    }
}
