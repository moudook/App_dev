package com.example.smarty.features.calendar.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.di.ServiceLocator

class CalendarViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val calendarFeatureManager = ServiceLocator.provideCalendarFeatureManager(application)

    // State
    val calendarEvents = calendarFeatureManager.calendarEvents
    val activeTimers = calendarFeatureManager.activeTimers
    val deviceCalendars = calendarFeatureManager.deviceCalendars
    val isCalendarSyncEnabled = calendarFeatureManager.isCalendarSyncEnabled
    val targetCalendarId = calendarFeatureManager.targetCalendarId

    fun loadDeviceCalendars() {
        calendarFeatureManager.loadDeviceCalendars()
    }

    fun setCalendarSyncEnabled(enabled: Boolean) {
        calendarFeatureManager.setCalendarSyncEnabled(enabled)
    }

    fun setTargetCalendarId(id: Long) {
        calendarFeatureManager.setTargetCalendarId(id)
    }

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
    ) {
        calendarFeatureManager.addCalendarEvent(
            title,
            description,
            startTime,
            endTime,
            isAllDay,
            location,
            color,
            reminderMinutes,
            isPrivate,
        )
    }

    fun updateCalendarEvent(event: CalendarEvent) {
        calendarFeatureManager.updateCalendarEvent(event)
    }

    fun deleteCalendarEvent(eventId: String) {
        calendarFeatureManager.deleteCalendarEvent(eventId)
    }

    fun cancelTimer(timerId: String) {
        calendarFeatureManager.cancelTimer(timerId)
    }
}
