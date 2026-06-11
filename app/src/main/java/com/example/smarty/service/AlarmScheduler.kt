package com.example.smarty.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.data.local.SmartyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Schedules and manages timers/alarms using AlarmManager.
 * Supports both one-time timers and recurring alarms.
 *
 * BUG FIX (RX-02): Added permission request flow for SCHEDULE_EXACT_ALARM
 * on Android 12+ (API 31+). Without this permission, alarms may fire late.
 *
 * HARDENING: Now persists all timers to Room database to survive app kills and reboots.
 */
class AlarmScheduler(
    private val context: Context,
) {
    private val database = SmartyDatabase.getDatabase(context)
    private val timerDao = database.timerDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cancel() {
        scope.cancel()
    }

    /**
     * Observable stream of active timers.
     */
    val activeTimers: StateFlow<List<SmartyTimer>> =
        timerDao
            .getActiveTimers()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    companion object {
        private const val TAG = "AlarmScheduler"

        // Singleton instance
        @Volatile
        private var INSTANCE: AlarmScheduler? = null

        fun getInstance(context: Context): AlarmScheduler =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AlarmScheduler(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Result of attempting to schedule an alarm.
     * See §3.2 of client_architecture.md: AlarmScheduler Permission Contract (API 33+).
     */
    sealed class AlarmResult {
        /** Alarm was scheduled successfully. */
        object Success : AlarmResult()
        /** Exact alarm permission is not granted (Android 12+, API 31+).
         *  Caller MUST NOT silently reject. Use createExactAlarmPermissionIntent() to
         *  open settings, and send a "failed" ACK to the server in the meantime. */
        object PermissionRequired : AlarmResult()
        /** A runtime exception occurred during scheduling. */
        data class Error(val message: String, val cause: Throwable? = null) : AlarmResult()
    }

    /**
     * Schedule a timer or alarm, returning an [AlarmResult] to the caller.
     * Does NOT silently fall back to inexact alarms — callers must handle [AlarmResult.PermissionRequired]
     * by prompting the user via [createExactAlarmPermissionIntent] (API 31+).
     */
    fun scheduleTimer(timer: SmartyTimer): AlarmResult {
        Log.d(TAG, "Scheduling timer: ${timer.name} at ${timer.triggerTime}")

        // §3.2 Permission contract: check before persisting or scheduling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "SCHEDULE_EXACT_ALARM permission not granted — returning PermissionRequired for timer=${timer.name}")
            return AlarmResult.PermissionRequired
        }

        // Persist to database
        scope.launch {
            timerDao.insertTimer(timer)
        }

        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_TIMER_TRIGGERED
                putExtra(AlarmReceiver.EXTRA_TIMER_ID, timer.id)
                putExtra(AlarmReceiver.EXTRA_TIMER_NAME, timer.name)
                putExtra(AlarmReceiver.EXTRA_IS_ALARM, timer.isAlarm)
                putExtra(AlarmReceiver.EXTRA_IS_RECURRING, timer.isRecurring)
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, timer.repeatDays)
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                timer.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                timer.triggerTime,
                pendingIntent,
            )
            Log.d(TAG, "Timer scheduled successfully: ${timer.name}")
            AlarmResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling timer (permission revoked mid-flight): ${e.message}", e)
            AlarmResult.PermissionRequired
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule timer: ${e.message}", e)
            AlarmResult.Error(e.message ?: "Unknown scheduling error", e)
        }
    }

    /**
     * Cancel a scheduled timer/alarm.
     */
    fun cancelTimer(timerId: String) {
        Log.d(TAG, "Cancelling timer: $timerId")

        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_TIMER_TRIGGERED
                putExtra(AlarmReceiver.EXTRA_TIMER_ID, timerId)
                putExtra(AlarmReceiver.EXTRA_TIMER_NAME, "") // Not needed for cancellation
                putExtra(AlarmReceiver.EXTRA_IS_ALARM, false)
                putExtra(AlarmReceiver.EXTRA_IS_RECURRING, false)
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, "")
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                timerId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Pending intent cancelled for timer: $timerId")
        }

        // Remove from database after cancelling the alarm
        scope.launch {
            timerDao.deleteTimerById(timerId)
            Log.d(TAG, "Timer removed from database: $timerId")
        }

        Log.d(TAG, "Timer cancelled: $timerId")
    }

    /**
     * Check if exact alarms can be scheduled.
     */
    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * BUG FIX (RX-02): Create intent to request SCHEDULE_EXACT_ALARM permission.
     * On Android 12+ (API 31+), this permission is required for exact alarms.
     * Returns null on older Android versions where permission is not needed.
     *
     * Usage: Launch this intent from an Activity to open system settings.
     */
    fun createExactAlarmPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            null // Not needed on older Android versions
        }

    /**
     * BUG FIX (RX-02): Check if exact alarm permission should be requested.
     * Returns true if on Android 12+ and permission is not granted.
     */
    fun shouldRequestExactAlarmPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()

    /**
     * BUG FIX (RX-02): Get user-friendly message about exact alarm permission.
     */
    fun getExactAlarmPermissionMessage(): String =
        if (shouldRequestExactAlarmPermission()) {
            "For precise timer functionality, please allow exact alarms in Settings."
        } else {
            ""
        }
}
