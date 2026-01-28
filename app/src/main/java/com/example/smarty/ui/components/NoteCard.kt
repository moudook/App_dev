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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.getAttachmentCount
import com.example.smarty.data.model.getAttachments
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.JarvisEasing
import com.example.smarty.ui.animation.animateCardTilt
import com.example.smarty.ui.animation.animatedCardTransform
import com.example.smarty.ui.animation.cardTilt3D
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.util.ContentTypeDetector
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Modern Soft Minimalist NoteCard with smooth interactions:
 * - Redesigned to match premium UI with pills and cleaner topography.
 * - Super-rounded corners (28dp) with floating soft shadow
 * - Spring-based press animation with scale + subtle rotation
 * - Swipe right: Archive (main view) or Delete (archive view)
 * - Swipe left: Open todos (main view) or Unarchive (archive view)
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
    onLongPress: () -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    searchQuery: String? = null,
    isNewlyProcessed: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Press state for animations
    var isPressed by remember { mutableStateOf(false) }

    // Swipe state
    val swipeOffset = remember { Animatable(0f) }
    val swipeThreshold = remember { with(density) { 60.dp.toPx() } }
    val swipeActivationThreshold = remember { with(density) { 30.dp.toPx() } }
    var swipeActivated by remember { mutableStateOf(false) }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var actionInProgress by remember { mutableStateOf(false) }
    val snapBackSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 800f)

    // Animation transforms
    val (scale, rotation) = animatedCardTransform(pressed = isPressed, index = index)
    val tilt = animateCardTilt(pressed = isPressed, pressedElevation = 2f)

    // Border color
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> LocalAccentColor.current
            note.processingStatus == ProcessingStatus.PROCESSING -> LocalAccentColor.current.copy(alpha = 0.5f)
            swipeOffset.value > swipeThreshold * 0.5f -> if (isArchiveView) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else SystemGray.copy(alpha = 0.7f)
            swipeOffset.value < -swipeThreshold * 0.5f -> SystemBlue.copy(alpha = 0.5f)
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
            val color = if (isSwipeRight) (if (isArchiveView) MaterialTheme.colorScheme.error else SystemGray) else (if (isArchiveView) SystemBlue else SystemBlue)
            val icon = if (isSwipeRight) (if (isArchiveView) Icons.Default.Whatshot else Icons.Default.AllInbox) else (if (isArchiveView) Icons.Default.Unarchive else Icons.Default.Verified)

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(LocalShapes.current.cardMedium)
                    .background(color),
                contentAlignment = if (isSwipeRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .scale(0.8f + swipeAlpha * 0.4f)
                )
            }
        }

        // Main Card Surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
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
                        onPress = { isPressed = true; tryAwaitRelease(); isPressed = false },
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() },
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
                                swipeActivated = false; accumulatedDrag = 0f
                            },
                            onDragCancel = { coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }; swipeActivated = false; accumulatedDrag = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                accumulatedDrag += dragAmount
                                if (!swipeActivated && abs(accumulatedDrag) > swipeActivationThreshold) {
                                    swipeActivated = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                if (swipeActivated) coroutineScope.launch { swipeOffset.snapTo(accumulatedDrag) }
                            }
                        )
                    }
                },
            shape = LocalShapes.current.cardMedium,
            color = animateColorAsState(
                targetValue = if (isSelected) com.example.smarty.ui.theme.SystemBlue.copy(alpha = 0.20f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
                label = "cardBackground"
            ).value,
            shadowElevation = 0.dp,
            border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, com.example.smarty.ui.theme.SystemBlue) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Title
                    val titleStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.5).sp
                    )
                    
                    if (searchQuery.isNullOrBlank()) {
                        Text(
                            text = note.title.ifBlank { "Untitled Note" },
                            style = titleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 24.dp) // Space for edit icon area if needed
                        )
                    } else {
                        HighlightedText(
                            text = note.title.ifBlank { "Untitled Note" },
                            query = searchQuery,
                            style = titleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 2. Pills Layout
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // AI Generation Pill
                        if (note.isAiCreated) {
                            NoteCardPill(
                                text = "AI Generation",
                                icon = Icons.Default.AutoAwesome,
                                backgroundColor = Color(0xFFE8DEF8), // Light Purple
                                contentColor = Color(0xFF1D192B),
                                darkBackgroundColor = Color(0xFF4A4458),
                                darkContentColor = Color(0xFFE8DEF8)
                            )
                        }

                        // Category Pill (Filter)
                        note.categoryName?.let { category ->
                            NoteCardPill(
                                text = category,
                                icon = Icons.AutoMirrored.Filled.Label, // Creative: Tag/Label
                                backgroundColor = Color(0xFFE3E8EF), // Light Grey/Blue
                                contentColor = Color(0xFF1D2939),
                                darkBackgroundColor = Color(0xFF344054),
                                darkContentColor = Color(0xFFD0D5DD)
                            )
                        }

                        // Attachments Pills
                        val attachments = note.getAttachments()
                        val hasAudio = note.type == NoteType.AUDIO || attachments.any { it.mimeType.startsWith("audio/") }
                        val hasFiles = attachments.isNotEmpty() && !hasAudio // Simplification

                        if (hasFiles) {
                            NoteCardPill(
                                text = "SAVED FILES",
                                icon = Icons.Default.Description,
                                backgroundColor = Color(0xFFD1E9FF), // Light Blue
                                contentColor = Color(0xFF003F7F),
                                darkBackgroundColor = Color(0xFF004A77),
                                darkContentColor = Color(0xFFC3E7FF)
                            )
                        }

                        if (hasAudio) {
                            NoteCardPill(
                                text = "SAVED AUDIO",
                                icon = Icons.Default.GraphicEq, // Creative: Waveform
                                backgroundColor = Color(0xFFC4E7FF), // Light Cyan
                                contentColor = Color(0xFF004C6D),
                                darkBackgroundColor = Color(0xFF004F70),
                                darkContentColor = Color(0xFFC2E8FF)
                            )
                        }
                    }
                }

                // 3. Edit Icon (Bottom Right)
                Icon(
                    imageVector = Icons.Default.DesignServices, // Creative: Design/Edit
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                )
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
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
                contentDescription = null,
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (isDark) Color(0xFF344054) else Color(0xFFE3E8EF)
    val contentColor = if (isDark) Color(0xFFD0D5DD) else Color(0xFF1D2939)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50), // Pill shape
        color = if (isNew) LocalAccentColor.current.copy(alpha = Alpha.medium) else bg
    ) {
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = if (isNew) LocalAccentColor.current else contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
