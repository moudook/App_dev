package com.example.smarty.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.smarty.data.model.CogniTimer

/**
 * Schedules and manages timers/alarms using AlarmManager.
 * Supports both one-time timers and recurring alarms.
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        private const val TAG = "AlarmScheduler"

        // Singleton instance
        @Volatile
        private var INSTANCE: AlarmScheduler? = null

        fun getInstance(context: Context): AlarmScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AlarmScheduler(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule a timer or alarm.
     * For one-time timers, schedules at the specified triggerTime.
     * For recurring alarms, schedules the next occurrence based on repeatDays.
     */
    fun scheduleTimer(timer: CogniTimer) {
        Log.d(TAG, "Scheduling timer: ${timer.name} at ${timer.triggerTime}")

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TIMER_TRIGGERED
            putExtra(AlarmReceiver.EXTRA_TIMER_ID, timer.id)
            putExtra(AlarmReceiver.EXTRA_TIMER_NAME, timer.name)
            putExtra(AlarmReceiver.EXTRA_IS_ALARM, timer.isAlarm)
            putExtra(AlarmReceiver.EXTRA_IS_RECURRING, timer.isRecurring)
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, timer.repeatDays)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timer.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use exact alarm for precise timing
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        timer.triggerTime,
                        pendingIntent
                    )
                } else {
                    // Fallback to inexact alarm
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        timer.triggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Cannot schedule exact alarms, using inexact")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    timer.triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Timer scheduled successfully: ${timer.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule timer: ${e.message}", e)
        }
    }

    /**
     * Cancel a scheduled timer/alarm.
     */
    fun cancelTimer(timerId: String) {
        Log.d(TAG, "Cancelling timer: $timerId")

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TIMER_TRIGGERED
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timerId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Timer cancelled: $timerId")
        }
    }

    /**
     * Check if exact alarms can be scheduled.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
