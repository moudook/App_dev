package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
 * Event colors for visual categorization
 */
private val eventColors = listOf(
    Color(0xFF0066FF), // Electric Blue
    Color(0xFFFF3B30), // Red
    Color(0xFFFF9500), // Orange
    Color(0xFFFFCC00), // Yellow
    Color(0xFF34C759), // Green
    Color(0xFF5856D6), // Purple
    Color(0xFFAF52DE), // Magenta
    Color(0xFF00C7BE), // Teal
)

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

/**
 * Modern Edit Event Sheet following app design guidelines:
 * - Pre-populated with existing event data
 * - Delete option with confirmation
 * - Same styling as AddEventSheet
 */
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
    val shapes = LocalShapes.current
    val spacing = LocalSpacing.current

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
    var selectedColor by remember {
        mutableStateOf(event.color?.let { Color(it) })
    }
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
        title, description, isAllDay, location, selectedColor,
        selectedReminder, isPrivate, selectedDate, startHour,
        startMinute, endHour, endMinute
    ) {
        title != event.title ||
        description != (event.description ?: "") ||
        isAllDay != event.isAllDay ||
        location != (event.location ?: "") ||
        selectedColor?.toArgb() != event.color ||
        selectedReminder != event.reminderMinutes ||
        isPrivate != event.isEventPrivate ||
        selectedDate.get(Calendar.DAY_OF_YEAR) != existingCalendar.get(Calendar.DAY_OF_YEAR) ||
        selectedDate.get(Calendar.YEAR) != existingCalendar.get(Calendar.YEAR) ||
        startHour != existingCalendar.get(Calendar.HOUR_OF_DAY) ||
        startMinute != existingCalendar.get(Calendar.MINUTE) ||
        endHour != existingEndCalendar.get(Calendar.HOUR_OF_DAY) ||
        endMinute != existingEndCalendar.get(Calendar.MINUTE)
    }

    // Main content
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.bottomSheet,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
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
                    .padding(vertical = spacing.medium),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(shapes.pill)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.default),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }

                Text(
                    text = "Edit Event",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                                color = selectedColor?.toArgb(),
                                reminderMinutes = selectedReminder,
                                isEventPrivate = isPrivate,
                                updatedAt = System.currentTimeMillis()
                            )
                            onSave(updatedEvent)
                        }
                    },
                    enabled = title.isNotBlank() && hasChanges,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LocalAccentColor.current
                    )
                ) {
                    Text(
                        text = "Save",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = spacing.small),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Scrollable form
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.default)
                    .padding(bottom = spacing.large),
                verticalArrangement = Arrangement.spacedBy(spacing.default)
            ) {
                // Title field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Event title") },
                    placeholder = { Text("Add title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = shapes.input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalAccentColor.current,
                        cursorColor = LocalAccentColor.current
                    )
                )

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("Add details") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    shape = shapes.input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalAccentColor.current,
                        cursorColor = LocalAccentColor.current
                    )
                )

                // All Day Toggle
                EditFormRow(
                    icon = Icons.Default.WbSunny,
                    title = "All day",
                    trailing = {
                        Switch(
                            checked = isAllDay,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isAllDay = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = LocalAccentColor.current
                            )
                        )
                    }
                )

                // Date Selection
                EditFormRow(
                    icon = Icons.Default.CalendarToday,
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
                    Column(
                        verticalArrangement = Arrangement.spacedBy(spacing.small)
                    ) {
                        EditFormRow(
                            icon = Icons.Default.Schedule,
                            title = "Start time",
                            value = startTimeDisplay,
                            onClick = { showStartTimePicker = true }
                        )

                        EditFormRow(
                            icon = Icons.Default.Schedule,
                            title = "End time",
                            value = endTimeDisplay,
                            onClick = { showEndTimePicker = true }
                        )
                    }
                }

                // Location field
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    placeholder = { Text("Add location") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = shapes.input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalAccentColor.current,
                        cursorColor = LocalAccentColor.current
                    )
                )

                // Color Selection
                EditFormSection(title = "Color") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // No color option
                        EditColorChip(
                            color = null,
                            isSelected = selectedColor == null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedColor = null
                            }
                        )

                        eventColors.forEach { color ->
                            EditColorChip(
                                color = color,
                                isSelected = selectedColor == color,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedColor = color
                                }
                            )
                        }
                    }
                }

                // Reminder Selection
                EditFormRow(
                    icon = Icons.Default.Notifications,
                    title = "Reminder",
                    value = reminderOptions.find { it.first == selectedReminder }?.second ?: "None",
                    onClick = { showReminderMenu = true }
                )

                // Privacy Toggle
                EditFormRow(
                    icon = Icons.Default.Lock,
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
                                checkedThumbColor = Color.White,
                                checkedTrackColor = LocalAccentColor.current
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.height(spacing.default))

                // Delete Button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDeleteConfirmation = true
                        },
                    shape = shapes.button,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete Event",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

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
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Start Time Picker Dialog
    if (showStartTimePicker) {
        EditTimePickerDialog(
            onDismiss = { showStartTimePicker = false },
            onConfirm = {
                startHour = startTimePickerState.hour
                startMinute = startTimePickerState.minute
                // Auto-adjust end time if needed
                if (startHour >= endHour && startMinute >= endMinute) {
                    endHour = (startHour + 1) % 24
                }
                showStartTimePicker = false
            }
        ) {
            TimePicker(state = startTimePickerState)
        }
    }

    // End Time Picker Dialog
    if (showEndTimePicker) {
        EditTimePickerDialog(
            onDismiss = { showEndTimePicker = false },
            onConfirm = {
                endHour = endTimePickerState.hour
                endMinute = endTimePickerState.minute
                showEndTimePicker = false
            }
        ) {
            TimePicker(state = endTimePickerState)
        }
    }

    // Reminder Menu
    if (showReminderMenu) {
        AlertDialog(
            onDismissRequest = { showReminderMenu = false },
            title = { Text("Reminder") },
            text = {
                Column {
                    reminderOptions.forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(LocalShapes.current.button)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedReminder = minutes
                                    showReminderMenu = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label)
                            if (selectedReminder == minutes) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = LocalAccentColor.current
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReminderMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Delete Event?",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text("Are you sure you want to delete \"${event.title}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditFormRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val shapes = LocalShapes.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shapes.button,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
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
                    color = LocalAccentColor.current,
                    fontWeight = FontWeight.Medium
                )
            }

            if (onClick != null && trailing == null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EditFormSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        content()
    }
}

@Composable
private fun EditColorChip(
    color: Color?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) LocalAccentColor.current else Color.Transparent,
        animationSpec = tween(200),
        label = "border"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = CircleShape
            )
            .padding(3.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (color == null) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = "No color",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select time") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
