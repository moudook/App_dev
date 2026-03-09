package com.example.smarty.features.calendar.domain

import android.util.Log
import com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.service.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Feature Manager for Calendar functionality.
 * Coordinates between local database, Google Calendar sync, and UI state.
 */
class CalendarFeatureManager(
    private val repository: SmartyRepository,
    private val googleCalendarSyncManager: GoogleCalendarSyncManager,
    private val securePreferences: SecurePreferences,
    private val alarmScheduler: AlarmScheduler,
    private val scope: CoroutineScope
) {
    private val TAG = "CalendarFeatureManager"

    // Underlying low-level manager for basic DAO/Alarm operations
    private val calendarManager = CalendarManager(repository, alarmScheduler, scope)

    // Observable State
    val calendarEvents = calendarManager.calendarEvents
    val activeTimers = calendarManager.activeTimers

    private val _deviceCalendars = MutableStateFlow<List<GoogleCalendarSyncManager.DeviceCalendar>>(emptyList())
    val deviceCalendars: StateFlow<List<GoogleCalendarSyncManager.DeviceCalendar>> = _deviceCalendars.asStateFlow()

    private val _isCalendarSyncEnabled = MutableStateFlow(securePreferences.isSyncToGoogleCalendarEnabled())
    val isCalendarSyncEnabled: StateFlow<Boolean> = _isCalendarSyncEnabled.asStateFlow()

    private val _targetCalendarId = MutableStateFlow(securePreferences.getTargetGoogleCalendarId())
    val targetCalendarId: StateFlow<Long> = _targetCalendarId.asStateFlow()

    /**
     * Load available calendars from the device.
     */
    fun loadDeviceCalendars() {
        scope.launch {
            try {
                val userEmail = securePreferences.getGoogleAccountEmail()
                _deviceCalendars.value = googleCalendarSyncManager.getDeviceCalendars(userEmail)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device calendars", e)
            }
        }
    }

    /**
     * Enable or disable Google Calendar sync.
     */
    fun setCalendarSyncEnabled(enabled: Boolean) {
        securePreferences.setSyncToGoogleCalendarEnabled(enabled)
        _isCalendarSyncEnabled.value = enabled
        if (enabled && _deviceCalendars.value.isEmpty()) {
            loadDeviceCalendars()
        }
    }

    /**
     * Set the target Google Calendar ID for sync.
     */
    fun setTargetCalendarId(id: Long) {
        securePreferences.setTargetGoogleCalendarId(id)
        _targetCalendarId.value = id
    }

    /**
     * Add a new calendar event with optional Google Calendar sync.
     * 
     * OPTIMIZED: Save to local database FIRST (instant), then sync to Google Calendar in background.
     * This eliminates UI latency during event creation.
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
        isPrivate: Boolean = false
    ) {
        scope.launch {
            // FIRST: Save to local database immediately (FAST - <10ms)
            val localEvent = calendarManager.addCalendarEventAndReturn(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                isAllDay = isAllDay,
                location = location,
                color = color,
                reminderMinutes = reminderMinutes,
                isPrivate = isPrivate,
                googleEventId = null // Will be updated after Google sync
            )
            
            // THEN: Sync to Google Calendar in background (SLOW - 500-2000ms)
            // This happens AFTER local save, so UI is not blocked
            if (securePreferences.isSyncToGoogleCalendarEnabled()) {
                val targetCalendarId = securePreferences.getTargetGoogleCalendarId()
                if (targetCalendarId != -1L) {
                    scope.launch {
                        try {
                            val tempEvent = CalendarEvent(
                                title = title,
                                description = description,
                                startTime = startTime,
                                endTime = endTime,
                                isAllDay = isAllDay,
                                location = location,
                                color = color,
                                reminderMinutes = reminderMinutes,
                                isEventPrivate = isPrivate
                            )
                            val googleEventId = googleCalendarSyncManager.exportEventToDeviceCalendar(tempEvent, targetCalendarId)
                            
                            // Update local event with Google event ID (if sync was successful)
                            if (googleEventId != null) {
                                val updatedEvent = localEvent.copy(googleEventId = googleEventId)
                                calendarManager.updateCalendarEvent(updatedEvent)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Background Google Calendar sync failed: ${e.message}")
                            // Event is already saved locally, so this is non-fatal
                        }
                    }
                }
            }
        }
    }

    /**
     * Update an existing calendar event and sync changes to Google Calendar if enabled.
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        scope.launch {
            // Handle Google Calendar Sync Update
            if (securePreferences.isSyncToGoogleCalendarEnabled() && event.googleEventId != null) {
                googleCalendarSyncManager.updateEventOnDeviceCalendar(event)
            }
            calendarManager.updateCalendarEvent(event)
        }
    }

    /**
     * Delete a calendar event and its Google Calendar counterpart if it exists.
     */
    fun deleteCalendarEvent(eventId: String) {
        scope.launch {
            // Handle Google Calendar Sync Deletion
            val event = calendarManager.getEventById(eventId)
            event?.googleEventId?.let { googleId ->
                googleCalendarSyncManager.deleteEventFromDeviceCalendar(googleId)
            }
            calendarManager.deleteCalendarEvent(eventId)
        }
    }

    /**
     * Get events for a specific day.
     */
    suspend fun getEventsForDay(dayMillis: Long): List<CalendarEvent> =
        calendarManager.getEventsForDay(dayMillis)

    /**
     * Get today's events.
     */
    suspend fun getTodayEvents(): List<CalendarEvent> =
        calendarManager.getTodayEvents()

    /**
     * Get AI-visible upcoming events.
     */
    suspend fun getAiVisibleUpcomingEvents(limit: Int = 10): List<CalendarEvent> =
        calendarManager.getAiVisibleUpcomingEvents(limit)

    /**
     * Parse natural language date/time strings.
     */
    fun parseDateTime(input: String): Long? = calendarManager.parseDateTime(input)

    /**
     * Set a timer or alarm.
     */
    fun setTimer(name: String, triggerTime: Long, isAlarm: Boolean) {
        calendarManager.setTimer(name, triggerTime, isAlarm)
    }

    /**
     * Cancel a timer.
     */
    fun cancelTimer(timerId: String) {
        calendarManager.cancelTimer(timerId)
    }

    /**
     * Search for events by query text.
     */
    suspend fun searchEvents(query: String): List<CalendarEvent> =
        calendarManager.searchEvents(query)

    /**
     * Get basic calendar manager for direct access if needed.
     */
    fun getCalendarManager(): CalendarManager = calendarManager
}

