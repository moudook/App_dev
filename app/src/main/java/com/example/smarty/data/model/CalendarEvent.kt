package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smarty.util.PrivacyAware
import java.util.UUID

/**
 * Calendar event entity for scheduling and reminders.
 * Supports privacy filtering through PrivacyAware interface.
 */
@Entity(tableName = "calendar_events")
data class CalendarEvent(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val startTime: Long,                    // Unix timestamp in milliseconds
    val endTime: Long,                      // Unix timestamp in milliseconds
    val isAllDay: Boolean = false,
    val color: Int? = null,                 // Optional color for visual distinction
    val location: String? = null,
    val reminderMinutes: Int? = null,       // Minutes before event to remind
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,     // iCal RRULE format for recurring events
    val linkedNoteId: String? = null,       // Optional link to a note
    val isEventPrivate: Boolean = false,    // Privacy flag - hidden from AI
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : PrivacyAware {

    /**
     * PrivacyAware implementation - private events are invisible to AI.
     * This is a computed property (no backing field) so Room ignores it automatically.
     */
    override val isPrivate: Boolean
        get() = isEventPrivate

    /**
     * Check if event is happening now
     */
    fun isHappeningNow(): Boolean {
        val now = System.currentTimeMillis()
        return now in startTime..endTime
    }

    /**
     * Check if event is in the past
     */
    fun isPast(): Boolean = endTime < System.currentTimeMillis()

    /**
     * Check if event is today
     */
    fun isToday(): Boolean {
        val now = System.currentTimeMillis()
        val dayStart = now - (now % (24 * 60 * 60 * 1000))
        val dayEnd = dayStart + (24 * 60 * 60 * 1000)
        return startTime in dayStart until dayEnd
    }

    /**
     * Duration in minutes
     */
    val durationMinutes: Long
        get() = (endTime - startTime) / (60 * 1000)
}
