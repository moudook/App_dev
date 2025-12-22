package com.example.smarty.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.CalendarEventCard
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Modern Calendar Screen following app design guidelines:
 * - Super-rounded shapes and soft shadows
 * - Week strip view for quick navigation
 * - Mini month grid for date selection
 * - Animated transitions
 * - Spring-based interactions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    events: List<CalendarEvent>,
    onBackClick: () -> Unit,
    onAddEvent: () -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val shapes = LocalShapes.current
    val spacing = LocalSpacing.current

    // Calendar state
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var showMonthPicker by remember { mutableStateOf(false) }

    // Generate week dates centered on selected date
    val weekDates = remember(selectedDate) {
        generateWeekDates(selectedDate)
    }

    // Filter events for selected date
    val selectedDateEvents = remember(events, selectedDate) {
        val dayStart = selectedDate.clone() as Calendar
        dayStart.set(Calendar.HOUR_OF_DAY, 0)
        dayStart.set(Calendar.MINUTE, 0)
        dayStart.set(Calendar.SECOND, 0)
        dayStart.set(Calendar.MILLISECOND, 0)

        val dayEnd = dayStart.clone() as Calendar
        dayEnd.add(Calendar.DAY_OF_MONTH, 1)

        events.filter { event ->
            event.startTime >= dayStart.timeInMillis && event.startTime < dayEnd.timeInMillis
        }.sortedBy { it.startTime }
    }

    // Events for month (for indicators)
    val monthEventDays = remember(events, currentMonth) {
        val start = currentMonth.clone() as Calendar
        start.set(Calendar.DAY_OF_MONTH, 1)
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)

        val end = start.clone() as Calendar
        end.add(Calendar.MONTH, 1)

        events.filter { it.startTime >= start.timeInMillis && it.startTime < end.timeInMillis }
            .map { event ->
                val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
                cal.get(Calendar.DAY_OF_MONTH)
            }.toSet()
    }

    Scaffold(
        topBar = {
            CalendarTopBar(
                currentMonth = currentMonth,
                onBackClick = onBackClick,
                onMonthClick = { showMonthPicker = !showMonthPicker },
                onTodayClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedDate = Calendar.getInstance()
                    currentMonth = Calendar.getInstance()
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onAddEvent()
                },
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = shapes.fab
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Event")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Month Picker (expandable)
            AnimatedVisibility(
                visible = showMonthPicker,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                MiniMonthGrid(
                    currentMonth = currentMonth,
                    selectedDate = selectedDate,
                    eventDays = monthEventDays,
                    onDateSelected = { date ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedDate = date
                        showMonthPicker = false
                    },
                    onMonthChange = { newMonth ->
                        currentMonth = newMonth
                    },
                    modifier = Modifier.padding(horizontal = spacing.default)
                )
            }

            // Week Strip
            WeekStrip(
                weekDates = weekDates,
                selectedDate = selectedDate,
                eventDays = monthEventDays,
                onDateSelected = { date ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedDate = date
                    // Update current month if needed
                    if (date.get(Calendar.MONTH) != currentMonth.get(Calendar.MONTH) ||
                        date.get(Calendar.YEAR) != currentMonth.get(Calendar.YEAR)) {
                        currentMonth = date.clone() as Calendar
                    }
                },
                onPreviousWeek = {
                    // Navigate to previous week
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val newDate = selectedDate.clone() as Calendar
                    newDate.add(Calendar.WEEK_OF_YEAR, -1)
                    selectedDate = newDate
                    if (newDate.get(Calendar.MONTH) != currentMonth.get(Calendar.MONTH)) {
                        currentMonth = newDate.clone() as Calendar
                    }
                },
                onNextWeek = {
                    // Navigate to next week
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val newDate = selectedDate.clone() as Calendar
                    newDate.add(Calendar.WEEK_OF_YEAR, 1)
                    selectedDate = newDate
                    if (newDate.get(Calendar.MONTH) != currentMonth.get(Calendar.MONTH)) {
                        currentMonth = newDate.clone() as Calendar
                    }
                },
                onDateLongPress = { date ->
                    // Phase 6: Long press to create event for that date
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedDate = date
                    onAddEvent()  // Open event creation dialog
                },
                modifier = Modifier.padding(vertical = spacing.medium)
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = spacing.default),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Events List
            if (selectedDateEvents.isEmpty()) {
                EmptyCalendarState(
                    selectedDate = selectedDate,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.extraLarge)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = spacing.default,
                        vertical = spacing.medium
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium)
                ) {
                    items(
                        items = selectedDateEvents,
                        key = { it.id }
                    ) { event ->
                        CalendarEventCard(
                            event = event,
                            onClick = { onEventClick(event) },
                            onDelete = { onDeleteEvent(event) },
                            index = selectedDateEvents.indexOf(event),
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(
    currentMonth: Calendar,
    onBackClick: () -> Unit,
    onMonthClick: () -> Unit,
    onTodayClick: () -> Unit
) {
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .clip(LocalShapes.current.button)
                    .clickable(onClick = onMonthClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = monthFormat.format(currentMonth.time),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select month",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            // Today button
            TextButton(
                onClick = onTodayClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = LocalAccentColor.current
                )
            ) {
                Text(
                    text = "Today",
                    fontWeight = FontWeight.Medium
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun WeekStrip(
    weekDates: List<Calendar>,
    selectedDate: Calendar,
    eventDays: Set<Int>,
    onDateSelected: (Calendar) -> Unit,
    onDateLongPress: (Calendar) -> Unit = {},  // Phase 6: Long press for quick event creation
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val today = remember { Calendar.getInstance() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val accentColor = LocalAccentColor.current

    // Find index of selected date
    val selectedIndex = weekDates.indexOfFirst { cal ->
        cal.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR) &&
        cal.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
    }.coerceAtLeast(0)

    // Scroll to selected date on first composition
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(maxOf(0, selectedIndex - 2))
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous week arrow
        IconButton(
            onClick = onPreviousWeek,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Previous week",
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(weekDates.size) { index ->
                val date = weekDates[index]
                val isSelected = date.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR) &&
                                date.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
                val isToday = date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                             date.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                val hasEvents = eventDays.contains(date.get(Calendar.DAY_OF_MONTH)) &&
                               date.get(Calendar.MONTH) == selectedDate.get(Calendar.MONTH)

                DayChip(
                    date = date,
                    isSelected = isSelected,
                    isToday = isToday,
                    hasEvents = hasEvents,
                    onClick = { onDateSelected(date) },
                    onLongPress = { onDateLongPress(date) }
                )
            }
        }
        
        // Next week arrow
        IconButton(
            onClick = onNextWeek,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Next week",
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayChip(
    date: Calendar,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val shapes = LocalShapes.current
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> LocalAccentColor.current
            isToday -> LocalAccentColor.current.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "dayBg"
    )

    val textColor = when {
        isSelected -> Color.White
        isToday -> LocalAccentColor.current
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .width(52.dp)
            .clip(shapes.button)
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Day name
        Text(
            text = dayFormat.format(date.time).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = 0.7f)
        )

        // Day number
        Text(
            text = date.get(Calendar.DAY_OF_MONTH).toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        // Event indicator dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (hasEvents) {
                        if (isSelected) Color.White.copy(alpha = 0.8f)
                        else LocalAccentColor.current
                    } else Color.Transparent
                )
        )
    }
}

@Composable
private fun MiniMonthGrid(
    currentMonth: Calendar,
    selectedDate: Calendar,
    eventDays: Set<Int>,
    onDateSelected: (Calendar) -> Unit,
    onMonthChange: (Calendar) -> Unit,
    modifier: Modifier = Modifier
) {
    val shapes = LocalShapes.current
    val spacing = LocalSpacing.current
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val today = remember { Calendar.getInstance() }

    // Generate days in month
    val daysInMonth = remember(currentMonth) {
        generateMonthDays(currentMonth)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.cardMedium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(spacing.default)
        ) {
            // Month Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onMonthChange((currentMonth.clone() as Calendar).apply {
                        add(Calendar.MONTH, -1)
                    })
                }) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Previous month"
                    )
                }

                Text(
                    text = monthFormat.format(currentMonth.time),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(onClick = {
                    onMonthChange((currentMonth.clone() as Calendar).apply {
                        add(Calendar.MONTH, 1)
                    })
                }) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Next month"
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.small))

            // Day headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.small))

            // Calendar grid
            daysInMonth.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    week.forEach { day ->
                        if (day != null) {
                            val isSelected = day.get(Calendar.DAY_OF_MONTH) == selectedDate.get(Calendar.DAY_OF_MONTH) &&
                                           day.get(Calendar.MONTH) == selectedDate.get(Calendar.MONTH) &&
                                           day.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
                            val isToday = day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                                         day.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                            val hasEvents = eventDays.contains(day.get(Calendar.DAY_OF_MONTH))

                            MiniDayCell(
                                day = day.get(Calendar.DAY_OF_MONTH),
                                isSelected = isSelected,
                                isToday = isToday,
                                hasEvents = hasEvents,
                                onClick = { onDateSelected(day) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniDayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> LocalAccentColor.current
            isToday -> LocalAccentColor.current.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "cellBg"
    )

    val textColor = when {
        isSelected -> Color.White
        isToday -> LocalAccentColor.current
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            if (hasEvents && !isSelected) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(LocalAccentColor.current)
                )
            }
        }
    }
}

