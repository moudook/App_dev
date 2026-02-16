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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        private const val NOTIFICATION_CHANNEL_ID = "Smarty_alarms"
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
            "Smarty:AlarmWakeLock"
        )
wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                // BUG FIX: Check if timer still exists in DB before playing
                // This prevents zombie alarms if cancellation didn't clear the PendingIntent
                val db = com.example.smarty.data.local.SmartyDatabase.getDatabase(context)
                val timer = db.timerDao().getTimerById(timerId)

                if (timer == null) {
                    Log.d(TAG, "Timer $timerId not found in DB - skipping alarm (zombie alarm prevention)")
                    return@launch
                }

                // Play alarm audio for 5 seconds
                AlarmAudioPlayer.play(context, duration = 5000)

                // Show notification
                withContext(Dispatchers.Main) {
                    showNotification(context, timerId, timerName, isAlarm)
                }

                // For recurring alarms, schedule the next occurrence
                if (isRecurring) {
                    Log.d(TAG, "Recurring alarm - scheduling next occurrence")
                    scheduleNextOccurrence(context, timerId, timerName, isAlarm, intent.getStringExtra(EXTRA_REPEAT_DAYS))
                } else {
                    // One-time timer/alarm - deactivate in database
                    db.timerDao().deactivateTimer(timerId)
                }

                // Wait for audio to complete before releasing resources
                delay(6000) // Wait for 5s audio + 1s buffer
                Log.d(TAG, "Alarm broadcast completed, resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error in alarm receiver: ${e.message}", e)
            } finally {
                // Release WakeLock and finish broadcast
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
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

    /**
     * Logic to calculate and schedule the next occurrence of a recurring alarm.
     */
    private fun scheduleNextOccurrence(
        context: Context,
        timerId: String,
        timerName: String,
        isAlarm: Boolean,
        repeatDaysJson: String?
    ) {
        if (repeatDaysJson.isNullOrEmpty()) return

        try {
            // Simple parsing of day names (e.g., ["monday", "friday"])
            val days = repeatDaysJson
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .split(",")
                .map { it.trim().lowercase() }

            if (days.isEmpty()) return

            val calendar = java.util.Calendar.getInstance()
            val currentDayOfWeek = when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> "monday"
                java.util.Calendar.TUESDAY -> "tuesday"
                java.util.Calendar.WEDNESDAY -> "wednesday"
                java.util.Calendar.THURSDAY -> "thursday"
                java.util.Calendar.FRIDAY -> "friday"
                java.util.Calendar.SATURDAY -> "saturday"
                java.util.Calendar.SUNDAY -> "sunday"
                else -> ""
            }

            // Find how many days until the next scheduled day
            val dayOrder = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
            val currentIdx = dayOrder.indexOf(currentDayOfWeek)

            var minDaysAway = 8
            for (day in days) {
                val targetIdx = dayOrder.indexOf(day)
                if (targetIdx == -1) continue

                var daysAway = targetIdx - currentIdx
                if (daysAway <= 0) daysAway += 7 // Same day (next week) or earlier in the week

                if (daysAway < minDaysAway) {
                    minDaysAway = daysAway
                }
            }

            if (minDaysAway <= 7) {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, minDaysAway)
                val nextTriggerTime = calendar.timeInMillis

                val nextTimer = com.example.smarty.core.domain.model.SmartyTimer(
                    id = timerId,
                    name = timerName,
                    triggerTime = nextTriggerTime,
                    repeatDays = repeatDaysJson,
                    isAlarm = isAlarm,
                    isActive = true
                )

                AlarmScheduler.getInstance(context).scheduleTimer(nextTimer)
                Log.d(TAG, "Scheduled next occurrence for $timerName in $minDaysAway days")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next occurrence: ${e.message}")
        }
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
