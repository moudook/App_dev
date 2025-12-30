package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PREMIUM DARK ADD EVENT SHEET
 * 
 * Design: Always-dark theme matching Calendar aesthetic
 * - Deep dark background for immersive experience
 * - Electric Blue accent (consistent with Cogni design)
 * - Gradient accents and modern form fields
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// Design System Colors - Now uses theme colors
private val SheetAccent = Color(0xFF2979FF)
private val SheetAccentLight = Color(0xFF5C9AFF)

// Legacy color compatibility - maps to theme colors
// These are kept as vals for non-composable contexts
private val SheetDarkBg = Color(0xFF0D0D12)
private val SheetSurfaceDark = Color(0xFF1A1A24)
private val SheetMutedText = Color(0xFF6B6B80)
private val SheetWhite = Color(0xFFF5F5F7)
private val SheetInputBg = Color(0xFF1E1E28)
private val SheetBorder = Color(0xFF2A2A35)

// Theme-aware color functions for composable contexts
@Composable
private fun sheetBackground() = MaterialTheme.colorScheme.background

@Composable
private fun sheetSurface() = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun sheetTextPrimary() = MaterialTheme.colorScheme.onBackground

@Composable
private fun sheetTextMuted() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun sheetInputBgThemed() = MaterialTheme.colorScheme.surface

