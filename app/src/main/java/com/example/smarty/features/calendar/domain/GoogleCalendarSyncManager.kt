package com.example.smarty.features.calendar.domain

import com.example.smarty.R
import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.data.repository.SmartyRepository
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
    private val repository: SmartyRepository
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
        data class Error(val message: String, val isPermissionError: Boolean = false) : SyncState()
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
     * Check if calendar write permission is granted
     */
    fun hasWriteCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Export a local event to the device's Google Calendar
     * @param event The local CalendarEvent to export
     * @param targetCalendarId The ID of the device calendar to export to
     * @return The new Google Event ID, or null if failed
     */
    suspend fun exportEventToDeviceCalendar(event: CalendarEvent, targetCalendarId: Long): String? = withContext(Dispatchers.IO) {
        if (!hasWriteCalendarPermission()) {
            Log.w(TAG, "Write calendar permission not granted")
            return@withContext null
        }

        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, event.startTime)
                put(CalendarContract.Events.DTEND, event.endTime)
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.CALENDAR_ID, targetCalendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
                if (event.location != null) {
                    put(CalendarContract.Events.EVENT_LOCATION, event.location)
                }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val newId = uri?.lastPathSegment
            Log.d(TAG, "Exported event to Google Calendar. New ID: $newId")
            newId
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException exporting event: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting event to Google Calendar: ${e.message}", e)
            null
        }
    }

    /**
     * Update an existing event in the device calendar
     * @param event The local CalendarEvent with the updated data
     * @return true if successful, false otherwise
     */
    suspend fun updateEventOnDeviceCalendar(event: CalendarEvent): Boolean = withContext(Dispatchers.IO) {
        val googleId = event.googleEventId ?: return@withContext false
        if (!hasWriteCalendarPermission()) {
            Log.w(TAG, "Write calendar permission not granted")
            return@withContext false
        }

        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, event.startTime)
                put(CalendarContract.Events.DTEND, event.endTime)
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.ALL_DAY, if (event.isAllDay) 1 else 0)
                if (event.location != null) {
                    put(CalendarContract.Events.EVENT_LOCATION, event.location)
                }
            }

            val updateUri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, googleId)
            val rows = context.contentResolver.update(updateUri, values, null, null)
            Log.d(TAG, "Updated event $googleId in Google Calendar. Rows affected: $rows")
            rows > 0
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException updating event: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error updating event in Google Calendar: ${e.message}", e)
            false
        }
    }

    /**
     * Delete an event from the device calendar
     * @param googleEventId The Google Event ID to delete
     */
    suspend fun deleteEventFromDeviceCalendar(googleEventId: String) = withContext(Dispatchers.IO) {
        if (!hasWriteCalendarPermission()) {
            Log.w(TAG, "Write calendar permission not granted")
            return@withContext
        }

        try {
            val deleteUri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, googleEventId)
            val rows = context.contentResolver.delete(deleteUri, null, null)
            Log.d(TAG, "Deleted event $googleEventId from Google Calendar. Rows affected: $rows")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException deleting event: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting event from Google Calendar: ${e.message}", e)
        }
    }

    /**
     * Get list of all calendars on the device.
     * Sorts the list so calendars matching the userEmail come first.
     */
    suspend fun getDeviceCalendars(userEmail: String? = null): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) {
            Log.w(TAG, "Calendar permission not granted")
            return@withContext emptyList()
        }

        val calendars = mutableListOf<DeviceCalendar>()
        val uri: Uri = CalendarContract.Calendars.CONTENT_URI

        try {
            // Use .use{} to ensure cursor is always closed (fixes memory leak)
            context.contentResolver.query(
                uri,
                CALENDAR_PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val calendar = DeviceCalendar(
                        id = cursor.getLong(CALENDAR_ID_INDEX),
                        displayName = cursor.getString(CALENDAR_DISPLAY_NAME_INDEX) ?: "Unknown",
                        accountName = cursor.getString(CALENDAR_ACCOUNT_NAME_INDEX) ?: "",
                        accountType = cursor.getString(CALENDAR_ACCOUNT_TYPE_INDEX) ?: "",
                        color = if (!cursor.isNull(CALENDAR_COLOR_INDEX)) cursor.getInt(CALENDAR_COLOR_INDEX) else null,
                        isVisible = cursor.getInt(CALENDAR_VISIBLE_INDEX) == 1
                    )
                    calendars.add(calendar)
                }
            }

            Log.d(TAG, "Found ${calendars.size} calendars on device")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reading calendars: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calendars: ${e.message}", e)
        }

        // Sort: userEmail matches first, then Google accounts, then others
        calendars.sortedWith(compareByDescending<DeviceCalendar> {
            userEmail != null && it.accountName.equals(userEmail, ignoreCase = true)
        }.thenByDescending { it.isGoogleCalendar }
         .thenBy { it.displayName })
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

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during sync: ${e.message}")
            _syncState.value = SyncState.Error("permission_revoked", isPermissionError = true)
            0
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            _syncState.value = SyncState.Error("sync_failed")
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

        try {
            // Use .use{} to ensure cursor is always closed (fixes memory leak)
            context.contentResolver.query(
                uri,
                EVENT_PROJECTION,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(EVENT_ID_INDEX)
                    val title = cursor.getString(EVENT_TITLE_INDEX) ?: context.getString(R.string.untitled_event)
                    val description = cursor.getString(EVENT_DESCRIPTION_INDEX)
                    val dtStart = cursor.getLong(EVENT_DTSTART_INDEX)
                    val dtEnd = if (!cursor.isNull(EVENT_DTEND_INDEX)) {
                        cursor.getLong(EVENT_DTEND_INDEX)
                    } else {
                        // Default to 1 hour if no end time
                        dtStart + (60 * 60 * 1000)
                    }
                    val allDay = cursor.getInt(EVENT_ALL_DAY_INDEX) == 1
                    val location = cursor.getString(EVENT_LOCATION_INDEX)
                    val color = if (!cursor.isNull(EVENT_COLOR_INDEX)) cursor.getInt(EVENT_COLOR_INDEX) else null

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
                            googleEventId = eventId.toString(),
                            isEventPrivate = false, // Synced events are not private by default
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying events: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying events: ${e.message}", e)
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
