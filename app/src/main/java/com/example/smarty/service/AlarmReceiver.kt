package com.example.smarty.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smarty.MainActivity
import com.example.smarty.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receives timer/alarm broadcasts and triggers notifications with audio.
 *
 * BUG FIX (RX-01): Uses goAsync() and WakeLock to prevent CPU sleep
 * during audio playback. Without this, Android kills the process
 * immediately after onReceive returns.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"

        const val ACTION_TIMER_TRIGGERED = "com.example.smarty.TIMER_TRIGGERED"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_NAME = "timer_name"
        const val EXTRA_IS_ALARM = "is_alarm"
        const val EXTRA_IS_RECURRING = "is_recurring"
        const val EXTRA_REPEAT_DAYS = "repeat_days"

        private const val NOTIFICATION_CHANNEL_ID = "cogni_alarms"
        private const val NOTIFICATION_CHANNEL_NAME = "Timers & Alarms"

        // WakeLock timeout slightly longer than audio duration to ensure completion
        private const val WAKELOCK_TIMEOUT_MS = 10_000L // 10 seconds
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TIMER_TRIGGERED) return

        val timerId = intent.getStringExtra(EXTRA_TIMER_ID) ?: return
        // INPUT-003: Validate timer name length to prevent UI overflow/memory issues
        val rawTimerName = intent.getStringExtra(EXTRA_TIMER_NAME) ?: "Timer"
        val timerName = if (rawTimerName.length > 100) rawTimerName.take(97) + "..." else rawTimerName
        val isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, false)
        val isRecurring = intent.getBooleanExtra(EXTRA_IS_RECURRING, false)

        Log.d(TAG, "Timer triggered: $timerName (alarm=$isAlarm, recurring=$isRecurring)")

        // BUG FIX (RX-01): Use goAsync() to keep BroadcastReceiver alive
        val pendingResult = goAsync()

        // Acquire WakeLock to prevent CPU sleep during audio playback
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Cogni:AlarmWakeLock"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

        try {
            // Play alarm audio for 5 seconds
            AlarmAudioPlayer.play(context, duration = 5000)

            // Show notification
            showNotification(context, timerId, timerName, isAlarm)

            // For recurring alarms, schedule the next occurrence
            if (isRecurring) {
                Log.d(TAG, "Recurring alarm - next occurrence would be scheduled here")
                // TODO: Schedule next occurrence based on repeatDays
            }

            // Wait for audio to complete before releasing resources
            // Use coroutine to avoid blocking the main thread
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    delay(6000) // Wait for 5s audio + 1s buffer
                } finally {
                    // Release WakeLock and finish broadcast
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                    pendingResult.finish()
                    Log.d(TAG, "Alarm broadcast completed, resources released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in alarm receiver: ${e.message}", e)
            // Ensure cleanup on error
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            pendingResult.finish()
        }
    }

    private fun showNotification(
        context: Context,
        timerId: String,
        timerName: String,
        isAlarm: Boolean
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (isAlarm) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                importance
            ).apply {
                description = "Notifications for timers and alarms"
                enableVibration(isAlarm)
            }

            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to open app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingContentIntent = PendingIntent.getActivity(
            context,
            timerId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create dismiss action to stop audio
        val dismissIntent = Intent(context, AlarmDismissReceiver::class.java).apply {
            action = AlarmDismissReceiver.ACTION_DISMISS
            putExtra(EXTRA_TIMER_ID, timerId)
        }

        val pendingDismissIntent = PendingIntent.getBroadcast(
            context,
            timerId.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)  // Use app icon
            .setContentTitle(if (isAlarm) "Alarm" else "Timer")
            .setContentText(timerName)
            .setPriority(if (isAlarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingContentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                pendingDismissIntent
            )
            .build()

        notificationManager.notify(timerId.hashCode(), notification)
    }
}

/**
 * Receiver to handle alarm dismissal.
 */
class AlarmDismissReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "com.example.smarty.DISMISS_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return

        val timerId = intent.getStringExtra(AlarmReceiver.EXTRA_TIMER_ID) ?: return
        Log.d("AlarmDismissReceiver", "Dismissing alarm: $timerId")

        // Stop audio playback
        AlarmAudioPlayer.stop()

        // Cancel notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(timerId.hashCode())
    }
}