@Composable
private fun sheetBorderThemed() = MaterialTheme.colorScheme.outline



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
fun AddEventSheet(
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        description: String?,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean,
        location: String?,
        color: Int?,
        reminderMinutes: Int?,
        isPrivate: Boolean
    ) -> Unit,
    initialDate: Calendar = Calendar.getInstance(),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // Form state
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isAllDay by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }

    var selectedReminder by remember { mutableStateOf<Int?>(null) }
    var isPrivate by remember { mutableStateOf(false) }

    // Date/Time state
    var selectedDate by remember { mutableStateOf(initialDate.clone() as Calendar) }
    var startHour by remember { mutableIntStateOf(initialDate.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf((initialDate.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var endMinute by remember { mutableIntStateOf(0) }

    // UI state
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showReminderMenu by remember { mutableStateOf(false) }

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

    // ═══════════════════════════════════════════════════════════════════
    // MAIN SHEET (Dark Theme)
    // ═══════════════════════════════════════════════════════════════════
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = sheetBackground()
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
                    text = "New Event",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sheetTextPrimary()
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

                            if (!isAllDay && endCal.timeInMillis <= startCal.timeInMillis) {
                                if (endHour == 0 && endMinute == 0) {
                                    endCal.add(Calendar.DAY_OF_MONTH, 1)
                                } else {
                                    endCal.timeInMillis = startCal.timeInMillis + 3600000L
                                }
                            }

                            onConfirm(
                                title,
                                description.ifBlank { null },
                                startCal.timeInMillis,
                                endCal.timeInMillis,
                                isAllDay,
                                location.ifBlank { null },
                                null, // Color option removed
                                selectedReminder,
                                isPrivate
                            )
                        }
                    },
                    enabled = title.isNotBlank(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SheetAccent,
                        disabledContentColor = SheetMutedText.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Add", fontWeight = FontWeight.Bold)
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
                DarkTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Event title",
                    singleLine = true
                )

                // Description field
                DarkTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Description",
                    minLines = 2,
                    maxLines = 4
                )

                // All Day Toggle
                DarkFormRow(
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
                                checkedThumbColor = SheetWhite,
                                checkedTrackColor = SheetAccent,
                                uncheckedThumbColor = SheetMutedText,
                                uncheckedTrackColor = SheetSurfaceDark
                            )
                        )
                    }
                )

                // Date Selection
                DarkFormRow(
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DarkFormRow(
                            icon = Icons.Default.Schedule,
                            title = "Start time",
                            value = startTimeDisplay,
                            onClick = { showStartTimePicker = true }
                        )

                        DarkFormRow(
                            icon = Icons.Default.Schedule,
                            title = "End time",
                            value = endTimeDisplay,
                            onClick = { showEndTimePicker = true }
                        )
                    }
                }

                // Location field
                DarkFormRow(
                    icon = Icons.Default.LocationOn,
                    title = if (location.isBlank()) "Add location" else location,
                    onClick = { /* Could expand to full input */ }
                )

                // Reminder Selection
                DarkFormRow(
                    icon = Icons.Default.Notifications,
                    title = "Reminder",
                    value = reminderOptions.find { it.first == selectedReminder }?.second ?: "None",
                    onClick = { showReminderMenu = true }
                )

                // Privacy Toggle
                DarkFormRow(
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
                                checkedThumbColor = SheetWhite,
                                checkedTrackColor = SheetAccent,
                                uncheckedThumbColor = SheetMutedText,
                                uncheckedTrackColor = SheetSurfaceDark
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIALOGS (Using dark theme overrides)
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
    // Start Time Picker Dialog
    if (showStartTimePicker) {
        CosmicTimeSelectorDialog(
            initialHour = startHour,
            initialMinute = startMinute,
            onDismiss = { showStartTimePicker = false },
            onConfirm = { h, m ->
                startHour = h
                startMinute = m
                if (startHour > endHour || (startHour == endHour && startMinute >= endMinute)) {
                   // Auto advance end time by 1 hour if start passes end
                    endHour = (startHour + 1) % 24
                    endMinute = startMinute
                }
                showStartTimePicker = false
            }
        )
    }

    // End Time Picker Dialog
    if (showEndTimePicker) {
        CosmicTimeSelectorDialog(
            initialHour = endHour,
            initialMinute = endMinute,
            onDismiss = { showEndTimePicker = false },
            onConfirm = { h, m ->
                endHour = h
                endMinute = m
                showEndTimePicker = false
            }
        )
    }

    // Reminder Menu
    if (showReminderMenu) {
        AlertDialog(
            onDismissRequest = { showReminderMenu = false },
            containerColor = SheetSurfaceDark,
            titleContentColor = SheetWhite,
            title = { Text("Reminder") },
            text = {
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
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = SheetAccent
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showReminderMenu = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetMutedText)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DARK THEMED FORM COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun DarkTextField(
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
private fun DarkFormRow(
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
                    color = sheetTextPrimary()
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
private fun DarkFormSection(
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



@Composable
private fun CosmicTimeSelectorDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            CosmicTimeSelector(
                initialHour = initialHour,
                initialMinute = initialMinute,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun CosmicTimeSelector(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(initialHour) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }
    
    // For visual conversion to 12h format inside the wheels, 
    // but we store 24h state for logic consistency.
    // However, the user provided image showed AM/PM, so let's support that style 
    // but with our custom UI.
    
    val isPm = selectedHour >= 12
    val displayHour = if (selectedHour % 12 == 0) 12 else selectedHour % 12
    
    // State to track AM/PM for the switcher
    var isPmState by remember { mutableStateOf(isPm) }
    
    LaunchedEffect(isPmState) {
        // adjust hour when PM toggles
        if (isPmState && selectedHour < 12) selectedHour += 12
        else if (!isPmState && selectedHour >= 12) selectedHour -= 12
    }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .width(340.dp)
            .height(380.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(SheetDarkBg)
            .border(1.dp, SheetBorder, RoundedCornerShape(32.dp))
            .clickable(enabled = false) {}, // consume clicks
        contentAlignment = Alignment.Center
    ) {
        // Decorative background elements
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF2979FF).copy(alpha = 0.1f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 1.5f
                ),
                radius = size.minDimension / 1.5f,
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetMutedText)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                }
                
                TextButton(
                    onClick = { onConfirm(selectedHour, selectedMinute) },
                    colors = ButtonDefaults.textButtonColors(contentColor = SheetWhite)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // The Core Time Weaver
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Central Lens / Highlight Line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF2979FF).copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF2979FF).copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            ),
                            RoundedCornerShape(8.dp)
                        )
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // HOURS WHEEL
                    CosmicWheel(
                        range = 1..12,
                        initialValue = displayHour,
                        onValueChange = { h ->
                            val isPmCurrent = selectedHour >= 12
                            selectedHour = if (isPmCurrent) {
                                if (h == 12) 12 else h + 12
                            } else {
                                if (h == 12) 0 else h
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        label = "HRS"
                    )
                    
                    // Separator dots
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF2979FF), CircleShape))
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF2979FF).copy(0.5f), CircleShape))
                    }

                    // MINUTES WHEEL
                    CosmicWheel(
                        range = 0..59,
                        initialValue = selectedMinute,
                        onValueChange = { m -> 
                            selectedMinute = m 
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        format = { "%02d".format(it) },
                        label = "MIN"
                    )
                }
            }

            // AM/PM Toggle (Gravity Switch)
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0A0A10))
                    .border(1.dp, Color(0xFF2A2A35), RoundedCornerShape(24.dp))
                    .padding(4.dp)
            ) {
               Row(modifier = Modifier.fillMaxSize()) {
                   // AM
                   Box(
                       modifier = Modifier
                           .weight(1f)
                           .fillMaxHeight()
                           .clip(RoundedCornerShape(20.dp))
                           .background(if (!isPmState) Color(0xFF2979FF) else Color.Transparent)
                           .clickable { isPmState = false },
                       contentAlignment = Alignment.Center
                   ) {
                       Text(
                           "AM", 
                           color = if (!isPmState) SheetWhite else Color(0xFF404050),
                           fontWeight = FontWeight.Bold
                       )
                   }
                   
                   // PM
                   Box(
                       modifier = Modifier
                           .weight(1f)
                           .fillMaxHeight()
                           .clip(RoundedCornerShape(20.dp))
                           .background(if (isPmState) Color(0xFF2979FF) else Color.Transparent)
                           .clickable { isPmState = true },
                       contentAlignment = Alignment.Center
                   ) {
                       Text(
                           "PM", 
                           color = if (isPmState) SheetWhite else Color(0xFF404050),
                           fontWeight = FontWeight.Bold
                       )
                   }
               }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CosmicWheel(
    range: IntRange,
    initialValue: Int,
    onValueChange: (Int) -> Unit,
    format: (Int) -> String = { it.toString() },
    label: String
) {
    // Generate a large list to simulate infinity
    val items = remember { range.toList() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % items.size) + items.indexOf(initialValue) - 2
    )
    
    // Snap behavior
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    
    // Calculate selected item
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val centerIndex = index + 2 // Middle of visible 5 items
                val actualIndex = centerIndex % items.size
                val value = items[actualIndex]
                if (value != initialValue) { 
                     onValueChange(value)
                }
            }
    }
    
    // Notify parent of selection changes more accurately
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }
    
    LaunchedEffect(layoutInfo) {
         val viewportCenter = layoutInfo.viewportStartOffset + (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
         val closestItem = layoutInfo.visibleItemsInfo.minByOrNull { Math.abs((it.offset + it.size / 2) - viewportCenter) }
         closestItem?.let {
             // Calculate the real index in our list data
             val rawIndex = it.index
             val dataIndex = rawIndex % items.size
             val value = items[dataIndex]
             if (value != initialValue) {
                 onValueChange(value)
             }
         }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label, 
            style = MaterialTheme.typography.labelSmall, 
            color = Color(0xFF6B6B80),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(250.dp)
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 0.dp) // Manual padding handled by item sizing
            ) {
                items(Int.MAX_VALUE) { index ->
                    val itemValue = items[index % items.size]
                    
                    // Simple item box
                    Box(
                        modifier = Modifier
                            .height(50.dp) // Fixed height to match snap
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CosmicWheelItem(
                            text = format(itemValue),
                            listState = listState,
                            index = index
                        )
                    }
                }
            }
             
            // Top and Bottom fade gradients
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0F0F16), Color.Transparent)))
                    .pointerInput(Unit) {} // pass through clicks? No, visual only
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0F0F16))))
            )
        }
    }
}

