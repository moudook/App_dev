package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Timer/Alarm entity for scheduled notifications.
 * Supports one-time timers and recurring alarms on specific days.
 */
@Entity(tableName = "timers")
data class SmartyTimer(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerTime: Long,  // Epoch milliseconds for one-time, or time of day for recurring
    val repeatDays: String? = null,  // JSON array: ["monday", "friday"], null for one-time
    val isAlarm: Boolean = false,  // true = alarm (louder, vibrate), false = timer (notification)
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if this is a recurring timer/alarm.
     */
    val isRecurring: Boolean get() = !repeatDays.isNullOrEmpty()

    companion object {
        // Day name constants for repeatDays JSON
        const val MONDAY = "monday"
        const val TUESDAY = "tuesday"
        const val WEDNESDAY = "wednesday"
        const val THURSDAY = "thursday"
        const val FRIDAY = "friday"
        const val SATURDAY = "saturday"
        const val SUNDAY = "sunday"

        val WEEKDAYS = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val WEEKEND = listOf(SATURDAY, SUNDAY)
        val EVERYDAY = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
    }
}
