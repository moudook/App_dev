package com.example.smarty.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.smarty.MainActivity
import com.example.smarty.R
import com.example.smarty.data.local.CogniDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that runs daily at 6:30 AM to generate a digest notification.
 * Summarizes recent notes and provides a motivational start to the day.
 */
class DailyDigestWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DailyDigestWorker"
        private const val WORK_NAME = "daily_digest_work"
        private const val CHANNEL_ID = "daily_digest_channel"
        private const val NOTIFICATION_ID = 1003  // Unique ID to avoid conflicts with other services

        /**
         * Schedule the daily digest to run at 6:30 AM every day.
         */
        fun schedule(context: Context) {
            val currentTime = Calendar.getInstance()
            val targetTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                // If it's already past 6:30 AM, schedule for tomorrow
                if (before(currentTime)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

            val workRequest = PeriodicWorkRequestBuilder<DailyDigestWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Log.d(TAG, "Daily digest scheduled. First run in ${initialDelay / 1000 / 60} minutes")
        }

        /**
         * Cancel the daily digest schedule.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Daily digest cancelled")
        }

        /**
         * Create the notification channel for daily digest.
         * Should be called on app startup.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Daily Digest",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Morning summary of your notes and tasks"
                }

                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Daily digest worker started")

        try {
            // Generate digest content
            val digest = generateDigest()

            // Show notification
            showNotification(digest)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily digest failed: ${e.message}", e)
            return Result.retry()
        }
    }

    private suspend fun generateDigest(): DigestContent {
        // Use applicationContext to prevent context leaks
        val appContext = applicationContext
        val database = CogniDatabase.getDatabase(appContext)
        val noteDao = database.noteDao()

        // Get notes from the last 24 hours
        // INPUT-002: Cap notes fetched to prevent memory issues
        // WORKER-002/MED-001: Exclude private notes from digest to protect user privacy
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val recentNotes = noteDao.getNotesCreatedAfter(oneDayAgo)
            .filter { !it.isFullPrivacy }  // Exclude private notes
            .take(100)

        // Get total note count
        val totalNotes = noteDao.getNoteCount()

        // Get pinned notes count
        val pinnedNotes = noteDao.getPinnedNotesCount()

        // Build digest
        val title = when {
            recentNotes.isEmpty() -> "Good morning!"
            recentNotes.size == 1 -> "You captured 1 note yesterday"
            else -> "You captured ${recentNotes.size} notes yesterday"
        }

        val body = buildString {
            if (recentNotes.isNotEmpty()) {
                append("Latest: \"${recentNotes.first().title.take(40)}\"")
                if (recentNotes.first().title.length > 40) append("...")
                append("\n")
            }

            append("$totalNotes total notes")

            if (pinnedNotes > 0) {
                append(" | $pinnedNotes pinned")
            }
        }

        return DigestContent(title, body)
    }

    private fun showNotification(digest: DigestContent) {
        // Use applicationContext to prevent context leaks
        val appContext = applicationContext

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted")
                return
            }
        }

        // Create intent to open app
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_note)
            .setContentTitle(digest.title)
            .setContentText(digest.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(digest.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Daily digest notification shown")
    }

    data class DigestContent(
        val title: String,
        val body: String
    )
}
