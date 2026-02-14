package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smarty.core.common.util.PrivacyAware
import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.UUID

/**
 * Calendar event entity for scheduling and reminders.
 * Supports privacy filtering through PrivacyAware interface.
 */
@Serializable
@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
        Index(value = ["isEventPrivate"]),
        Index(value = ["linkedNoteId"]),
        Index(value = ["startTime", "endTime"]),
        // DB-004: Composite index for efficient privacy-filtered time queries
        Index(value = ["isEventPrivate", "startTime"]),
        // PERFORMANCE: Index for Google Calendar sync lookups
        Index(value = ["googleEventId"])
    ]
)
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
    val googleEventId: String? = null,      // Google Calendar Event ID for two-way sync
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
     *
     * BUG FIX (NEW-014): Use Calendar for proper local timezone handling.
     * The previous implementation used modulo arithmetic which gives UTC midnight,
     * not local midnight, causing events to appear on wrong days in different timezones.
     */
    fun isToday(): Boolean {
        val calendar = Calendar.getInstance()
        // Get today's start in local timezone
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val dayStart = calendar.timeInMillis

        // Get tomorrow's start
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = calendar.timeInMillis

        return startTime in dayStart until dayEnd
    }

    /**
     * Duration in minutes
     */
    val durationMinutes: Long
        get() = (endTime - startTime) / (60 * 1000)
}
