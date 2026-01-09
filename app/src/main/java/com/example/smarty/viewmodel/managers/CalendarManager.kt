package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.CalendarDao
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.JarvisTimer
import com.example.smarty.service.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Manages calendar event operations.
 *
 * Responsibilities:
 * - Calendar event CRUD operations
 * - Event queries (by date range, upcoming, etc.)
 * - Alarm scheduling for event reminders
 * - Privacy filtering for AI-visible events
 *
 * @property calendarDao Database access for calendar events
 * @property alarmScheduler Optional alarm scheduler for reminders
 * @property scope Coroutine scope for async operations
 */
class CalendarManager(
    private val calendarDao: CalendarDao,
    private val alarmScheduler: AlarmScheduler?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CalendarManager"
    }

    // ==================== Observable State ====================

    /**
     * All calendar events, ordered by start time.
     * Use for full calendar view.
     */
    val calendarEvents: StateFlow<List<CalendarEvent>> = calendarDao.getAllEvents()
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Upcoming events (from now onwards).
     * Use for dashboard/overview displays.
     */
    val upcomingEvents: Flow<List<CalendarEvent>> = calendarDao.getUpcomingEvents()

    /**
     * AI-visible events (excludes private events).
     * Use when building context for AI/Agent services.
     */
    val aiVisibleEvents: Flow<List<CalendarEvent>> = calendarDao.getAiVisibleEvents()

    // ==================== CRUD Operations ====================

    /**
     * Add a new calendar event.
     *
     * @param title Event title
     * @param description Optional event description
     * @param startTime Event start time (Unix timestamp in milliseconds)
     * @param endTime Event end time (Unix timestamp in milliseconds)
     * @param isAllDay Whether this is an all-day event
     * @param location Optional event location
     * @param color Optional color for visual distinction
     * @param reminderMinutes Minutes before event to remind (null = no reminder)
     * @param isPrivate Whether event is private (hidden from AI)
     * @param linkedNoteId Optional link to a note
     */
    fun addCalendarEvent(
        title: String,
        description: String? = null,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean = false,
        location: String? = null,
        color: Int? = null,
        reminderMinutes: Int? = null,
        isPrivate: Boolean = false,
        linkedNoteId: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val event = CalendarEvent(
                    title = title,
                    description = description,
                    startTime = startTime,
                    endTime = endTime,
                    isAllDay = isAllDay,
                    location = location,
                    color = color,
                    reminderMinutes = reminderMinutes,
                    isEventPrivate = isPrivate,
                    linkedNoteId = linkedNoteId
                )

                calendarDao.insertEvent(event)
                Log.d(TAG, "Added calendar event: $title")

                // Schedule alarm if reminder is set
                reminderMinutes?.let { minutes ->
                    scheduleReminder(event, minutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Add a calendar event from an existing CalendarEvent object.
     */
    fun addCalendarEvent(event: CalendarEvent) {
        scope.launch(Dispatchers.IO) {
            try {
                calendarDao.insertEvent(event)
                Log.d(TAG, "Added calendar event: ${event.title}")

                // Schedule alarm if reminder is set
                event.reminderMinutes?.let { minutes ->
                    scheduleReminder(event, minutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Update an existing calendar event.
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        scope.launch(Dispatchers.IO) {
            try {
                val updatedEvent = event.copy(updatedAt = System.currentTimeMillis())
                calendarDao.updateEvent(updatedEvent)
                Log.d(TAG, "Updated calendar event: ${event.title}")

                // Reschedule alarm if reminder changed
                event.reminderMinutes?.let { minutes ->
                    cancelReminder(event.id)
                    scheduleReminder(updatedEvent, minutes)
                } ?: cancelReminder(event.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a calendar event by ID.
     */
    fun deleteCalendarEvent(eventId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                cancelReminder(eventId)
                calendarDao.deleteEventById(eventId)
                Log.d(TAG, "Deleted calendar event: $eventId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a calendar event.
     */
    fun deleteCalendarEvent(event: CalendarEvent) {
        deleteCalendarEvent(event.id)
    }

    // ==================== Query Operations ====================

    /**
     * Get a single event by ID.
     */
    suspend fun getEventById(eventId: String): CalendarEvent? {
        return try {
            calendarDao.getEventById(eventId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting event by ID: ${e.message}", e)
            null
        }
    }

    /**
     * Get events within a date range.
     *
     * @param startMillis Start of range (inclusive)
     * @param endMillis End of range (exclusive)
     */
    fun getEventsInRange(startMillis: Long, endMillis: Long): Flow<List<CalendarEvent>> {
        return calendarDao.getEventsInRange(startMillis, endMillis)
    }

    /**
     * Get events for a specific day.
     *
     * @param dayMillis Any timestamp within the desired day
     */
    suspend fun getEventsForDay(dayMillis: Long): List<CalendarEvent> {
        val (dayStart, dayEnd) = getDayBounds(dayMillis)
        return try {
            calendarDao.getEventsForDay(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting events for day: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get today's events.
     */
    suspend fun getTodayEvents(): List<CalendarEvent> {
        val (dayStart, dayEnd) = getDayBounds(System.currentTimeMillis())
        return try {
            calendarDao.getTodayEvents(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get events linked to a specific note.
     */
    fun getEventsForNote(noteId: String): Flow<List<CalendarEvent>> {
        return calendarDao.getEventsForNote(noteId)
    }

    /**
     * Get AI-visible upcoming events (for agent context).
     */
    suspend fun getAiVisibleUpcomingEvents(limit: Int = 10): List<CalendarEvent> {
        return try {
            calendarDao.getAiVisibleUpcomingEvents(System.currentTimeMillis(), limit)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting AI-visible upcoming events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Check if there are events on a specific day.
     */
    suspend fun hasEventsOnDay(dayMillis: Long): Boolean {
        val (dayStart, dayEnd) = getDayBounds(dayMillis)
        return try {
            calendarDao.hasEventsOnDay(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking events on day: ${e.message}", e)
            false
        }
    }

    /**
     * Get total event count.
     */
    suspend fun getEventCount(): Int {
        return try {
            calendarDao.getEventCount()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting event count: ${e.message}", e)
            0
        }
    }

    // ==================== Note Link Operations ====================

    /**
     * Clear note link when a note is deleted.
     * Call this from NoteOperationsManager when deleting a note.
     */
    suspend fun clearNoteLinkForNote(noteId: String) {
        try {
            calendarDao.clearNoteLinkForNote(noteId)
            Log.d(TAG, "Cleared note link for note: $noteId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing note link: ${e.message}", e)
        }
    }

    // ==================== Alarm/Reminder Operations ====================

    /**
     * Schedule a reminder alarm for an event using JarvisTimer.
     * Creates a one-time timer that fires at the reminder time.
     */
    private fun scheduleReminder(event: CalendarEvent, minutesBefore: Int) {
        alarmScheduler?.let { scheduler ->
            val reminderTime = event.startTime - (minutesBefore * 60 * 1000L)
            if (reminderTime > System.currentTimeMillis()) {
                // Create a JarvisTimer for the event reminder
                val timer = JarvisTimer(
                    id = "event_reminder_${event.id}",
                    name = "Reminder: ${event.title}",
                    triggerTime = reminderTime,
                    isAlarm = false // Use notification, not loud alarm
                )
                scheduler.scheduleTimer(timer)
                Log.d(TAG, "Scheduled reminder for event ${event.id} at $reminderTime")
            }
        }
    }

    /**
     * Cancel a reminder alarm for an event.
     */
    private fun cancelReminder(eventId: String) {
        alarmScheduler?.cancelTimer("event_reminder_$eventId")
    }

    // ==================== Utility Functions ====================

    /**
     * Get the start and end timestamps for a day in local timezone.
     */
    private fun getDayBounds(millis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = calendar.timeInMillis

        return Pair(dayStart, dayEnd)
    }

    /**
     * Get all events as a one-shot query (for backup).
     */
    suspend fun getAllEventsOnce(): List<CalendarEvent> {
        return try {
            calendarDao.getAllEventsOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Delete all events.
     * Use with caution - typically for testing or user-initiated data wipe.
     */
    suspend fun deleteAllEvents() {
        try {
            calendarDao.deleteAllEvents()
            Log.w(TAG, "Deleted all calendar events")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all events: ${e.message}", e)
        }
    }
}
