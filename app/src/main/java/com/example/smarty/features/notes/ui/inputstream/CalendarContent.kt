package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.CalendarEmptyState
import com.example.smarty.ui.components.CalendarLoadingState
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

/**
 * Reimagined Calendar
 *
 * Design Philosophy:
 * - Warm, minimal, and focused
 * - Horizontal week scroll (not overwhelming grid)
 * - Today is prominent but not flashy
 * - Events feel like gentle reminders, not demands
 * - Weekends subtly differentiated (softer tone)
 * - Inline event creation (no separate dialogs)
 * - Clean typography, no visual noise
 */

@Composable
fun CalendarContent(
    events: List<CalendarEvent>,
    activeTimers: List<com.example.smarty.core.domain.model.SmartyTimer> = emptyList(),
    onEventClick: (CalendarEvent) -> Unit,
    onAddEvent: (Calendar) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    onCancelTimer: (SmartyTimer) -> Unit = {},
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    // Direct event creation callback - enables inline creation (bypasses dialog)
    onCreateEvent: ((
        title: String,
        description: String?,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean
    ) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // State
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    val today = remember { Calendar.getInstance() }

    // Inline event creation state
    var isCreatingEvent by remember { mutableStateOf(false) }
    var newEventTitle by remember { mutableStateOf("") }
    var newEventIsAllDay by remember { mutableStateOf(false) }
    var selectedStartHour by remember { mutableIntStateOf(9) }
    var selectedEndHour by remember { mutableIntStateOf(10) }

    // Generate a 3-week window centered on current week
    val weekDays = remember(selectedDate) {
        generateWeekWindow(selectedDate)
    }

    // Filter events for selected date
    val selectedDateEvents = remember(events, selectedDate) {
        filterEventsForDate(events, selectedDate)
    }

    // Events map for week indicators
    val eventDaysInWindow = remember(events, weekDays) {
        val daySet = mutableSetOf<Int>()
        weekDays.filterNotNull().forEach { day ->
            val hasEvent = events.any { event ->
                isSameDay(day, event.startTime)
            }
            if (hasEvent) {
                daySet.add(day.get(Calendar.DAY_OF_YEAR) * 1000 + day.get(Calendar.YEAR))
            }
        }
        daySet
    }

    // Theme colors
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    // Week list state for auto-scrolling to today
    val weekListState = rememberLazyListState()

    // Track if selected date is today (for showing "return to today" button)
    val isViewingToday = remember(selectedDate, today) {
        isSameDay(selectedDate, today)
    }

    // Coroutine scope for scroll actions
    val scope = rememberCoroutineScope()

    // Auto-scroll to center today on first load
    LaunchedEffect(Unit) {
        weekListState.scrollToItem(7) // Center of 21 days
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Compact Month Label
        item {
            val monthFormat = remember { SimpleDateFormat("MMMM", Locale.getDefault()) }
            val yearFormat = remember { SimpleDateFormat("yyyy", Locale.getDefault()) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = monthFormat.format(selectedDate.time),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = textPrimary
                    )
                    Text(
                        text = yearFormat.format(selectedDate.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = textMuted.copy(alpha = 0.6f)
                    )
                }

                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Return to Today button - appears when not viewing today
                    AnimatedVisibility(
                        visible = !isViewingToday,
                        enter = fadeIn() + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + scaleOut(targetScale = 0.8f)
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedDate = Calendar.getInstance()
                                // Scroll back to center (today)
                                scope.launch {
                                    weekListState.animateScrollToItem(7)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Today,
                                    contentDescription = stringResource(R.string.return_to_today),
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.today),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = accentColor
                                )
                            }
                        }
                    }

                    // Add event button - toggles inline creation
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (onCreateEvent != null) {
                                isCreatingEvent = !isCreatingEvent
                                if (!isCreatingEvent) {
                                    // Reset state when closing
                                    newEventTitle = ""
                                    newEventIsAllDay = false
                                    selectedStartHour = 9
                                    selectedEndHour = 10
                                    keyboardController?.hide()
                                }
                            } else {
                                // Fallback to old dialog method
                                onAddEvent(selectedDate)
                            }
                        },
                        shape = CircleShape,
                        color = if (isCreatingEvent) textMuted.copy(alpha = 0.3f) else accentColor,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                if (isCreatingEvent) Icons.Rounded.Close else Icons.Rounded.Add,
                                contentDescription = if (isCreatingEvent) stringResource(R.string.cancel) else stringResource(R.string.add_event),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Active Timers Section
        if (activeTimers.isNotEmpty()) {
            item {
                ActiveTimersRow(
                    timers = activeTimers,
                    accentColor = accentColor,
                    textPrimary = textPrimary,
                    surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
                    onCancelTimer = onCancelTimer
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Horizontal Week Scroll
        item {
            HorizontalWeekStrip(
                days = weekDays,
                selectedDate = selectedDate,
                today = today,
                eventDays = eventDaysInWindow,
                onDaySelected = { day ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedDate = day
                },
                accentColor = accentColor,
                listState = weekListState
            )
        }

        // Selected Date Context
        item {
            val dateFormat = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
            val isToday = isSameDay(selectedDate, today)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isToday) stringResource(R.string.today) else dateFormat.format(selectedDate.time).lowercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = if (isToday) accentColor else textPrimary
                )

                if (selectedDateEvents.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.events_count, selectedDateEvents.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textMuted
                    )
                }
            }
        }

        // Inline Event Creation Card
        item {
            AnimatedVisibility(
                visible = isCreatingEvent && onCreateEvent != null,
                enter = expandVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeOut()
            ) {
                InlineEventCreator(
                    title = newEventTitle,
                    onTitleChange = { newEventTitle = it },
                    isAllDay = newEventIsAllDay,
                    onAllDayChange = { newEventIsAllDay = it },
                    startHour = selectedStartHour,
                    onStartHourChange = { selectedStartHour = it },
                    endHour = selectedEndHour,
                    onEndHourChange = { selectedEndHour = it },
                    accentColor = accentColor,
                    onSave = {
                        if (newEventTitle.isNotBlank() && onCreateEvent != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            val startCal = selectedDate.clone() as Calendar
                            startCal.set(Calendar.HOUR_OF_DAY, if (newEventIsAllDay) 0 else selectedStartHour)
                            startCal.set(Calendar.MINUTE, 0)
                            startCal.set(Calendar.SECOND, 0)
                            startCal.set(Calendar.MILLISECOND, 0)

                            val endCal = selectedDate.clone() as Calendar
                            endCal.set(Calendar.HOUR_OF_DAY, if (newEventIsAllDay) 23 else selectedEndHour)
                            endCal.set(Calendar.MINUTE, if (newEventIsAllDay) 59 else 0)
                            endCal.set(Calendar.SECOND, if (newEventIsAllDay) 59 else 0)
                            endCal.set(Calendar.MILLISECOND, 0)

                            onCreateEvent(
                                newEventTitle,
                                null, // description
                                startCal.timeInMillis,
                                endCal.timeInMillis,
                                newEventIsAllDay
                            )

                            // Reset and close
                            newEventTitle = ""
                            newEventIsAllDay = false
                            selectedStartHour = 9
                            selectedEndHour = 10
                            isCreatingEvent = false
                            keyboardController?.hide()
                        }
                    },
                    onCancel = {
                        newEventTitle = ""
                        isCreatingEvent = false
                        keyboardController?.hide()
                    }
                )
            }
        }

        // Events or Loading or Empty State
        if (isLoading) {
            item {
                CalendarLoadingState(
                    count = 3,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        } else if (selectedDateEvents.isEmpty() && !isCreatingEvent) {
            item {
                CalendarEmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp)
                )
            }
        } else if (selectedDateEvents.isNotEmpty()) {
            itemsIndexed(selectedDateEvents, key = { _, event -> event.id }) { index, event ->
                EventCard(
                    event = event,
                    onClick = { onEventClick(event) },
                    accentColor = accentColor,
                    index = index
                )
            }
        }

        // Bottom Spacer
        item {
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}

