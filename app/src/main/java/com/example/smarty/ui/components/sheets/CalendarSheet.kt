package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.ui.components.AddEventDialog
import com.example.smarty.features.calendar.ui.CalendarScreen
import java.util.Calendar

/**
 * Calendar as a full-page overlay.
 * Respects system theme for a consistent experience.
 * Completely covers the main screen.
 * Includes AddEventDialog for creating new events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSheet(
    events: List<CalendarEvent>,
    activeTimers: List<com.example.smarty.core.domain.model.SmartyTimer> = emptyList(),
    sheetState: SheetState, // Keep for API compatibility, but not used
    onDismiss: () -> Unit,
    onAddEvent: () -> Unit, // Legacy callback - still used for actual event creation
    onEventClick: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    onCreateEvent: ((title: String, description: String?, startTime: Long, endTime: Long, isAllDay: Boolean) -> Unit)? = null
) {
    // State for showing add event dialog
    var showAddEventDialog by remember { mutableStateOf(false) }
    var selectedDateForNewEvent by remember { mutableStateOf<Calendar?>(null) }

    // Handle back button
    BackHandler(enabled = true) {
        onDismiss()
    }

    // Full-page overlay - respects system theme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        CalendarScreen(
            events = events,
            activeTimers = activeTimers,
            onBackClick = onDismiss,
            onAddEvent = { selectedDate ->
                // Show dialog with the selected date
                if (onCreateEvent != null) {
                    selectedDateForNewEvent = selectedDate
                    showAddEventDialog = true
                } else {
                    // Fallback to legacy behavior
                    onAddEvent()
                }
            },
            onEventClick = onEventClick,
            onDeleteEvent = onDeleteEvent
        )
    }

    // Add Event Dialog
    if (showAddEventDialog && onCreateEvent != null) {
        AddEventDialog(
            onDismiss = {
                showAddEventDialog = false
                selectedDateForNewEvent = null
            },
            onConfirm = { title, description, startTime, endTime, isAllDay ->
                onCreateEvent(title, description, startTime, endTime, isAllDay)
                showAddEventDialog = false
                selectedDateForNewEvent = null
            },
            initialDate = selectedDateForNewEvent ?: Calendar.getInstance()
        )
    }
}