@Composable
private fun EmptyCalendarState(
    selectedDate: Calendar,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    val isToday = remember(selectedDate) {
        val today = Calendar.getInstance()
        selectedDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
        selectedDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.EventBusy,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isToday) "No events today" else "No events",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = dateFormat.format(selectedDate.time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Tap + to add an event",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// Helper functions

private fun generateWeekDates(centerDate: Calendar): List<Calendar> {
    val dates = mutableListOf<Calendar>()
    val start = centerDate.clone() as Calendar
    start.add(Calendar.DAY_OF_MONTH, -14) // 2 weeks before

    repeat(35) { // 5 weeks total
        dates.add(start.clone() as Calendar)
        start.add(Calendar.DAY_OF_MONTH, 1)
    }

    return dates
}

private fun generateMonthDays(month: Calendar): List<Calendar?> {
    val days = mutableListOf<Calendar?>()
    val calendar = month.clone() as Calendar
    calendar.set(Calendar.DAY_OF_MONTH, 1)

    // Add empty slots for days before the first of the month
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
    repeat(firstDayOfWeek) {
        days.add(null)
    }

    // Add all days of the month
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    repeat(daysInMonth) { dayIndex ->
        val day = month.clone() as Calendar
        day.set(Calendar.DAY_OF_MONTH, dayIndex + 1)
        days.add(day)
    }

    // Pad to complete the last week
    while (days.size % 7 != 0) {
        days.add(null)
    }

    return days
}