/**
 * Inline event creator - warm, minimal, in-context
 */
@Composable
private fun InlineEventCreator(
    title: String,
    onTitleChange: (String) -> Unit,
    isAllDay: Boolean,
    onAllDayChange: (Boolean) -> Unit,
    startHour: Int,
    onStartHourChange: (Int) -> Unit,
    endHour: Int,
    onEndHourChange: (Int) -> Unit,
    accentColor: Color,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus title field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title input - clean, borderless
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = textPrimary
                ),
                cursorBrush = SolidColor(accentColor),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (title.isNotBlank()) onSave()
                    }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (title.isEmpty()) {
                            Text(
                                text = stringResource(R.string.what_is_happening),
                                style = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textMuted.copy(alpha = 0.5f),
                                    letterSpacing = (-0.2).sp
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Time options row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // All day chip
                TimeChip(
                    label = stringResource(R.string.all_day),
                    isSelected = isAllDay,
                    onClick = { onAllDayChange(!isAllDay) },
                    accentColor = accentColor
                )

                // Time range (only if not all day)
                AnimatedVisibility(
                    visible = !isAllDay,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Start hour selector
                        HourChip(
                            hour = startHour,
                            onHourChange = { newHour ->
                                onStartHourChange(newHour)
                                // Auto-adjust end if needed
                                if (newHour >= endHour) {
                                    onEndHourChange((newHour + 1) % 24)
                                }
                            },
                            accentColor = accentColor
                        )

                        Text(
                            text = stringResource(R.string.arrow_right),
                            color = textMuted.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )

                        // End hour selector
                        HourChip(
                            hour = endHour,
                            onHourChange = onEndHourChange,
                            accentColor = accentColor
                        )
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = textMuted.copy(alpha = 0.7f)
                    )
                ) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onSave,
                    enabled = title.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = textMuted.copy(alpha = 0.2f),
                        disabledContentColor = textMuted.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                }
            }
        }
    }
}

