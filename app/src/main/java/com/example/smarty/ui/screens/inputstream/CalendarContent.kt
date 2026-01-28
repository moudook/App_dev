package com.example.smarty.ui.screens.inputstream

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import java.text.SimpleDateFormat
import java.util.*

/**
 * Inline calendar content that displays in the main content area.
 *
 * This replaces the bottom sheet approach - calendar is shown in the same
 * layer as note cards, behind the gradient input field.
 * 
 * Similar structure to ChatHistoryContent for consistency.
 */

// Design System Colors for Calendar
private val CalendarAccent = Color(0xFF2979FF)
private val CalendarAccentLight = Color(0xFF5C9AFF)

@Composable
fun CalendarContent(
    events: List<CalendarEvent>,
    onEventClick: (CalendarEvent) -> Unit,
    onAddEvent: (Calendar) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    
    // Calendar state
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }

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
            event.startTime < dayEnd.timeInMillis && event.endTime >= dayStart.timeInMillis
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

    // Generate month days
    val daysInMonth = remember(currentMonth) {
        generateMonthDays(currentMonth)
    }

    val today = remember { Calendar.getInstance() }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    // Theme-aware colors
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Month Header with Navigation
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = monthFormat.format(currentMonth.time),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Previous Month
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentMonth = (currentMonth.clone() as Calendar).apply {
                                add(Calendar.MONTH, -1)
                            }
                        },
                        shape = CircleShape,
                        color = surfaceColor,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, // Creative: Back
                                contentDescription = "Previous",
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Next Month
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentMonth = (currentMonth.clone() as Calendar).apply {
                                add(Calendar.MONTH, 1)
                            }
                        },
                        shape = CircleShape,
                        color = surfaceColor,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward, // Creative: Forward
                                contentDescription = "Next",
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Add Event Button
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAddEvent(selectedDate)
                        },
                        shape = CircleShape,
                        color = CalendarAccent,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.AutoAwesome, // Creative: Burst
                                contentDescription = "Add Event",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Day Headers
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.sp
                        ),
                        color = textMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Calendar Grid
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                daysInMonth.chunked(7).forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        week.forEach { day ->
                            val cellModifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)

                            if (day != null) {
                                val isSelected = day.get(Calendar.DAY_OF_MONTH) == selectedDate.get(Calendar.DAY_OF_MONTH) &&
                                               day.get(Calendar.MONTH) == selectedDate.get(Calendar.MONTH) &&
                                               day.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
                                val isToday = day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                                             day.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                                val isWeekend = day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                                               day.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                                val hasEvents = monthEventDays.contains(day.get(Calendar.DAY_OF_MONTH))
                                val isCurrentMonth = day.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH)

                                InlineDayCell(
                                    day = day.get(Calendar.DAY_OF_MONTH),
                                    isSelected = isSelected,
                                    isToday = isToday,
                                    isWeekend = isWeekend,
                                    hasEvents = hasEvents,
                                    isCurrentMonth = isCurrentMonth,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedDate = day
                                    },
                                    modifier = cellModifier
                                )
                            } else {
                                Spacer(modifier = cellModifier)
                            }
                        }
                    }
                }
            }
        }

        // Selected Date Header
        item {
            val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
            Text(
                text = dateFormat.format(selectedDate.time),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // Events for Selected Date
        if (selectedDateEvents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty, // Creative: Waiting
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = textMuted.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "No events scheduled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Tap + to add an event",
                            style = MaterialTheme.typography.bodySmall,
                            color = CalendarAccent.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            items(selectedDateEvents, key = { it.id }) { event ->
                InlineEventCard(
                    event = event,
                    onClick = { onEventClick(event) }
                )
            }
        }

        // Bottom Spacer for input field
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun InlineDayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    isWeekend: Boolean,
    hasEvents: Boolean,
    isCurrentMonth: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryText = MaterialTheme.colorScheme.onBackground
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant

    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        !isCurrentMonth -> mutedText.copy(alpha = 0.3f)
        isToday -> CalendarAccent
        isWeekend -> mutedText
        else -> primaryText.copy(alpha = 0.9f)
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .then(
                when {
                    isSelected -> Modifier.background(CalendarAccent)
                    isToday || (isWeekend && isCurrentMonth) -> Modifier.drawBehind {
                        drawCircle(
                            color = mutedText.copy(alpha = 0.5f),
                            style = Stroke(
                                width = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                            )
                        )
                    }
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
                ),
                color = textColor
            )

            // Event indicator
            if (hasEvents && !isSelected) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(CalendarAccent)
                )
            }
        }
    }
}

@Composable
private fun InlineEventCard(
    event: CalendarEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            surfaceColor,
                            CalendarAccent.copy(alpha = 0.2f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Time Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bgColor.copy(alpha = 0.6f)
                    ) {
                        val timeText = if (event.isAllDay) "All Day" else {
                            "${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                        }
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = textPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Title
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Location
                    event.location?.let { location ->
                        if (location.isNotBlank()) {
                            Text(
                                text = location,
                                style = MaterialTheme.typography.bodySmall,
                                color = textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    CalendarAccent,
                                    CalendarAccentLight
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (event.isRecurring) Icons.Default.AllInclusive else Icons.Default.Timeline, // Creative: Loop/Journey
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun generateMonthDays(month: Calendar): List<Calendar?> {
    val days = mutableListOf<Calendar?>()
    val calendar = month.clone() as Calendar
    calendar.set(Calendar.DAY_OF_MONTH, 1)

    // Adjust for Monday start
    val firstDayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> 6
        else -> calendar.get(Calendar.DAY_OF_WEEK) - 2
    }
    
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
