package com.example.smarty.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.smarty.data.local.SmartyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules all active timers and alarms after a device reboot.
 * This is essential for reliability, as AlarmManager loses all alarms on reboot.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Device reboot detected - rescheduling alarms")

            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)

            scope.launch {
                try {
                    val database = SmartyDatabase.getDatabase(context)
                    val activeTimers = database.timerDao().getActiveTimersOnce()
                    val scheduler = AlarmScheduler.getInstance(context)

                    var rescheduledCount = 0
                    val currentTime = System.currentTimeMillis()

                    for (timer in activeTimers) {
                        // Only reschedule if it hasn't expired, OR if it's recurring
                        if (timer.isRecurring || timer.triggerTime > currentTime) {
                            scheduler.scheduleTimer(timer)
                            rescheduledCount++
                        } else {
                            // Mark expired non-recurring timers as inactive
                            database.timerDao().deactivateTimer(timer.id)
                        }
                    }

                    Log.i(TAG, "Rescheduled $rescheduledCount alarms successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule alarms: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