/**
 * Time selection chip
 */
@Composable
private fun TimeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    accentColor: Color
) {
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
            1.dp, textMuted.copy(alpha = 0.2f)
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (isSelected) accentColor else textMuted
        )
    }
}

/**
 * Hour selector chip with tap-to-cycle and long-press-to-pick behavior
 */
@Composable
private fun HourChip(
    hour: Int,
    onHourChange: (Int) -> Unit,
    accentColor: Color
) {
    val haptic = LocalHapticFeedback.current
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val amPm = if (hour < 12) stringResource(R.string.am) else stringResource(R.string.pm)

    // State for showing the hour picker
    var showHourPicker by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onHourChange((hour + 1) % 24)
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showHourPicker = true
                        }
                    )
                },
            shape = RoundedCornerShape(10.dp),
            color = if (showHourPicker) accentColor.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$displayHour",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textPrimary
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = amPm,
                    style = MaterialTheme.typography.labelSmall,
                    color = textMuted.copy(alpha = 0.6f)
                )
            }
        }

        // Time picker matching app's Smarty dialog style
        if (showHourPicker) {
            SmartyTimePicker(
                selectedHour = hour,
                onHourSelected = { h ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onHourChange(h)
                    showHourPicker = false
                },
                onDismiss = { showHourPicker = false },
                accentColor = accentColor
            )
        }
    }
}

/**
 * Smarty-Style Time Picker
 * Theme-aware design that adapts to light/dark mode:
 * - Uses MaterialTheme colors for automatic theming
 * - Super-rounded corners (32dp)
 * - Premium fintech/bento-grid feel
 * - Accent color highlights
 */
