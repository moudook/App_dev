package com.example.smarty.features.notes.ui.inputstream

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.theme.appleShapes
import com.example.smarty.ui.theme.appleSpacing
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.CalendarEmptyState
import com.example.smarty.ui.components.CalendarLoadingState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ── S-TIER PHYSICS ENGINE ───────────────────────────────────────────
@Composable
fun Modifier.squishClick(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "squish",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clickable(
            interactionSource = interactionSource,
            indication = null, // No cheap Android ripple!
            onClick = onClick,
        )
}

// ── MAIN CALENDAR REIMAGINED ────────────────────────────────────────
@Composable
fun CalendarContent(
    events: List<CalendarEvent>,
    activeTimers: List<SmartyTimer> = emptyList(),
    onEventClick: (CalendarEvent) -> Unit,
    onAddEvent: (Calendar) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit = {},
    onCancelTimer: (SmartyTimer) -> Unit = {},
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onCreateEvent: (
        (
            title: String,
            description: String?,
            startTime: Long,
            endTime: Long,
            isAllDay: Boolean,
        ) -> Unit
    )? = null,
) {
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val bgColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val accentColor = LocalAccentColor.current // Uses Smarty's dynamic/selected theme color

    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    var isCreatingEvent by remember { mutableStateOf(false) }

    // Generate current week mathematically for the sliding track
    val weekDays =
        remember(selectedDate) {
            val cal = selectedDate.clone() as Calendar
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            List(7) {
                val day = cal.clone() as Calendar
                cal.add(Calendar.DAY_OF_YEAR, 1)
                day
            }
        }

    val selectedDateEvents = remember(events, selectedDate) { filterEventsForDate(events, selectedDate) }

    Box(modifier = modifier.fillMaxSize().background(bgColor)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 180.dp, bottom = 120.dp), // Massive top padding for Parallax Header
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── 1. THE SLIDING PILL WEEK TRACK ──
            item {
                STierSlidingWeekTrack(
                    weekDays = weekDays,
                    selectedDate = selectedDate,
                    onDateSelect = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selectedDate = it
                    },
                    accentColor = accentColor,
                    isDark = isDark,
                )
                Spacer(Modifier.height(32.dp))
            }

            // ── TIMERS ──
            if (activeTimers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.appleSpacing.large)) {
                        Text(
                            "ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.2.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        activeTimers.forEach { timer ->
                            STierLiveTimerCard(
                                timer = timer,
                                accentColor = accentColor,
                                surfaceColor = surfaceColor,
                                onCancel = { onCancelTimer(timer) },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // ── 2. THE TIMELINE & BENTO CARDS ──
            item {
                Text(
                    text = "Schedule",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = MaterialTheme.appleSpacing.large),
                )
                Spacer(Modifier.height(16.dp))
            }

            // INLINE EVENT CREATOR
            item {
                AnimatedVisibility(
                    visible = isCreatingEvent && onCreateEvent != null,
                    enter = expandVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeOut(),
                ) {
                    Box(modifier = Modifier.padding(horizontal = MaterialTheme.appleSpacing.large)) {
                        com.example.smarty.ui.components.TimeEditor(
                            onSave = { h, m, t ->
                                val durationMs = (h * 60 + m) * 60 * 1000L
                                val startTime = selectedDate.timeInMillis
                                val endTime = startTime + durationMs
                                onCreateEvent?.invoke(t, null, startTime, endTime, false)
                                isCreatingEvent = false
                            },
                        )
                    }
                }
            }

            if (isLoading) {
                item {
                    CalendarLoadingState(
                        count = 3,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
            } else if (selectedDateEvents.isEmpty() && !isCreatingEvent) {
                item {
                    CalendarEmptyState(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
                }
            } else {
                itemsIndexed(selectedDateEvents, key = { _, e -> e.id }) { index, event ->
                    BentoTimelineNode(
                        event = event,
                        isLast = index == selectedDateEvents.size - 1,
                        isDark = isDark,
                        accentColor = accentColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onEventClick(event)
                        },
                    )
                }
            }
        }

        // ── 3. THE PARALLAX DYNAMIC HEADER ──
        // Reads scroll state and dynamically shrinks/fades the header!
        val scrollOffset = remember { derivedStateOf { listState.firstVisibleItemScrollOffset } }
        val headerScale by animateFloatAsState(
            targetValue = if (scrollOffset.value > 50) 0.85f else 1f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
            label = "headerScale",
        )
        val headerAlpha by animateFloatAsState(
            targetValue = if (scrollOffset.value > 150) 0f else 1f,
            animationSpec = tween(200),
            label = "headerAlpha",
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(contentPadding.calculateTopPadding() + 160.dp)
                    .background(Brush.verticalGradient(listOf(bgColor, bgColor.copy(alpha = 0f))))
                    .graphicsLayer {
                        translationY = -scrollOffset.value * 0.4f // Pure Parallax!
                        scaleX = headerScale
                        scaleY = headerScale
                        alpha = headerAlpha
                    }.padding(horizontal = MaterialTheme.appleSpacing.large)
                    .padding(bottom = 32.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(selectedDate.time).uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = accentColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = SimpleDateFormat("EEEE", Locale.getDefault()).format(selectedDate.time),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = textColor,
                        letterSpacing = (-1).sp,
                    )
                }

                // Floating Action Button fused into the header
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(MaterialTheme.appleShapes.medium) // Squircle FAB
                            .background(if (isCreatingEvent) Color.Gray.copy(0.3f) else accentColor)
                            .squishClick {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                if (onCreateEvent != null) {
                                    isCreatingEvent = !isCreatingEvent
                                    if (!isCreatingEvent) {
                                        keyboardController?.hide()
                                    }
                                } else {
                                    onAddEvent(selectedDate)
                                }
                            },
                ) {
                    Icon(
                        if (isCreatingEvent) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = "Add",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

// ── S-TIER COMPONENT: THE SLIDING PILL TRACK ────────────────────────
@Composable
fun STierSlidingWeekTrack(
    weekDays: List<Calendar>,
    selectedDate: Calendar,
    onDateSelect: (Calendar) -> Unit,
    accentColor: Color,
    isDark: Boolean,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val trackPadding = 24.dp
    val availableWidth = screenWidth - (trackPadding * 2)
    val dayWidth = availableWidth / 7

    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val selectedIndex =
        weekDays
            .indexOfFirst {
                it.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR) &&
                    it.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
            }.coerceAtLeast(0)

    // Mathematically pure sliding animation (Zero recomposition on sliding items)
    val slidingOffset by animateDpAsState(
        targetValue = dayWidth * selectedIndex,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 350f),
        label = "slidingPill",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = trackPadding)
                .height(72.dp)
                .clip(MaterialTheme.appleShapes.large)
                .background(surfaceColor)
                .border(1.dp, if (isDark) Color.White.copy(0.05f) else Color.Black.copy(0.03f), MaterialTheme.appleShapes.large),
    ) {
        // The Physical Background Pill that slides
        Box(
            modifier =
                Modifier
                    .offset(x = slidingOffset)
                    .width(dayWidth)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(MaterialTheme.appleShapes.medium)
                    .background(accentColor),
        )

        // The text layers sitting on top
        Row(modifier = Modifier.fillMaxSize()) {
            weekDays.forEachIndexed { index, day ->
                val isSelected = index == selectedIndex
                val itemTextColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else onSurfaceColor.copy(alpha = 0.6f),
                    animationSpec = tween(200),
                    label = "textColor",
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .width(dayWidth)
                            .fillMaxHeight()
                            .squishClick { onDateSelect(day) },
                ) {
                    Text(
                        text = SimpleDateFormat("E", Locale.getDefault()).format(day.time).take(1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = itemTextColor.copy(alpha = if (isSelected) 0.8f else 0.5f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = day.get(Calendar.DAY_OF_MONTH).toString(),
                        fontSize = 18.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = itemTextColor,
                    )
                }
            }
        }
    }
}

// ── S-TIER COMPONENT: BENTO TIMELINE NODE ───────────────────────────
@Composable
fun BentoTimelineNode(
    event: CalendarEvent,
    isLast: Boolean,
    isDark: Boolean,
    accentColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onClick: () -> Unit,
) {
    val isHappeningNow = event.isHappeningNow()
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    // Breathing Glow for "Now"
    val glow by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = if (isHappeningNow) 0.4f else 0f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.appleSpacing.large)
                .height(IntrinsicSize.Min), // Matches row height to content
    ) {
        // Left Timeline
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(50.dp),
        ) {
            Text(
                text = timeFormat.format(Date(event.startTime)).replace(" AM", "am").replace(" PM", "pm"),
                fontSize = 12.sp,
                fontWeight = if (isHappeningNow) FontWeight.Bold else FontWeight.Medium,
                color = if (isHappeningNow) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // Connected line with glowing dot
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .width(2.dp)
                        .background(if (isDark) Color.White.copy(0.1f) else Color.Black.copy(0.05f)),
            ) {
                if (isHappeningNow) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .offset(x = (-3).dp, y = 10.dp) // Float the dot
                                .clip(CircleShape)
                                .background(accentColor)
                                .border(2.dp, accentColor.copy(alpha = glow), CircleShape),
                    )
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Right Bento Card
        Surface(
            shape = MaterialTheme.appleShapes.large,
            color = surfaceColor,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = if (isLast) 0.dp else 20.dp)
                    .squishClick(onClick = onClick)
                    .graphicsLayer {
                        shadowElevation = if (isDark) 0f else 12.dp.toPx()
                        ambientShadowColor = Color.Black.copy(0.03f)
                        spotShadowColor = Color.Black.copy(0.05f)
                    }.border(
                        width = 1.dp,
                        color =
                            if (isHappeningNow) {
                                accentColor.copy(alpha = 0.5f + glow)
                            } else if (isDark) {
                                Color.White.copy(0.05f)
                            } else {
                                Color.Transparent
                            },
                        shape = MaterialTheme.appleShapes.large,
                    ),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.appleSpacing.large)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHappeningNow) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = event.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val desc = event.description
                if (!desc.isNullOrBlank() || !event.location.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))

                    if (!desc.isNullOrBlank()) {
                        Text(
                            text = desc,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Location Pill inside the bento
                    val loc = event.location
                    if (!loc.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .background(if (isDark) Color.White.copy(0.05f) else Color.Black.copy(0.05f), MaterialTheme.appleShapes.small)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.LocationOn,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(loc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ── S-TIER LIVE ACTIVITY TIMER (FROM PREVIOUS ITERATION) ────────────
@Composable
fun STierLiveTimerCard(
    timer: SmartyTimer,
    accentColor: Color,
    surfaceColor: Color,
    onCancel: () -> Unit,
) {
    var ticks by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (true) {
            ticks = System.currentTimeMillis()
            delay(1000)
        }
    }

    val timeRemaining = if (!timer.isAlarm) (timer.triggerTime - ticks).coerceAtLeast(0) else 0L
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val statusText =
        if (timer.isAlarm) {
            timeFormat.format(Date(timer.triggerTime)).lowercase()
        } else {
            val minutes = timeRemaining / 60000
            val seconds = (timeRemaining % 60000) / 1000
            String.format("%d:%02d", minutes, seconds)
        }

    Surface(
        shape = MaterialTheme.appleShapes.large,
        color = surfaceColor,
        modifier =
            Modifier.fillMaxWidth().graphicsLayer {
                shadowElevation = 12.dp.toPx()
                ambientShadowColor = accentColor.copy(alpha = 0.4f)
                spotShadowColor = accentColor.copy(alpha = 0.4f)
            },
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.appleSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val infiniteTransition = rememberInfiniteTransition()
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
            )
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accentColor.copy(alpha = alpha)))

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(timer.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    statusText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .squishClick(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.StopCircle,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

// ============ Helper Functions ============

private fun isSameDay(
    cal1: Calendar,
    cal2: Calendar,
): Boolean = cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)

private fun isSameDay(
    cal: Calendar,
    timestamp: Long,
): Boolean {
    val other = Calendar.getInstance().apply { timeInMillis = timestamp }
    return isSameDay(cal, other)
}

private fun filterEventsForDate(
    events: List<CalendarEvent>,
    date: Calendar,
): List<CalendarEvent> {
    val dayStart = date.clone() as Calendar
    dayStart.set(Calendar.HOUR_OF_DAY, 0)
    dayStart.set(Calendar.MINUTE, 0)
    dayStart.set(Calendar.SECOND, 0)
    dayStart.set(Calendar.MILLISECOND, 0)

    val dayEnd = dayStart.clone() as Calendar
    dayEnd.add(Calendar.DAY_OF_MONTH, 1)

    return events
        .filter { event ->
            event.startTime < dayEnd.timeInMillis && event.endTime >= dayStart.timeInMillis
        }.sortedBy { it.startTime }
}

