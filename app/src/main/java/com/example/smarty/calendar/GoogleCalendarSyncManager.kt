package com.example.smarty.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.CogniRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * Manages synchronization between device calendars and the app's local calendar database.
 * Uses Android's CalendarProvider for accessing Google Calendar and other calendar accounts.
 *
 * Features:
 * - Read events from all device calendars (Google, Exchange, etc.)
 * - Import selected events to local database
 * - Track sync status
 * - Handle privacy settings (imported events marked as non-private by default)
 */
class GoogleCalendarSyncManager(
    private val context: Context,
    private val repository: CogniRepository
) {
    companion object {
        private const val TAG = "CalendarSync"

        // Projection for calendar list query
        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE
        )

        // Projection for events query
        private val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.EVENT_COLOR
        )

        // Indices for calendar projection
        private const val CALENDAR_ID_INDEX = 0
        private const val CALENDAR_DISPLAY_NAME_INDEX = 1
        private const val CALENDAR_ACCOUNT_NAME_INDEX = 2
        private const val CALENDAR_ACCOUNT_TYPE_INDEX = 3
        private const val CALENDAR_COLOR_INDEX = 4
        private const val CALENDAR_VISIBLE_INDEX = 5

        // Indices for event projection
        private const val EVENT_ID_INDEX = 0
        private const val EVENT_TITLE_INDEX = 1
        private const val EVENT_DESCRIPTION_INDEX = 2
        private const val EVENT_DTSTART_INDEX = 3
        private const val EVENT_DTEND_INDEX = 4
        private const val EVENT_ALL_DAY_INDEX = 5
        private const val EVENT_LOCATION_INDEX = 6
        private const val EVENT_CALENDAR_ID_INDEX = 7
        private const val EVENT_COLOR_INDEX = 8
    }

    // Sync state
    sealed class SyncState {
        object Idle : SyncState()
        object Syncing : SyncState()
        data class Completed(val importedCount: Int, val totalCount: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Data class for device calendar info
     */
    data class DeviceCalendar(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val color: Int?,
        val isVisible: Boolean
    ) {
        val isGoogleCalendar: Boolean
            get() = accountType == "com.google"
    }

    /**
     * Check if calendar permission is granted
     */
    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get list of all calendars on the device
     */
    suspend fun getDeviceCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            Log.w(TAG, "Calendar permission not granted")
            return@withContext emptyList()
        }

        val calendars = mutableListOf<DeviceCalendar>()
        val uri: Uri = CalendarContract.Calendars.CONTENT_URI

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                CALENDAR_PROJECTION,
                null,
                null,
                null
            )

            cursor?.let {
                while (it.moveToNext()) {
                    val calendar = DeviceCalendar(
                        id = it.getLong(CALENDAR_ID_INDEX),
                        displayName = it.getString(CALENDAR_DISPLAY_NAME_INDEX) ?: "Unknown",
                        accountName = it.getString(CALENDAR_ACCOUNT_NAME_INDEX) ?: "",
                        accountType = it.getString(CALENDAR_ACCOUNT_TYPE_INDEX) ?: "",
                        color = if (!it.isNull(CALENDAR_COLOR_INDEX)) it.getInt(CALENDAR_COLOR_INDEX) else null,
                        isVisible = it.getInt(CALENDAR_VISIBLE_INDEX) == 1
                    )
                    calendars.add(calendar)
                }
            }

            Log.d(TAG, "Found ${calendars.size} calendars on device")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calendars: ${e.message}", e)
        } finally {
            cursor?.close()
        }

        calendars
    }

    /**
     * Sync events from a specific calendar
     * @param calendarId The device calendar ID to sync from
     * @param daysBack Number of days in the past to sync
     * @param daysForward Number of days in the future to sync
     * @return Number of events synced
     */
    suspend fun syncCalendar(
        calendarId: Long,
        daysBack: Int = 30,
        daysForward: Int = 90
    ): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            _syncState.value = SyncState.Error("Calendar permission required")
            return@withContext 0
        }

        _syncState.value = SyncState.Syncing

        try {
            // Calculate date range
            val calendar = Calendar.getInstance(TimeZone.getDefault())
            calendar.add(Calendar.DAY_OF_YEAR, -daysBack)
            val startMillis = calendar.timeInMillis

            calendar.timeInMillis = System.currentTimeMillis()
            calendar.add(Calendar.DAY_OF_YEAR, daysForward)
            val endMillis = calendar.timeInMillis

            // Query events
            val events = queryEvents(calendarId, startMillis, endMillis)
            var importedCount = 0

            for (event in events) {
                try {
                    repository.insertCalendarEvent(event)
                    importedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert event '${event.title}': ${e.message}")
                }
            }

            Log.i(TAG, "Synced $importedCount/${events.size} events from calendar $calendarId")
            _syncState.value = SyncState.Completed(importedCount, events.size)
            importedCount

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            _syncState.value = SyncState.Error("Sync failed: ${e.message}")
            0
        }
    }

    /**
     * Sync events from all visible Google calendars
     */
    suspend fun syncAllGoogleCalendars(
        daysBack: Int = 30,
        daysForward: Int = 90
    ): Int = withContext(Dispatchers.IO) {
        val calendars = getDeviceCalendars()
            .filter { it.isGoogleCalendar && it.isVisible }

        if (calendars.isEmpty()) {
            Log.w(TAG, "No visible Google calendars found")
            _syncState.value = SyncState.Completed(0, 0)
            return@withContext 0
        }

        _syncState.value = SyncState.Syncing
        var totalImported = 0

        for (calendar in calendars) {
            Log.d(TAG, "Syncing calendar: ${calendar.displayName} (${calendar.accountName})")
            totalImported += syncCalendar(calendar.id, daysBack, daysForward)
        }

        _syncState.value = SyncState.Completed(totalImported, totalImported)
        totalImported
    }

    /**
     * Query events from device calendar
     */
    private fun queryEvents(
        calendarId: Long,
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val uri = CalendarContract.Events.CONTENT_URI

        val selection = "(${CalendarContract.Events.CALENDAR_ID} = ?) AND " +
                "(${CalendarContract.Events.DTSTART} >= ?) AND " +
                "(${CalendarContract.Events.DTSTART} <= ?)"

        val selectionArgs = arrayOf(
            calendarId.toString(),
            startMillis.toString(),
            endMillis.toString()
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                EVENT_PROJECTION,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.let {
                while (it.moveToNext()) {
                    val eventId = it.getLong(EVENT_ID_INDEX)
                    val title = it.getString(EVENT_TITLE_INDEX) ?: "Untitled Event"
                    val description = it.getString(EVENT_DESCRIPTION_INDEX)
                    val dtStart = it.getLong(EVENT_DTSTART_INDEX)
                    val dtEnd = if (!it.isNull(EVENT_DTEND_INDEX)) {
                        it.getLong(EVENT_DTEND_INDEX)
                    } else {
                        // Default to 1 hour if no end time
                        dtStart + (60 * 60 * 1000)
                    }
                    val allDay = it.getInt(EVENT_ALL_DAY_INDEX) == 1
                    val location = it.getString(EVENT_LOCATION_INDEX)
                    val color = if (!it.isNull(EVENT_COLOR_INDEX)) it.getInt(EVENT_COLOR_INDEX) else null

                    // Use a composite ID to track imported events
                    val compositeId = "gcal_${calendarId}_${eventId}"

                    events.add(
                        CalendarEvent(
                            id = compositeId,
                            title = title,
                            description = description,
                            startTime = dtStart,
                            endTime = dtEnd,
                            isAllDay = allDay,
                            location = location,
                            color = color,
                            isEventPrivate = false, // Synced events are not private by default
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error querying events: ${e.message}", e)
        } finally {
            cursor?.close()
        }

        return events
    }

    /**
     * Get upcoming events from the device calendar (without syncing to local DB)
     * Useful for quick previews
     */
    suspend fun getUpcomingDeviceEvents(
        daysForward: Int = 7,
        limit: Int = 10
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            return@withContext emptyList()
        }

        val calendars = getDeviceCalendars().filter { it.isVisible }
        val allEvents = mutableListOf<CalendarEvent>()

        val startMillis = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, daysForward)
        val endMillis = calendar.timeInMillis

        for (cal in calendars) {
            allEvents.addAll(queryEvents(cal.id, startMillis, endMillis))
        }

        allEvents
            .sortedBy { it.startTime }
            .take(limit)
    }

    /**
     * Reset sync state
     */
    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }
}