@Composable
private fun SmartyTimePicker(
    selectedHour: Int,
    onHourSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color
) {
    // Theme-aware colors - adapts to light/dark mode
    val surfaceBg = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    // Current selection display
    val displayHour = if (selectedHour == 0) 12 else if (selectedHour > 12) selectedHour - 12 else selectedHour
    val isPM = selectedHour >= 12

    // LazyColumn state for scrolling
    val listState = rememberLazyListState()

    // Scroll to selected hour on open
    LaunchedEffect(selectedHour) {
        val targetIndex = if (selectedHour >= 12) selectedHour - 12 else selectedHour
        listState.animateScrollToItem((targetIndex - 2).coerceAtLeast(0))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(surfaceVariant, surfaceBg)
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with current time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.select_time),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = textPrimary
                    )

                    // Current selection badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$displayHour:00 ${if (isPM) stringResource(R.string.pm) else stringResource(R.string.am)}",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // AM/PM Toggle - pill style matching app theme
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceBg
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        // AM
                        Surface(
                            onClick = {
                                // Convert current hour to AM
                                val newHour = if (selectedHour >= 12) selectedHour - 12 else selectedHour
                                onHourSelected(newHour)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isPM) accentColor else Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.am),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = if (!isPM) MaterialTheme.colorScheme.onPrimary else textMuted
                                )
                            }
                        }

                        // PM
                        Surface(
                            onClick = {
                                // Convert current hour to PM
                                val newHour = if (selectedHour < 12) selectedHour + 12 else selectedHour
                                onHourSelected(newHour)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPM) accentColor else Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.pm),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = if (isPM) MaterialTheme.colorScheme.onPrimary else textMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hours list - scrollable
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 12 hours (display as 12, 1, 2, ... 11)
                    val hours = listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
                    items(hours.size) { index ->
                        val displayH = hours[index]
                        // Convert to 24h based on current AM/PM
                        val hour24 = when {
                            displayH == 12 && !isPM -> 0
                            displayH == 12 && isPM -> 12
                            isPM -> displayH + 12
                            else -> displayH
                        }
                        val isSelected = hour24 == selectedHour

                        SmartyTimeItem(
                            displayHour = displayH,
                            isSelected = isSelected,
                            accentColor = accentColor,
                            textPrimary = textPrimary,
                            textMuted = textMuted,
                            onClick = { onHourSelected(hour24) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Done button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.done),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * Individual hour item in the Smarty-style picker - theme-aware
 */
@Composable
private fun SmartyTimeItem(
    displayHour: Int,
    isSelected: Boolean,
    accentColor: Color,
    textPrimary: Color,
    textMuted: Color,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent,
        label = "bgColor"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else textPrimary,
        label = "textColor"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$displayHour:00",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                ),
                color = textColor
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Horizontal scrolling week strip - core navigation
 */
@Composable
private fun HorizontalWeekStrip(
    days: List<Calendar?>,
    selectedDate: Calendar,
    today: Calendar,
    eventDays: Set<Int>,
    onDaySelected: (Calendar) -> Unit,
    accentColor: Color,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(days.filterNotNull()) { day ->
            val isSelected = isSameDay(day, selectedDate)
            val isToday = isSameDay(day, today)
            val isWeekend = day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                           day.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val hasEvents = eventDays.contains(
                day.get(Calendar.DAY_OF_YEAR) * 1000 + day.get(Calendar.YEAR)
            )

            DayPill(
                day = day,
                isSelected = isSelected,
                isToday = isToday,
                isWeekend = isWeekend,
                hasEvents = hasEvents,
                onClick = { onDaySelected(day) },
                accentColor = accentColor
            )
        }
    }
}

/**
 * Individual day pill - tactile and readable
 */
@Composable
private fun DayPill(
    day: Calendar,
    isSelected: Boolean,
    isToday: Boolean,
    isWeekend: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    accentColor: Color
) {
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }

    // Animation
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> accentColor
            isToday -> accentColor.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        label = "bgColor"
    )

    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> accentColor
        isWeekend -> textMuted.copy(alpha = 0.5f)
        else -> textPrimary.copy(alpha = 0.85f)
    }

    val dayLabelColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
        isToday -> accentColor.copy(alpha = 0.7f)
        isWeekend -> textMuted.copy(alpha = 0.35f)
        else -> textMuted.copy(alpha = 0.5f)
    }

    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 4.dp)
            .width(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = accentColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    )
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Day name
        Text(
            text = dayFormat.format(day.time).lowercase().take(3),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                fontSize = 10.sp
            ),
            color = dayLabelColor
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Day number
        Text(
            text = day.get(Calendar.DAY_OF_MONTH).toString(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium
            ),
            color = textColor
        )

        // Event indicator dot
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(5.dp)
                .alpha(if (hasEvents && !isSelected) 1f else 0f)
                .clip(CircleShape)
                .background(if (isToday) accentColor else textMuted.copy(alpha = 0.4f))
        )
    }
}

/**
 * Empty state - warm and inviting
 */
@Composable
private fun EmptyDayState(
    isToday: Boolean,
    onAddEvent: () -> Unit,
    accentColor: Color
) {
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.EventAvailable,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .alpha(0.4f),
            tint = if (isToday) accentColor else textMuted
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isToday) stringResource(R.string.nothing_planned) else stringResource(R.string.free_day),
            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp),
            color = textMuted.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.tap_plus_to_add),
            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
            color = accentColor.copy(alpha = 0.6f),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAddEvent
            )
        )
    }
}

/**
 * Event card - clean, scannable, purposeful
 */
