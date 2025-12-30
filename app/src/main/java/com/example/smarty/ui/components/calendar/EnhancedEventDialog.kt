package com.example.smarty.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.CalendarEvent
import java.text.SimpleDateFormat
import java.util.*

// Color presets for events
val eventColors = listOf(
    Color(0xFF4285F4), // Blue
    Color(0xFF0F9D58), // Green
    Color(0xFFDB4437), // Red
    Color(0xFFF4B400), // Yellow
    Color(0xFF9C27B0), // Purple
    Color(0xFF00BCD4), // Cyan
)

// Reminder options (minutes, display text)
val reminderOptions = listOf(
    null to "None",
    5 to "5 minutes before",
    15 to "15 minutes before",
    30 to "30 minutes before",
    60 to "1 hour before",
    1440 to "1 day before"
)

// Recurrence options (RRULE, display text)
val recurrenceOptions = listOf(
    "" to "Does not repeat",
    "FREQ=DAILY" to "Daily",
    "FREQ=WEEKLY" to "Weekly",
    "FREQ=MONTHLY" to "Monthly",
    "FREQ=YEARLY" to "Yearly"
)

/**
 * Enhanced dialog for creating and editing calendar events.
 * Supports all event fields including location, color, reminders, and recurrence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedEventDialog(
    onDismiss: () -> Unit,
    onSave: (CalendarEvent) -> Unit,
    existingEvent: CalendarEvent? = null,  // For editing
    modifier: Modifier = Modifier
) {
    // State for all fields
    var title by remember { mutableStateOf(existingEvent?.title ?: "") }
    var description by remember { mutableStateOf(existingEvent?.description ?: "") }
    var location by remember { mutableStateOf(existingEvent?.location ?: "") }
    var startTime by remember { mutableStateOf(existingEvent?.startTime ?: System.currentTimeMillis()) }
    var endTime by remember { mutableStateOf(existingEvent?.endTime ?: (startTime + 3600000)) }
    var isAllDay by remember { mutableStateOf(existingEvent?.isAllDay ?: false) }
    var selectedColor by remember { mutableStateOf(existingEvent?.color?.let { Color(it) }) }
    var reminderMinutes by remember { mutableStateOf(existingEvent?.reminderMinutes) }
    var isRecurring by remember { mutableStateOf(existingEvent?.isRecurring ?: false) }
    var recurrenceRule by remember { mutableStateOf(existingEvent?.recurrenceRule ?: "") }

    // Dropdown expanded states
    var reminderExpanded by remember { mutableStateOf(false) }
    var recurrenceExpanded by remember { mutableStateOf(false) }

    // Date/time formatting
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Calendar instances for date/time manipulation
    val startCalendar = remember(startTime) {
        Calendar.getInstance().apply { timeInMillis = startTime }
    }
    val endCalendar = remember(endTime) {
        Calendar.getInstance().apply { timeInMillis = endTime }
    }

    // Time state for pickers
    var startHour by remember { mutableIntStateOf(startCalendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableIntStateOf(startCalendar.get(Calendar.MINUTE)) }
    var endHour by remember { mutableIntStateOf(endCalendar.get(Calendar.HOUR_OF_DAY)) }
    var endMinute by remember { mutableIntStateOf(endCalendar.get(Calendar.MINUTE)) }

    // Update timestamps when time changes
    LaunchedEffect(startHour, startMinute) {
        val cal = Calendar.getInstance().apply { timeInMillis = startTime }
        cal.set(Calendar.HOUR_OF_DAY, startHour)
        cal.set(Calendar.MINUTE, startMinute)
        startTime = cal.timeInMillis
    }

    LaunchedEffect(endHour, endMinute) {
        val cal = Calendar.getInstance().apply { timeInMillis = endTime }
        cal.set(Calendar.HOUR_OF_DAY, endHour)
        cal.set(Calendar.MINUTE, endMinute)
        endTime = cal.timeInMillis
    }

    val isEditing = existingEvent != null
    val dialogTitle = if (isEditing) "Edit Event" else "New Event"
    val confirmText = if (isEditing) "Save" else "Add"

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(dialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Title field (required)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Event title *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = title.isBlank()
                )

                // 2. Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // 3. Location field with icon
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location"
                        )
                    }
                )

                // 4. Date display
                Text(
                    text = "Date: ${dateFormat.format(Date(startTime))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 5. All-day toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("All day event")
                    Switch(
                        checked = isAllDay,
                        onCheckedChange = { isAllDay = it }
                    )
                }

                // Time pickers (only if not all day)
                if (!isAllDay) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Start time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = startHour.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) startHour = h
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Text(":", modifier = Modifier.padding(top = 16.dp))
                                OutlinedTextField(
                                    value = startMinute.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { m ->
                                            if (m in 0..59) startMinute = m
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }

                        // End time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "End",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = endHour.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) endHour = h
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Text(":", modifier = Modifier.padding(top = 16.dp))
                                OutlinedTextField(
                                    value = endMinute.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { m ->
                                            if (m in 0..59) endMinute = m
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                // 6. Color picker
                Column {
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        eventColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selectedColor == color) {
                                            Modifier.border(
                                                width = 3.dp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                shape = CircleShape
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        selectedColor = if (selectedColor == color) null else color
                                    }
                            )
                        }
                    }
                }

                // 7. Reminder dropdown
                Column {
                    Text(
                        text = "Reminder",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = reminderExpanded,
                        onExpandedChange = { reminderExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = reminderOptions.find { it.first == reminderMinutes }?.second ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reminderExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = reminderExpanded,
                            onDismissRequest = { reminderExpanded = false }
                        ) {
                            reminderOptions.forEach { (minutes, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        reminderMinutes = minutes
                                        reminderExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 8. Recurring toggle with picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recurring event")
                    Switch(
                        checked = isRecurring,
                        onCheckedChange = {
                            isRecurring = it
                            if (!it) recurrenceRule = ""
                        }
                    )
                }

                if (isRecurring) {
                    ExposedDropdownMenuBox(
                        expanded = recurrenceExpanded,
                        onExpandedChange = { recurrenceExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = recurrenceOptions.find { it.first == recurrenceRule }?.second ?: "Does not repeat",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = recurrenceExpanded,
                            onDismissRequest = { recurrenceExpanded = false }
                        ) {
                            recurrenceOptions.drop(1).forEach { (rule, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        recurrenceRule = rule
                                        recurrenceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        val finalStartTime = if (isAllDay) {
                            Calendar.getInstance().apply {
                                timeInMillis = startTime
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        } else {
                            startTime
                        }

                        val finalEndTime = if (isAllDay) {
                            Calendar.getInstance().apply {
                                timeInMillis = startTime
                                set(Calendar.HOUR_OF_DAY, 23)
                                set(Calendar.MINUTE, 59)
                                set(Calendar.SECOND, 59)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        } else {
                            endTime
                        }

                        val event = CalendarEvent(
                            id = existingEvent?.id ?: java.util.UUID.randomUUID().toString(),
                            title = title.trim(),
                            description = description.ifBlank { null },
                            location = location.ifBlank { null },
                            startTime = finalStartTime,
                            endTime = finalEndTime,
                            isAllDay = isAllDay,
                            color = selectedColor?.toArgb(),
                            reminderMinutes = reminderMinutes,
                            isRecurring = isRecurring,
                            recurrenceRule = recurrenceRule.ifBlank { null },
                            linkedNoteId = existingEvent?.linkedNoteId,
                            isEventPrivate = existingEvent?.isEventPrivate ?: false,
                            createdAt = existingEvent?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        onSave(event)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