@Composable
private fun CosmicWheelItem(
    text: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    index: Int
) {
    // Dynamic scaling based on distance from center
    val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
    
    var scale by remember { mutableFloatStateOf(0.6f) }
    var alpha by remember { mutableFloatStateOf(0.3f) }
    var color by remember { mutableStateOf(Color(0xFF6B6B80)) }
    
    if (itemInfo != null) {
        val viewportCenter = listState.layoutInfo.viewportStartOffset + (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset) / 2
        val itemCenter = itemInfo.offset + itemInfo.size / 2
        val distance = Math.abs(viewportCenter - itemCenter)
        val maxDistance = 200f // Falloff distance
        
        val normalizedDistance = (distance / maxDistance).coerceIn(0f, 1f)
        
        scale = lerp(1.3f, 0.6f, normalizedDistance)
        alpha = lerp(1f, 0.3f, normalizedDistance)
        
        // Color interpolation
        color = if (normalizedDistance < 0.2f) {
            Color(0xFF2979FF) // Active Highlight
        } else {
             Color(0xFF6B6B80) // Inactive
        }
    }

    Text(
        text = text,
        style = TextStyle(
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            shadow = if (scale > 1.0f) Shadow(color = Color(0xFF2979FF), blurRadius = 20f) else null
        ),
        color = color,
        modifier = Modifier
            .scale(scale)
            .alpha(alpha)
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