@Composable
private fun EventCard(
    event: CalendarEvent,
    onClick: () -> Unit,
    accentColor: Color,
    index: Int
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    // Determine if happening now
    val now = System.currentTimeMillis()
    val isHappeningNow = now >= event.startTime && now <= event.endTime

    // Choose icon based on event type
    val eventIcon = selectEventIcon(event)

    // Stagger animation
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 50L)
        appeared = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f),
        label = "alpha"
    )

    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 20f,
        animationSpec = spring(dampingRatio = 0.8f),
        label = "offsetY"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = offsetY
            },
        shape = RoundedCornerShape(20.dp),
        color = if (isHappeningNow) accentColor.copy(alpha = 0.08f) else surfaceColor,
        border = if (isHappeningNow) {
            androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Icon indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isHappeningNow) accentColor.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = eventIcon,
                    contentDescription = null,
                    tint = if (isHappeningNow) accentColor else textMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Center: Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val timeText = when {
                        event.isAllDay -> stringResource(R.string.all_day)
                        isHappeningNow -> stringResource(R.string.now)
                        else -> timeFormat.format(Date(event.startTime)).lowercase()
                    }

                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isHappeningNow) FontWeight.Medium else FontWeight.Normal,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (isHappeningNow) accentColor else textMuted
                    )

                    event.location?.takeIf { it.isNotBlank() }?.let { location ->
                        Text(
                            text = " ${stringResource(R.string.dot_separator)} ",
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted.copy(alpha = 0.5f)
                        )
                        Text(
                            text = location,
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (event.isRecurring) {
                Icon(
                    imageVector = eventIcon,
                    contentDescription = stringResource(R.string.repeating),
                    tint = textMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Select appropriate icon based on event content
 */
private fun selectEventIcon(event: CalendarEvent): ImageVector {
    val title = event.title.lowercase()
    val location = event.location?.lowercase() ?: ""

    return when {
        title.contains("meet") || title.contains("call") || title.contains("sync") -> Icons.Outlined.Groups
        title.contains("deadline") || title.contains("due") -> Icons.Outlined.Flag
        title.contains("gym") || title.contains("workout") || title.contains("run") ||
        title.contains("exercise") -> Icons.Outlined.FitnessCenter
        title.contains("lunch") || title.contains("dinner") || title.contains("breakfast") ||
        title.contains("coffee") -> Icons.Outlined.Restaurant
        title.contains("flight") || title.contains("trip") || title.contains("travel") -> Icons.Outlined.Flight
        title.contains("doctor") || title.contains("appointment") || title.contains("dentist") -> Icons.Outlined.LocalHospital
        title.contains("birthday") || title.contains("party") || title.contains("celebration") -> Icons.Outlined.Celebration
        title.contains("study") || title.contains("class") || title.contains("lecture") -> Icons.Outlined.School
        title.contains("remind") -> Icons.Outlined.NotificationsActive
        location.isNotBlank() -> Icons.Outlined.Place
        event.isAllDay -> Icons.Outlined.CalendarToday
        event.isRecurring -> Icons.Outlined.EventRepeat
        else -> Icons.Outlined.Schedule
    }
}

// ============ Helper Functions ============

private fun generateWeekWindow(centerDate: Calendar): List<Calendar?> {
    val days = mutableListOf<Calendar>()
    val start = centerDate.clone() as Calendar
    start.add(Calendar.DAY_OF_MONTH, -10)

    repeat(21) { i ->
        val day = start.clone() as Calendar
        day.add(Calendar.DAY_OF_MONTH, i)
        days.add(day)
    }

    return days
}

// ============ Helper Components ============

@Composable
private fun ActiveTimersRow(
    timers: List<com.example.smarty.core.domain.model.SmartyTimer>,
    accentColor: Color,
    textPrimary: Color,
    surfaceVariant: Color,
    onCancelTimer: (SmartyTimer) -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // State to trigger recomposition every second for countdown
    var ticks by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timers) {
        while(true) {
            ticks = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.active_timers).lowercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            color = textPrimary.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(timers, key = { it.id }) { timer ->
                // Calculate time remaining for timers
                val now = ticks
                val timeRemaining = if (!timer.isAlarm) {
                    (timer.triggerTime - now).coerceAtLeast(0)
                } else {
                    0L
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = surfaceVariant.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.15f)),
                    modifier = Modifier.widthIn(min = 130.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (timer.isAlarm) Icons.Rounded.Alarm else Icons.Rounded.Timer,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = timer.name.lowercase(),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val statusText = if (timer.isAlarm) {
                                timeFormat.format(Date(timer.triggerTime)).lowercase()
                            } else {
                                val minutes = timeRemaining / 60000
                                val seconds = (timeRemaining % 60000) / 1000
                                String.format("%d:%02d", minutes, seconds)
                            }

                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = textPrimary.copy(alpha = 0.5f)
                            )
                        }

                        // Cancel button
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                                .clickable { onCancelTimer(timer) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun filterEventsForDate(events: List<CalendarEvent>, date: Calendar): List<CalendarEvent> {
    val dayStart = date.clone() as Calendar
    dayStart.set(Calendar.HOUR_OF_DAY, 0)
    dayStart.set(Calendar.MINUTE, 0)
    dayStart.set(Calendar.SECOND, 0)
    dayStart.set(Calendar.MILLISECOND, 0)

    val dayEnd = dayStart.clone() as Calendar
    dayEnd.add(Calendar.DAY_OF_MONTH, 1)

    return events.filter { event ->
        event.startTime < dayEnd.timeInMillis && event.endTime >= dayStart.timeInMillis
    }.sortedBy { it.startTime }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) &&
           cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
}

private fun isSameDay(cal: Calendar, timestamp: Long): Boolean {
    val other = Calendar.getInstance().apply { timeInMillis = timestamp }
    return isSameDay(cal, other)
}
