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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.CalendarEmptyState
import com.example.smarty.ui.components.CalendarEventCard
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PREMIUM DARK CALENDAR SCREEN
 * 
 * Design: Always-dark theme with Electric Blue accent (matches app theme)
 * - Deep dark background for immersive experience
 * - Electric Blue accent for selections (consistent with Jarvis design)
 * - Dashed circles for today/weekends
 * - Gradient event cards
 * ═══════════════════════════════════════════════════════════════════════════════
 */

// Design System Colors for Calendar
// Removed hardcoded CalendarAccent/Light in favor of LocalAccentColor.current

// Theme-aware colors - will be accessed via composable functions
@Composable
private fun calendarBackground() = MaterialTheme.colorScheme.background

@Composable
private fun calendarSurface() = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun calendarTextPrimary() = MaterialTheme.colorScheme.onBackground

@Composable
private fun calendarTextMuted() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Sync status for calendar synchronization indicator
 */
enum class SyncStatus {
    Idle,      // No sync in progress
    Syncing,   // Sync in progress
    Success,   // Last sync succeeded
    Error      // Last sync failed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    events: List<CalendarEvent>,
    onBackClick: () -> Unit,
    onAddEvent: (Calendar) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    syncStatus: SyncStatus = SyncStatus.Idle,
    onSyncClick: () -> Unit = {},
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val accentColor = LocalAccentColor.current
    val accentLight = accentColor.copy(alpha = 0.7f)

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

    // Events for month (for indicators) - tracks both event days and recurring event days
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

    // Recurring event days (for special indicator on day cells)
    val recurringEventDays = remember(events, currentMonth) {
        val start = currentMonth.clone() as Calendar
        start.set(Calendar.DAY_OF_MONTH, 1)
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)

        val end = start.clone() as Calendar
        end.add(Calendar.MONTH, 1)

