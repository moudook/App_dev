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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.core.domain.model.getAttachmentCount
import com.example.smarty.core.domain.model.getAttachments
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.animation.animateCardTilt
import com.example.smarty.ui.animation.animatedCardTransform
import com.example.smarty.ui.animation.cardTilt3D
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.core.common.util.ContentTypeDetector
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Cached date formatters — NoteCards render in lists so this avoids creating new objects per card
private val dayNameFormat by lazy { SimpleDateFormat("EEEE", Locale.getDefault()) }
private val shortDateFormat by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/**
 * Modern Soft Minimalist NoteCard with smooth interactions:
 * - Redesigned to match premium UI with pills and cleaner topography.
 * - Super-rounded corners (28dp) with floating soft shadow
 * - Spring-based press animation with scale + subtle rotation
 * - Swipe right: Archive (main view) or Delete (archive view)
 * - Swipe left: Open todos (main view) or Unarchive (archive view)
 * - Monochrome Aesthetic Update: Uses Black/White instead of Blue
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onOpenTodo: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    isArchiveView: Boolean = false,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    searchQuery: String? = null,
    isNewlyProcessed: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Date formatter
    val dateString = remember(note.createdAt) {
        val now = System.currentTimeMillis()
        val diff = now - note.createdAt
        val days = diff / (1000 * 60 * 60 * 24)

        when {
            days == 0L -> context.getString(R.string.today)
            days == 1L -> context.getString(R.string.yesterday)
            days < 7 -> dayNameFormat.format(Date(note.createdAt))
            else -> shortDateFormat.format(Date(note.createdAt))
        }
    }

    // Press state for animations
    var isPressed by remember { mutableStateOf(false) }

    // Swipe state
    val swipeOffset = remember { Animatable(0f) }
    val swipeThreshold = remember { with(density) { 48.dp.toPx() } }
    val swipeActivationThreshold = remember { with(density) { 30.dp.toPx() } }
    var swipeActivated by remember { mutableStateOf(false) }
    var thresholdReached by remember { mutableStateOf(false) }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var actionInProgress by remember { mutableStateOf(false) }
    val snapBackSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 800f)

    // Animation transforms
    val (scale, rotation) = animatedCardTransform(pressed = isPressed, index = index)
    val tilt = animateCardTilt(pressed = isPressed, pressedElevation = 2f)

    // Monochrome Aesthetic: Black/White instead of Blue
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accentColor = rememberMonochromeAccent()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Border color
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> accentColor
            note.processingStatus == ProcessingStatus.PROCESSING -> accentColor.copy(alpha = 0.5f)
            swipeOffset.value > swipeThreshold * 0.5f -> if (isArchiveView) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else onSurfaceVariant.copy(alpha = 0.7f)
            swipeOffset.value < -swipeThreshold * 0.5f -> accentColor.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(AnimationDuration.fast),
        label = "border"
    )

    val swipeAlpha by remember { derivedStateOf { (abs(swipeOffset.value) / swipeThreshold).coerceIn(0f, 1f) } }

    Box(modifier = modifier.fillMaxWidth()) {
        // Swipe Background
        if (swipeAlpha > 0f) {
            val isSwipeRight = swipeOffset.value > 0
            val color = if (isSwipeRight) (if (isArchiveView) MaterialTheme.colorScheme.error else onSurfaceVariant) else accentColor
            val icon = if (isSwipeRight) (if (isArchiveView) Icons.Default.DeleteOutline else Icons.Default.Archive) else (if (isArchiveView) Icons.Default.Unarchive else Icons.Default.CheckCircle)

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(LocalShapes.current.cardMedium)
                    .background(color),
                contentAlignment = if (isSwipeRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // Decorative icon - swipe action indicator
                    tint = if (isSwipeRight && isArchiveView) Color.White else if (isDark) Color.Black else Color.White, // High contrast for monochrome bg
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .scale(0.8f + swipeAlpha * 0.4f)
                )
            }
        }

        var cardOffsetInWindow by remember { mutableStateOf(Offset.Zero) }

        // Main Card Surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardOffsetInWindow = coordinates.positionInWindow()
                }
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .softCardShadow(shape = LocalShapes.current.cardMedium)
                .graphicsLayer {
                    rotationZ = rotation
                    cameraDistance = 12f * density.density
                    scaleX = scale
                    scaleY = scale
                }
                .cardTilt3D(tilt)
                .pointerInput(isSelectionMode) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            try {
                                awaitRelease()
                            } finally {
                                isPressed = false
                            }
                        },
                        onLongPress = { touchOffset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Provide absolute position in window
                            onLongPress(cardOffsetInWindow + touchOffset)
                        },
                        onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
                    )
                }
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (actionInProgress) {
                                    coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }
                                    swipeActivated = false; accumulatedDrag = 0f; return@detectHorizontalDragGestures
                                }
                                if (swipeActivated && abs(swipeOffset.value) > swipeThreshold) {
                                    actionInProgress = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (swipeOffset.value > 0) { if (isArchiveView) onDelete() else onArchive?.invoke() }
                                    else { if (isArchiveView) onUnarchive?.invoke() else onOpenTodo() }
                                    coroutineScope.launch { swipeOffset.snapTo(0f); actionInProgress = false }
                                } else {
                                    coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }
                                }
                                swipeActivated = false; thresholdReached = false; accumulatedDrag = 0f
                            },
                            onDragCancel = { coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }; swipeActivated = false; thresholdReached = false; accumulatedDrag = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                accumulatedDrag += dragAmount
                                // Clamp the drag to prevent excessive movement
                                val maxDrag = swipeThreshold * 1.5f
                                accumulatedDrag = accumulatedDrag.coerceIn(-maxDrag, maxDrag)

                                if (!swipeActivated && abs(accumulatedDrag) > swipeActivationThreshold) {
                                    swipeActivated = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                // Haptic tick when crossing the full threshold
                                if (swipeActivated) {
                                    val isOverThreshold = abs(accumulatedDrag) > swipeThreshold
                                    if (isOverThreshold && !thresholdReached) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        thresholdReached = true
                                    } else if (!isOverThreshold && thresholdReached) {
                                        thresholdReached = false
                                    }

                                    coroutineScope.launch { swipeOffset.snapTo(accumulatedDrag) }
                                }
                            }
                        )
                    }
                },
            shape = LocalShapes.current.cardMedium,
            color = animateColorAsState(
                targetValue = if (isSelected) accentColor.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
                label = "cardBackground"
            ).value,
            shadowElevation = 0.dp,
            border = BorderStroke(
                width = animateDpAsState(
                    targetValue = if (isSelected) 1.5.dp else 0.5.dp,
                    animationSpec = tween(AnimationDuration.fast),
                    label = "borderWidth"
                ).value,
                color = borderColor
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Header Row (Title + Date)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        val titleStyle = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = (-0.5).sp,
                            lineHeight = 24.sp
                        )

                        // Title container
                        Box(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            if (searchQuery.isNullOrBlank()) {
                                Text(
                                    text = note.title.ifBlank { stringResource(R.string.untitled_note) },
                                    style = titleStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                HighlightedText(
                                    text = note.title.ifBlank { stringResource(R.string.untitled_note) },
                                    query = searchQuery,
                                    style = titleStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Date Label & Unread Indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (!note.isViewed && !isSelectionMode) {
                                NewNoteIndicatorDot(
                                    isVisible = true,
                                    modifier = Modifier.size(8.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Text(
                                text = dateString,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Insight / Summary Snippet (Ideation Integration)
                    // Prioritize "Why Saved" as it represents the user's specific intent
                    val insightText = note.whySaved?.takeIf { it.isNotBlank() }
                        ?: note.summary?.takeIf { it.isNotBlank() && !it.startsWith("analyzing_section") }

                    if (insightText != null) {
                         val bodyStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.1.sp,
                            fontWeight = FontWeight.Normal
                        )
                        val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

                        if (searchQuery.isNullOrBlank()) {
                            Text(
                                text = insightText,
                                style = bodyStyle,
                                color = bodyColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            HighlightedText(
                                text = insightText,
                                query = searchQuery,
                                style = bodyStyle,
                                color = bodyColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 2. Pills Layout (Updated to Monochrome)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // AI Generation Pill
                        if (note.isAiCreated) {
                            NoteCardPill(
                                text = stringResource(R.string.smarty_label),
                                icon = Icons.Default.AutoAwesome,
                                backgroundColor = Color.Black.copy(alpha = 0.05f),
                                contentColor = Color.Black,
                                darkBackgroundColor = Color.White.copy(alpha = 0.1f),
                                darkContentColor = Color.White
                            )
                        }

                        // Category Pill (Filter)
                        note.categoryName?.let { category ->
                            NoteCardPill(
                                text = category.lowercase(),
                                icon = Icons.AutoMirrored.Filled.Label,
                                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                darkBackgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                darkContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Attachments Pills
                        val attachments = note.getAttachments()
                        val hasAudio = note.type == NoteType.AUDIO || attachments.any { it.mimeType.startsWith("audio/") }
                        val hasFiles = attachments.isNotEmpty() && !hasAudio // Simplification

                        if (hasFiles) {
                            NoteCardPill(
                                text = stringResource(R.string.files),
                                icon = Icons.Default.Description,
                                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                darkBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                darkContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (hasAudio) {
                            NoteCardPill(
                                text = stringResource(R.string.audio_label),
                                icon = Icons.Default.Audiotrack,
                                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                darkBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                darkContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCardPill(
    text: String,
    icon: ImageVector,
    backgroundColor: Color,
    contentColor: Color,
    darkBackgroundColor: Color,
    darkContentColor: Color
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bg = if (isDark) darkBackgroundColor else backgroundColor
    val content = if (isDark) darkContentColor else contentColor

    Surface(
        color = bg,
        shape = RoundedCornerShape(50), // Fully rounded pill
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // Decorative icon - attachment type indicator
                tint = content,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = content
            )
        }
    }
}

/**
 * Category chip with pill shape - for use in detail screens like KnowledgeCardScreen
 */
@Composable
fun CategoryChip(
    name: String,
    isNew: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Monochrome aesthetic for chips
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accentColor = rememberMonochromeAccent()

    val bg = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    val cardColor = if (isNew) accentColor.copy(alpha = Alpha.medium) else bg
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = cardColor
    ) {
        Text(
            text = name.lowercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = if (isNew) accentColor else contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

