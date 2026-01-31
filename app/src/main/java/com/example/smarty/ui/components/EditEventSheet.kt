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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PREMIUM EDIT EVENT SHEET
 *
 * Design: Calm Aesthetic, theme-aware
 * - Integrated with Material 3 dynamic colors
 * - Electric Blue accent (consistent with Smarty design)
 * - Pre-populated with existing event data
 * - Delete option with confirmation
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// Reminder options (minutes, display text)
private val reminderOptions = listOf(
    null to "none",
    5 to "5_minutes_before",
    10 to "10_minutes_before",
    15 to "15_minutes_before",
    30 to "30_minutes_before",
    60 to "1_hour_before",
    1440 to "1_day_before"
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
    val accentColor = LocalAccentColor.current

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
        color = MaterialTheme.colorScheme.background
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
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.Medium)
                }

                Text(
                    text = stringResource(R.string.edit_event),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
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
                        contentColor = accentColor,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
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
                    placeholder = stringResource(R.string.event_title),
                    singleLine = true
                )

                // Description field
                EditDarkTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.description),
                    minLines = 2,
                    maxLines = 4
                )

                // All Day Toggle
                EditDarkFormRow(
                    icon = Icons.Default.CalendarToday,
                    title = stringResource(R.string.all_day),
                    trailing = {
                        Switch(
                            checked = isAllDay,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isAllDay = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = accentColor,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                )

                // Date Selection
                EditDarkFormRow(
                    icon = Icons.Default.Event,
                    title = stringResource(R.string.date_label, ""), // Empty placeholder for dynamic label
                    value = dateFormat.format(selectedDate.time).lowercase(),
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
                            icon = Icons.Default.Schedule,
                            title = stringResource(R.string.start_time),
                            value = startTimeDisplay.lowercase(),
                            onClick = { showStartTimePicker = true }
                        )

                        EditDarkFormRow(
                            icon = Icons.Default.Schedule,
                            title = stringResource(R.string.end_time),
                            value = endTimeDisplay.lowercase(),
                            onClick = { showEndTimePicker = true }
                        )
                    }
                }

                // Location field
                EditDarkFormRow(
                    icon = Icons.Default.LocationOn,
                    title = if (location.isBlank()) stringResource(R.string.add_location) else location.lowercase(),
                    onClick = { /* Could expand to full input */ }
                )

                // Reminder Selection
                EditDarkFormRow(
                    icon = Icons.Default.NotificationsNone,
                    title = stringResource(R.string.reminder),
                    value = reminderOptions.find { it.first == selectedReminder }?.second ?: stringResource(R.string.none),
                    onClick = { showReminderMenu = true }
                )

                // Privacy Toggle
                EditDarkFormRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.private_event),
                    subtitle = stringResource(R.string.hidden_from_ai_assistant),
                    trailing = {
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isPrivate = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = accentColor,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
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
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.delete_event),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
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
                    colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    headlineContentColor = MaterialTheme.colorScheme.onSurface,
                    weekdayContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    dayContentColor = MaterialTheme.colorScheme.onSurface,
                    selectedDayContainerColor = accentColor,
                    selectedDayContentColor = Color.White,
                    todayContentColor = accentColor,
                    todayDateBorderColor = accentColor
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                    clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectorColor = accentColor,
                    periodSelectorSelectedContainerColor = accentColor,
                    periodSelectorSelectedContentColor = Color.White,
                    periodSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    timeSelectorSelectedContainerColor = accentColor,
                    timeSelectorSelectedContentColor = Color.White,
                    timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                    clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectorColor = accentColor,
                    periodSelectorSelectedContainerColor = accentColor,
                    periodSelectorSelectedContentColor = Color.White,
                    periodSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    timeSelectorSelectedContainerColor = accentColor,
                    timeSelectorSelectedContentColor = Color.White,
                    timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }

    // Reminder Menu
    if (showReminderMenu) {
        com.example.smarty.ui.components.common.SmartyDialog(
            title = stringResource(R.string.reminder),
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
                            Text(label.lowercase(), color = MaterialTheme.colorScheme.onSurface)
                            if (selectedReminder == minutes) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.selected),
                                    tint = accentColor
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
        com.example.smarty.ui.components.common.SmartyDialog(
            title = stringResource(R.string.delete_event),
            text = stringResource(R.string.delete_event_confirm, event.title),
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showDeleteConfirmation = false
                onDelete()
            },
            onDismiss = { showDeleteConfirmation = false },
            confirmText = stringResource(R.string.remove),
            dismissText = stringResource(R.string.cancel),
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
    val accentColor = LocalAccentColor.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = accentColor,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            cursorColor = accentColor
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
    val accentColor = LocalAccentColor.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (trailing != null) {
                trailing()
            } else if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
            }

            if (onClick != null && trailing == null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    com.example.smarty.ui.components.common.SmartyDialog(
        title = stringResource(R.string.select_time),
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
        confirmText = stringResource(R.string.ok),
        dismissText = stringResource(R.string.cancel)
    )
}