        events.filter {
            it.startTime >= start.timeInMillis &&
            it.startTime < end.timeInMillis &&
            it.isRecurring
        }.map { event ->
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            cal.get(Calendar.DAY_OF_MONTH)
        }.toSet()
    }

    // Generate month days
    val daysInMonth = remember(currentMonth) {
        generateMonthDays(currentMonth)
    }

    // Theme-aware colors
    val surfaceColor = calendarSurface()
    val textPrimary = calendarTextPrimary()
    val textMuted = calendarTextMuted()

    // Intercept system back button
    androidx.activity.compose.BackHandler(onBack = onBackClick)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ═══════════════════════════════════════════════════════════════════
            // TOP NAVIGATION BAR
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {


                // Right side buttons - Sync + Add
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sync Button with status indicator
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSyncClick()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = surfaceColor,
                            contentColor = when (syncStatus) {
                                SyncStatus.Success -> accentColor
                                SyncStatus.Error -> MaterialTheme.colorScheme.error
                                else -> textPrimary
                            }
                        ),
                        modifier = Modifier.size(44.dp),
                        enabled = syncStatus != SyncStatus.Syncing
                    ) {
                        when (syncStatus) {
                            SyncStatus.Syncing -> {
                                com.example.smarty.ui.components.CalmThinkingDots(
                                    dotSize = 3.dp
                                )
                            }
                            SyncStatus.Success -> {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = stringResource(R.string.sync_completed),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            SyncStatus.Error -> {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = stringResource(R.string.sync_failed),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            SyncStatus.Idle -> {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.sync_calendar),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Add Event Button
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAddEvent(selectedDate)
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = surfaceColor,
                            contentColor = textPrimary
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_event),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // TITLE SECTION
            // ═══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.my),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    ),
                    color = textPrimary
                )
                Text(
                    text = stringResource(R.string.schedule_),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp
                    ),
                    color = textPrimary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════════
            // MONTH HEADER WITH NAVIGATION
            // ═══════════════════════════════════════════════════════════════════
            val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = monthFormat.format(currentMonth.time).lowercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
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
                                Icons.Default.ChevronLeft,
                                contentDescription = stringResource(R.string.previous),
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
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.next),
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════════
            // DAY HEADERS (MON, TUE, etc.)
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    stringResource(R.string.mon),
                    stringResource(R.string.tue),
                    stringResource(R.string.wed),
                    stringResource(R.string.thu),
                    stringResource(R.string.fri),
                    stringResource(R.string.sat),
                    stringResource(R.string.sun)
                ).forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = textMuted.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ═══════════════════════════════════════════════════════════════════
            // CALENDAR GRID
            // ═══════════════════════════════════════════════════════════════════
            val today = remember { Calendar.getInstance() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                                val hasRecurringEvents = recurringEventDays.contains(day.get(Calendar.DAY_OF_MONTH))
                                val isCurrentMonth = day.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH)

                                PremiumDayCell(
                                    day = day.get(Calendar.DAY_OF_MONTH),
                                    isSelected = isSelected,
                                    isToday = isToday,
                                    isWeekend = isWeekend,
                                    hasEvents = hasEvents,
                                    hasRecurringEvents = hasRecurringEvents,
                                    isCurrentMonth = isCurrentMonth,
                                    accentColor = accentColor,
                                    accentLight = accentLight,
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

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════════
            // EVENT LIST (Bottom Section)
            // ═══════════════════════════════════════════════════════════════════
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    com.example.smarty.ui.components.CalmThinkingDots(
                        modifier = Modifier.padding(top = 40.dp)
                    )
                }
            } else if (selectedDateEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    com.example.smarty.ui.components.CalendarEmptyState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = selectedDateEvents,
                        key = { it.id }
                    ) { event ->
                        PremiumEventCard(
                            event = event,
                            accentColor = accentColor,
                            accentLight = accentLight,
                            onClick = { onEventClick(event) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PREMIUM DAY CELL (Matching Reference Image)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun PremiumDayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    isWeekend: Boolean,
    hasEvents: Boolean,
    hasRecurringEvents: Boolean = false,
    isCurrentMonth: Boolean,
    accentColor: Color,
    accentLight: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryText = MaterialTheme.colorScheme.onBackground
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant

    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        !isCurrentMonth -> mutedText.copy(alpha = 0.3f)
        isToday -> accentColor
        isWeekend -> mutedText
        else -> primaryText.copy(alpha = 0.9f)
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .then(
                when {
                    isSelected -> Modifier.background(accentColor)
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

            // Event indicator - different style for recurring vs regular events
            if (hasEvents && !isSelected) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Primary event dot
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    // Recurring indicator (second dot for recurring events)
                    if (hasRecurringEvents) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(accentLight)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PREMIUM EVENT CARD (Gradient Style from Reference)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun PremiumEventCard(
    event: CalendarEvent,
    accentColor: Color,
    accentLight: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val isHappening = event.isHappeningNow()
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            surfaceColor,
                            accentColor.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
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
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor.copy(alpha = 0.6f)
                    ) {
                        val timeText = if (event.isAllDay) stringResource(R.string.all_day) else {
                            "${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                        }.lowercase()
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
                            color = textPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Title with recurring indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = event.title.lowercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp
                            ),
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Recurring indicator
                        if (event.isRecurring) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = "recurring_event",
                                modifier = Modifier.size(14.dp),
                                tint = accentColor
                            )
                        }
                    }

                    event.location?.let { location ->
                        if (location.isNotBlank()) {
                            Text(
                                text = location.lowercase(),
                                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                                color = textMuted.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Icon/Decoration on right
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    accentColor,
                                    accentLight
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isHappening) Icons.Default.PlayCircleFilled else if (event.isRecurring) Icons.Default.Repeat else Icons.Default.Event,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

private fun generateMonthDays(month: Calendar): List<Calendar?> {
    val days = mutableListOf<Calendar?>()
    val calendar = month.clone() as Calendar
    calendar.set(Calendar.DAY_OF_MONTH, 1)

    // Adjust for Monday start (1 = Monday, 7 = Sunday)
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
