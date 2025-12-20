package com.example.smarty.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smarty.MainActivity
import com.example.smarty.R

/**
 * Receives timer/alarm broadcasts and triggers notifications with audio.
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TIMER_TRIGGERED) return

        val timerId = intent.getStringExtra(EXTRA_TIMER_ID) ?: return
        val timerName = intent.getStringExtra(EXTRA_TIMER_NAME) ?: "Timer"
        val isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, false)
        val isRecurring = intent.getBooleanExtra(EXTRA_IS_RECURRING, false)

        Log.d(TAG, "Timer triggered: $timerName (alarm=$isAlarm, recurring=$isRecurring)")

        // Play alarm audio for 5 seconds
        AlarmAudioPlayer.play(context, duration = 5000)

        // Show notification
        showNotification(context, timerId, timerName, isAlarm)

        // For recurring alarms, schedule the next occurrence
        // (This would be handled by a WorkManager job or rescheduling logic)
        if (isRecurring) {
            Log.d(TAG, "Recurring alarm - next occurrence would be scheduled here")
            // TODO: Schedule next occurrence based on repeatDays
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
