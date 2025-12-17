package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.animateCardTilt
import com.example.smarty.ui.animation.animatedCardTransform
import com.example.smarty.ui.animation.cardTilt3D
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.util.ContentTypeDetector
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Modern Soft Minimalist NoteCard with smooth interactions:
 * - Super-rounded corners (28dp) with floating soft shadow
 * - Spring-based press animation with scale + subtle rotation
 * - Swipe right: Archive (main view) or Delete (archive view)
 * - Swipe left: Open todos (main view) or Unarchive (archive view)
 * - Clean border-free design with selection state highlight
 * - 3D lift effect on press
 */
@Composable
fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onOpenTodo: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    isArchiveView: Boolean = false,  // Controls swipe behavior context
    onArchive: (() -> Unit)? = null,  // Archive action for main view (swipe right)
    onUnarchive: (() -> Unit)? = null,  // Unarchive action for archive view (swipe left)
    isSelected: Boolean = false,  // Multi-select: is this card selected
    isSelectionMode: Boolean = false,  // Multi-select: is selection mode active
    onLongPress: () -> Unit = {},  // Multi-select: long press to enter selection mode
    onPlayYouTube: (String) -> Unit = {}  // Callback to play YouTube video
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Press state for animations
    var isPressed by remember { mutableStateOf(false) }

    // Swipe state
    val swipeOffset = remember { Animatable(0f) }
    val swipeThreshold = remember { with(density) { 60.dp.toPx() } }  // Increased slightly for better feel
    val snapBackSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 800f)

    // Apple-style Card Transform (Scale + Rotation)
    val (scale, rotation) = animatedCardTransform(
        pressed = isPressed,
        index = index
    )

    // 3D Tilt Effect on Press
    val tilt = animateCardTilt(
        pressed = isPressed,
        pressedElevation = 2f // More subtle in the new design
    )

    // Border color handling
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> LocalAccentColor.current
            note.processingStatus == ProcessingStatus.PROCESSING -> LocalAccentColor.current.copy(alpha = 0.5f)
            swipeOffset.value > swipeThreshold * 0.5f -> {
                if (isArchiveView) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                else SafetyOrange.copy(alpha = 0.5f)
            }
            swipeOffset.value < -swipeThreshold * 0.5f -> LocalAccentColor.current.copy(alpha = 0.5f)
            else -> Color.Transparent // Clean look by default
        },
        animationSpec = tween(200),
        label = "border"
    )

    // Swipe indicator alpha
    val swipeAlpha by animateFloatAsState(
        targetValue = (abs(swipeOffset.value) / swipeThreshold).coerceIn(0f, 1f),
        animationSpec = tween(100),
        label = "swipeAlpha"
    )

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        // Swipe Background Layer
        if (swipeAlpha > 0f) {
            val isSwipeRight = swipeOffset.value > 0
            val color = if (isSwipeRight) {
                if (isArchiveView) MaterialTheme.colorScheme.error else SafetyOrange
            } else {
                LocalAccentColor.current
            }

            val icon = if (isSwipeRight) {
                if (isArchiveView) Icons.Default.Delete else Icons.Default.Archive
            } else {
                if (isArchiveView) Icons.Default.Unarchive else Icons.Default.Checklist
            }

            val shapes = LocalShapes.current
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shapes.cardMedium)
                    .background(color),
                contentAlignment = if (isSwipeRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White, // Always white on colored background
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
                .softCardShadow(shape = LocalShapes.current.cardMedium)
                .graphicsLayer {
                    rotationZ = rotation
                    cameraDistance = 12f * density.density
                }
                .cardTilt3D(tilt)
                .pointerInput(isSelectionMode) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClick()
                        }
                    )
                }
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (swipeOffset.value > swipeThreshold) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        swipeOffset.animateTo(0f, snapBackSpec)
                                        if (isArchiveView) onDelete() else onArchive?.invoke()
                                    } else if (swipeOffset.value < -swipeThreshold) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        swipeOffset.animateTo(0f, snapBackSpec)
                                        if (isArchiveView) onUnarchive?.invoke() else onOpenTodo()
                                    } else {
                                        swipeOffset.animateTo(0f, snapBackSpec)
                                    }
                                }
                            },
                            onDragCancel = {
                                coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                coroutineScope.launch {
                                    swipeOffset.snapTo(swipeOffset.value + dragAmount)
                                }
                            }
                        )
                    }
                },
            shape = LocalShapes.current.cardMedium,
            color = MaterialTheme.colorScheme.surface, // Solid white/dark surface
            shadowElevation = 0.dp, // Disable built-in harsh shadow -> using softCardShadow instead
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(2.dp, LocalAccentColor.current)
            } else null // Clean border-free look by default
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ComponentSpacing.cardPadding) // 24dp for airy feel
            ) {
                // Header: Title + Category + Indicators combined
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // Left: Title and Icon
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Type Icon (Subtler)
                        val iconTint = getNoteTypeColor(note.type)
                        Icon(
                            imageVector = getNoteTypeIcon(note.type),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier
                                .size(20.dp)
                                .offset(y = 2.dp) // Optical alignment with text
                        )

                        Column {
                            // Title
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Status / Privacy Indicators (Inline with title area)
                            if (note.processingStatus == ProcessingStatus.PROCESSING || note.isFullPrivacy || note.excludeFromAiChat) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (note.processingStatus == ProcessingStatus.PROCESSING) {
                                        ProcessingIndicator(modifier = Modifier.size(12.dp))
                                        Text(
                                            text = "Processing",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    if (note.isFullPrivacy) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: Timestamp or Category (Minimal pill)
                    note.categoryName?.let { category ->
                        CategoryChip(
                            name = category,
                            isNew = note.processingStatus == ProcessingStatus.COMPLETED
                        )
                    }
                }

                // Body Content
                val displayText = note.summary ?: note.content
                if (displayText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 30.dp) // Indent to align with text above
                    )
                }

                // YouTube Button - Check if note contains a YouTube video ID
                val youtubeId = remember(note) {
                    note.sourceUrl?.let { ContentTypeDetector.extractYouTubeId(it) }
                        ?: ContentTypeDetector.extractYouTubeId(note.content)
                        ?: note.summary?.let { ContentTypeDetector.extractYouTubeId(it) }
                }

                if (youtubeId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.padding(start = 30.dp)) {
                        YouTubePlayButton(
                            onClick = {
                                // Open YouTube app or website directly
                                val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl))
                                context.startActivity(intent)
                            },
                            isCompact = true
                        )
                    }
                }
            }
        }
    }
}


/**
 * Category chip with pill shape
 */
@Composable
fun CategoryChip(
    name: String,
    isNew: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = LocalShapes.current.pill,
        color = if (isNew) LocalAccentColor.current.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = if (isNew) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

/**
 * Processing indicator with pulsing animation
 */
@Composable
private fun ProcessingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "processingAlpha"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(LocalAccentColor.current.copy(alpha = alpha))
    )
}
