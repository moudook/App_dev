package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PREMIUM DARK EDIT EVENT SHEET
 * 
 * Design: Always-dark theme matching Calendar aesthetic
 * - Deep dark background for immersive experience
 * - Electric Blue accent (consistent with Jarvis design)
 * - Pre-populated with existing event data
 * - Delete option with confirmation
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// Design System Colors (Matching Calendar & AddEventSheet)
private val SheetDarkBg = Color(0xFF0D0D12)
private val SheetSurfaceDark = Color(0xFF1A1A24)
private val SheetAccent = Color(0xFF2979FF)
private val SheetMutedText = Color(0xFF6B6B80)
private val SheetWhite = Color(0xFFF5F5F7)
private val SheetInputBg = Color(0xFF1E1E28)
private val SheetBorder = Color(0xFF2A2A35)
private val SheetError = Color(0xFFFF453A)



/**
 * Reminder options in minutes
 */
private val reminderOptions = listOf(
    null to "None",
    5 to "5 minutes before",
    10 to "10 minutes before",
    15 to "15 minutes before",
    30 to "30 minutes before",
    60 to "1 hour before",
    1440 to "1 day before"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventSheet(
    event: CalendarEvent,
    onDismiss: () -> Unit,
    onSave: (CalendarEvent) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // Parse existing event data
    val existingCalendar = remember(event) {
        Calendar.getInstance().apply { timeInMillis = event.startTime }
    }
    val existingEndCalendar = remember(event) {
        Calendar.getInstance().apply { timeInMillis = event.endTime }
    }

    // Form state - pre-populated from event
    var title by remember { mutableStateOf(event.title) }
    var description by remember { mutableStateOf(event.description ?: "") }
    var isAllDay by remember { mutableStateOf(event.isAllDay) }
    var location by remember { mutableStateOf(event.location ?: "") }

    var selectedReminder by remember { mutableStateOf(event.reminderMinutes) }
    var isPrivate by remember { mutableStateOf(event.isEventPrivate) }

    // Date/Time state - pre-populated from event
    var selectedDate by remember { mutableStateOf(existingCalendar.clone() as Calendar) }
    var startHour by remember { mutableIntStateOf(existingCalendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableIntStateOf(existingCalendar.get(Calendar.MINUTE)) }
    var endHour by remember { mutableIntStateOf(existingEndCalendar.get(Calendar.HOUR_OF_DAY)) }
    var endMinute by remember { mutableIntStateOf(existingEndCalendar.get(Calendar.MINUTE)) }

    // UI state
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showReminderMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Formatters
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // Calculate times for display
    val startTimeDisplay = remember(startHour, startMinute) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, startHour)
        cal.set(Calendar.MINUTE, startMinute)
        timeFormat.format(cal.time)
    }

    val endTimeDisplay = remember(endHour, endMinute) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, endHour)
        cal.set(Calendar.MINUTE, endMinute)
        timeFormat.format(cal.time)
    }

    // Date picker state
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.timeInMillis
    )

    // Time picker states
    val startTimePickerState = rememberTimePickerState(
        initialHour = startHour,
        initialMinute = startMinute
    )

    val endTimePickerState = rememberTimePickerState(
        initialHour = endHour,
        initialMinute = endMinute
    )

    // Check if any changes were made
    val hasChanges = remember(
        title, description, isAllDay, location,
        selectedReminder, isPrivate, selectedDate, startHour,
        startMinute, endHour, endMinute
    ) {
        title != event.title ||
        description != (event.description ?: "") ||
        isAllDay != event.isAllDay ||
        location != (event.location ?: "") ||
        selectedReminder != event.reminderMinutes ||
        isPrivate != event.isEventPrivate ||
        selectedDate.get(Calendar.DAY_OF_YEAR) != existingCalendar.get(Calendar.DAY_OF_YEAR) ||
        selectedDate.get(Calendar.YEAR) != existingCalendar.get(Calendar.YEAR) ||
        startHour != existingCalendar.get(Calendar.HOUR_OF_DAY) ||
        startMinute != existingCalendar.get(Calendar.MINUTE) ||
        endHour != existingEndCalendar.get(Calendar.HOUR_OF_DAY) ||
        endMinute != existingEndCalendar.get(Calendar.MINUTE)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN SHEET (Dark Theme)
    // ═══════════════════════════════════════════════════════════════════
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = SheetDarkBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(SheetMutedText.copy(alpha = 0.4f))
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // HEADER
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetMutedText)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Medium)
                }

                Text(
                    text = "Edit Event",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SheetWhite
                )

                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val startCal = selectedDate.clone() as Calendar
                            startCal.set(Calendar.HOUR_OF_DAY, if (isAllDay) 0 else startHour)
                            startCal.set(Calendar.MINUTE, if (isAllDay) 0 else startMinute)
                            startCal.set(Calendar.SECOND, 0)
                            startCal.set(Calendar.MILLISECOND, 0)

                            val endCal = selectedDate.clone() as Calendar
                            endCal.set(Calendar.HOUR_OF_DAY, if (isAllDay) 23 else endHour)
                            endCal.set(Calendar.MINUTE, if (isAllDay) 59 else endMinute)
                            endCal.set(Calendar.SECOND, if (isAllDay) 59 else 0)
                            endCal.set(Calendar.MILLISECOND, 0)

                            val updatedEvent = event.copy(
                                title = title,
                                description = description.ifBlank { null },
                                startTime = startCal.timeInMillis,
                                endTime = endCal.timeInMillis,
                                isAllDay = isAllDay,
                                location = location.ifBlank { null },
                                color = event.color, // Preserve existing color, option removed
                                reminderMinutes = selectedReminder,
                                isEventPrivate = isPrivate,
                                updatedAt = System.currentTimeMillis()
                            )
                            onSave(updatedEvent)
                        }
                    },
                    enabled = title.isNotBlank() && hasChanges,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SheetAccent,
                        disabledContentColor = SheetMutedText.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SheetBorder)
            )

            // ═══════════════════════════════════════════════════════════════════
            // SCROLLABLE FORM
            // ═══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title field
                EditDarkTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Event title",
                    singleLine = true
                )

                // Description field
                EditDarkTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Description",
                    minLines = 2,
                    maxLines = 4
                )

                // All Day Toggle
                EditDarkFormRow(
                    icon = Icons.Default.WbSunny, // Creative: Sun
                    title = "All day",
                    trailing = {
                        Switch(
                            checked = isAllDay,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isAllDay = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SheetWhite,
                                checkedTrackColor = SheetAccent,
                                uncheckedThumbColor = SheetMutedText,
                                uncheckedTrackColor = SheetSurfaceDark
                            )
                        )
                    }
                )

                // Date Selection
                EditDarkFormRow(
                    icon = Icons.Default.DateRange, // Creative: Range
                    title = "Date",
                    value = dateFormat.format(selectedDate.time),
                    onClick = { showDatePicker = true }
                )

                // Time Selection (if not all day)
                AnimatedVisibility(
                    visible = !isAllDay,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditDarkFormRow(
                            icon = Icons.Default.HourglassTop, // Creative: Time/Start
                            title = "Start time",
                            value = startTimeDisplay,
                            onClick = { showStartTimePicker = true }
                        )

                        EditDarkFormRow(
                            icon = Icons.Default.HourglassBottom, // Creative: Time/End
                            title = "End time",
                            value = endTimeDisplay,
                            onClick = { showEndTimePicker = true }
                        )
                    }
                }

                // Location field
                EditDarkFormRow(
                    icon = Icons.Default.Explore, // Creative: Compass
                    title = if (location.isBlank()) "Add location" else location,
                    onClick = { /* Could expand to full input */ }
                )

                // Reminder Selection
                EditDarkFormRow(
                    icon = Icons.Default.TipsAndUpdates, // Creative: Insight/Reminder
                    title = "Reminder",
                    value = reminderOptions.find { it.first == selectedReminder }?.second ?: "None",
                    onClick = { showReminderMenu = true }
                )

                // Privacy Toggle
                EditDarkFormRow(
                    icon = Icons.Default.Lock, // Creative: Lock
                    title = "Private event",
                    subtitle = "Hidden from AI assistant",
                    trailing = {
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isPrivate = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SheetWhite,
                                checkedTrackColor = SheetAccent,
                                uncheckedThumbColor = SheetMutedText,
                                uncheckedTrackColor = SheetSurfaceDark
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Delete Button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDeleteConfirmation = true
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = SheetError.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Whatshot, // Creative: Fire
                            contentDescription = null,
                            tint = SheetError,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete Event",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = SheetError
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIALOGS (Dark themed)
    // ═══════════════════════════════════════════════════════════════════

    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Calendar.getInstance().apply { timeInMillis = millis }
                        }
                        showDatePicker = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetAccent)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetMutedText)
                ) {
                    Text("Cancel")
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = SheetSurfaceDark
            )
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = SheetSurfaceDark,
                    titleContentColor = SheetWhite,
                    headlineContentColor = SheetWhite,
                    weekdayContentColor = SheetMutedText,
                    dayContentColor = SheetWhite,
                    selectedDayContainerColor = SheetAccent,
                    selectedDayContentColor = SheetWhite,
                    todayContentColor = SheetAccent,
                    todayDateBorderColor = SheetAccent
                )
            )
        }
    }

    // Start Time Picker Dialog
    if (showStartTimePicker) {
        EditDarkTimePickerDialog(
            onDismiss = { showStartTimePicker = false },
            onConfirm = {
                startHour = startTimePickerState.hour
                startMinute = startTimePickerState.minute
                if (startHour >= endHour && startMinute >= endMinute) {
                    endHour = (startHour + 1) % 24
                }
                showStartTimePicker = false
            }
        ) {
            TimePicker(
                state = startTimePickerState,
                colors = TimePickerDefaults.colors(
                    containerColor = SheetSurfaceDark,
                    clockDialColor = SheetInputBg,
                    clockDialSelectedContentColor = SheetWhite,
                    clockDialUnselectedContentColor = SheetMutedText,
                    selectorColor = SheetAccent,
                    periodSelectorSelectedContainerColor = SheetAccent,
                    periodSelectorSelectedContentColor = SheetWhite,
                    periodSelectorUnselectedContainerColor = SheetInputBg,
                    periodSelectorUnselectedContentColor = SheetMutedText,
                    timeSelectorSelectedContainerColor = SheetAccent,
                    timeSelectorSelectedContentColor = SheetWhite,
                    timeSelectorUnselectedContainerColor = SheetInputBg,
                    timeSelectorUnselectedContentColor = SheetWhite
                )
            )
        }
    }

    // End Time Picker Dialog
    if (showEndTimePicker) {
        EditDarkTimePickerDialog(
            onDismiss = { showEndTimePicker = false },
            onConfirm = {
                endHour = endTimePickerState.hour
                endMinute = endTimePickerState.minute
                showEndTimePicker = false
            }
        ) {
            TimePicker(
                state = endTimePickerState,
                colors = TimePickerDefaults.colors(
                    containerColor = SheetSurfaceDark,
                    clockDialColor = SheetInputBg,
                    clockDialSelectedContentColor = SheetWhite,
                    clockDialUnselectedContentColor = SheetMutedText,
                    selectorColor = SheetAccent,
                    periodSelectorSelectedContainerColor = SheetAccent,
                    periodSelectorSelectedContentColor = SheetWhite,
                    periodSelectorUnselectedContainerColor = SheetInputBg,
                    periodSelectorUnselectedContentColor = SheetMutedText,
                    timeSelectorSelectedContainerColor = SheetAccent,
                    timeSelectorSelectedContentColor = SheetWhite,
                    timeSelectorUnselectedContainerColor = SheetInputBg,
                    timeSelectorUnselectedContentColor = SheetWhite
                )
            )
        }
    }

    // Reminder Menu
    if (showReminderMenu) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "Reminder",
            onDismiss = { showReminderMenu = false },
            onConfirm = {},
            customContent = {
                Column {
                    reminderOptions.forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedReminder = minutes
                                    showReminderMenu = false
                                }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = SheetWhite)
                            if (selectedReminder == minutes) {
                                Icon(
                                    imageVector = Icons.Default.Verified, // Creative: Verified
                                    contentDescription = "Selected",
                                    tint = SheetAccent
                                )
                            }
                        }
                    }
                }
            },
            confirmText = "",
            confirmEnabled = false // Hide/Disable confirm as selection closes it
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "Delete Event?",
            text = "Are you sure you want to delete \"${event.title}\"? This action cannot be undone.",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showDeleteConfirmation = false
                onDelete()
            },
            onDismiss = { showDeleteConfirmation = false },
            confirmText = "Delete",
            dismissText = "Cancel",
            isDestructive = true
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DARK THEMED FORM COMPONENTS FOR EDIT SHEET
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EditDarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = SheetMutedText) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SheetWhite,
            unfocusedTextColor = SheetWhite,
            focusedContainerColor = SheetInputBg,
            unfocusedContainerColor = SheetInputBg,
            focusedBorderColor = SheetAccent,
            unfocusedBorderColor = SheetBorder,
            cursorColor = SheetAccent
        )
    )
}

@Composable
private fun EditDarkFormRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = SheetSurfaceDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SheetMutedText,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheetWhite
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = SheetMutedText
                    )
                }
            }

            if (trailing != null) {
                trailing()
            } else if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheetAccent,
                    fontWeight = FontWeight.Medium
                )
            }

            if (onClick != null && trailing == null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = SheetMutedText.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EditDarkFormSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SheetMutedText,
            fontWeight = FontWeight.Medium
        )
        content()
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDarkTimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    com.example.smarty.ui.components.common.JarvisDialog(
        title = "Select time",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        customContent = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        },
        confirmText = "OK",
        dismissText = "Cancel"
    )
}
