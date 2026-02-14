package com.example.smarty.ui.components

import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.animateCardTilt
import com.example.smarty.ui.animation.animatedCardTransform
import com.example.smarty.ui.animation.cardTilt3D
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Modern Calendar Event Card following the app's design system:
 * - Super-rounded corners (28dp) with floating soft shadow
 * - Spring-based press animation with scale + subtle rotation
 * - Swipe right to delete
 * - Clean visual hierarchy with date box and event details
 * - 3D lift effect on press
 */
@Composable
fun CalendarEventCard(
    event: CalendarEvent,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val shapes = LocalShapes.current
    val spacing = LocalSpacing.current

    // Press state for animations
    var isPressed by remember { mutableStateOf(false) }

    // Swipe state
    val swipeOffset = remember { Animatable(0f) }
    val swipeThreshold = remember { with(density) { 80.dp.toPx() } }
    val snapBackSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 800f)

    // Apple-style Card Transform (Scale + Rotation)
    val (scale, rotation) = animatedCardTransform(
        pressed = isPressed,
        index = index
    )

    // 3D Tilt Effect on Press
    val tilt = animateCardTilt(
        pressed = isPressed,
        pressedElevation = 2f
    )

    // Border color handling for swipe state
    val borderColor by animateColorAsState(
        targetValue = when {
            swipeOffset.value > swipeThreshold * 0.5f -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "border"
    )

    // Swipe indicator alpha
    val swipeAlpha by animateFloatAsState(
        targetValue = (abs(swipeOffset.value) / swipeThreshold).coerceIn(0f, 1f),
        animationSpec = tween(80),
        label = "swipeAlpha"
    )

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        // Swipe Background Layer - Delete
        if (swipeAlpha > 0f && swipeOffset.value > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shapes.cardMedium)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .scale(0.8f + swipeAlpha * 0.4f)
                )
            }
        }

        // Main Card Content
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .softCardShadow(shape = shapes.cardMedium)
                .graphicsLayer {
                    rotationZ = rotation
                    cameraDistance = 12f * density.density
                }
                .cardTilt3D(tilt)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClick()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset.value > swipeThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDelete()
                            }
                            coroutineScope.launch {
                                swipeOffset.animateTo(0f, snapBackSpec)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newOffset = (swipeOffset.value + dragAmount).coerceIn(0f, swipeThreshold * 1.5f)
                                swipeOffset.snapTo(newOffset)
                            }
                        }
                    )
                },
            shape = shapes.cardMedium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.default),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.medium)
            ) {
                // Date Box
                DateBox(
                    event = event,
                    modifier = Modifier.width(56.dp)
                )

                // Event Details
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Title
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Time Row
                    TimeRow(event = event)

                    // Location (if present)
                    event.location?.let { location ->
                        if (location.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = location.lowercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Status Indicators Column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Color indicator
                    event.color?.let { color ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                        )
                    }

                    // Privacy indicator
                    if (event.isEventPrivate) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.private_label),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Reminder indicator
                    event.reminderMinutes?.let {
                        Icon(
                            imageVector = Icons.Default.NotificationsNone,
                            contentDescription = stringResource(R.string.reminder),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Recurring indicator
                    if (event.isRecurring) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = stringResource(R.string.sync_calendar),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateBox(
    event: CalendarEvent,
    modifier: Modifier = Modifier
) {
    val shapes = LocalShapes.current
    val isToday = event.isToday()
    val isPast = event.isPast()
    val isHappening = event.isHappeningNow()

    val calendar = remember(event.startTime) {
        Calendar.getInstance().apply { timeInMillis = event.startTime }
    }
    val monthFormat = remember { SimpleDateFormat("MMM", Locale.getDefault()) }

    // Animate background color
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isHappening -> LocalAccentColor.current
            isToday -> LocalAccentColor.current.copy(alpha = 0.15f)
            isPast -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(200),
        label = "dateBoxBg"
    )

    val textColor = when {
        isHappening -> MaterialTheme.colorScheme.onPrimary
        isToday -> LocalAccentColor.current
        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(shapes.button)
            .background(backgroundColor)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = calendar.get(Calendar.DAY_OF_MONTH).toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        Text(
            text = monthFormat.format(calendar.time).lowercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun TimeRow(event: CalendarEvent) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val isHappening = event.isHappeningNow()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (event.isAllDay) Icons.Default.CalendarToday else Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isHappening) LocalAccentColor.current
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )

        val timeText = when {
            event.isAllDay -> stringResource(R.string.all_day)
            else -> {
                val startTime = timeFormat.format(Date(event.startTime)).lowercase()
                val endTime = timeFormat.format(Date(event.endTime)).lowercase()
                "$startTime - $endTime"
            }
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isHappening) FontWeight.Medium else FontWeight.Normal,
            color = if (isHappening) LocalAccentColor.current
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // "Now" indicator
        if (isHappening) {
            Surface(
                shape = LocalShapes.current.pill,
                color = LocalAccentColor.current.copy(alpha = 0.15f)
            ) {
                Text(
                    text = stringResource(R.string.now),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = LocalAccentColor.current,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
