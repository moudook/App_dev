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
import com.example.smarty.data.model.getAttachmentCount
import com.example.smarty.data.model.getAttachments
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.animateCardTilt
import com.example.smarty.ui.animation.animatedCardTransform
import com.example.smarty.ui.animation.cardTilt3D
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.ui.animation.shimmerEffect
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
    onPlayYouTube: (String) -> Unit = {},  // Callback to play YouTube video
    searchQuery: String? = null,  // Search query for highlighting matching text
    isNewlyProcessed: Boolean = false  // Show blue dot for newly processed notes
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Press state for animations
    var isPressed by remember { mutableStateOf(false) }

    // Swipe state
    val swipeOffset = remember { Animatable(0f) }
    val swipeThreshold = remember { with(density) { 60.dp.toPx() } }  // Distance to trigger action
    val swipeActivationThreshold = remember { with(density) { 30.dp.toPx() } }  // Minimum drag to start showing swipe UI
    var swipeActivated by remember { mutableStateOf(false) }  // Has the swipe been activated this gesture?
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }  // Track total drag distance
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
                else SystemGray.copy(alpha = 0.5f)
            }
            swipeOffset.value < -swipeThreshold * 0.5f -> SystemBlue.copy(alpha = 0.5f)
            else -> Color.Transparent // Clean look by default
        },
        animationSpec = tween(160),
        label = "border"
    )

    // OPTIMIZED: Swipe indicator alpha using derivedStateOf
    // derivedStateOf only triggers recomposition when the derived value actually changes
    // This prevents unnecessary recompositions during swipe gestures
    val swipeAlpha by remember {
        derivedStateOf { (abs(swipeOffset.value) / swipeThreshold).coerceIn(0f, 1f) }
    }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        // Swipe Background Layer
        if (swipeAlpha > 0f) {
            val isSwipeRight = swipeOffset.value > 0
            val color = if (isSwipeRight) {
                if (isArchiveView) MaterialTheme.colorScheme.error else SystemGray
            } else {
                if (isArchiveView) SystemBlue else SystemBlue
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
                        .padding(horizontal = 16.dp)
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
                                if (swipeActivated && swipeOffset.value > swipeThreshold) {
                                    // INSTANT: Call action FIRST, then animate
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isArchiveView) onDelete() else onArchive?.invoke()
                                    coroutineScope.launch { swipeOffset.snapTo(0f) }
                                } else if (swipeActivated && swipeOffset.value < -swipeThreshold) {
                                    // INSTANT: Call action FIRST, then animate
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isArchiveView) onUnarchive?.invoke() else onOpenTodo()
                                    coroutineScope.launch { swipeOffset.snapTo(0f) }
                                } else {
                                    coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }
                                }
                                // Reset activation state for next gesture
                                swipeActivated = false
                                accumulatedDrag = 0f
                            },
                            onDragCancel = {
                                coroutineScope.launch { swipeOffset.animateTo(0f, snapBackSpec) }
                                // Reset activation state
                                swipeActivated = false
                                accumulatedDrag = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                // Accumulate drag distance
                                accumulatedDrag += dragAmount

                                // Only start showing swipe UI after exceeding activation threshold
                                if (!swipeActivated && abs(accumulatedDrag) > swipeActivationThreshold) {
                                    swipeActivated = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                // Only update swipe offset if activated
                                if (swipeActivated) {
                                    coroutineScope.launch {
                                        // Start from where we exceeded threshold, not from 0
                                        val effectiveDrag = accumulatedDrag - (swipeActivationThreshold * if (accumulatedDrag > 0) 1 else -1)
                                        swipeOffset.snapTo(effectiveDrag)
                                    }
                                }
                            }
                        )
                    }
                },
            shape = LocalShapes.current.cardMedium,
            // Uniform look for all cards - distinction is via tags only
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(2.dp, LocalAccentColor.current)
            } else {
                // High contrast border for better definition against background
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight() // Adaptive height: Matches content size
                    .heightIn(max = 140.dp) // COMPACT NOTEBOOK STYLE: drastically reduced max height
                    .padding(12.dp), // Reduced padding
                verticalArrangement = Arrangement.spacedBy(4.dp) // Reduced spacing
            ) {
                if (note.processingStatus == ProcessingStatus.PROCESSING || note.processingStatus == ProcessingStatus.PENDING) {
                    // Enhanced Shimmering Skeleton Loader with staggered wave effect
                    val shimmerBaseColor = LocalAccentColor.current.copy(alpha = 0.4f)
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Title skeleton - first in sequence
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .shimmerEffect(
                                    shimmerColor = shimmerBaseColor,
                                    durationMs = 1400,
                                    delayMs = 0,
                                    shimmerWidth = 350f
                                )
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // Body skeleton - Line 1 (slightly delayed)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                                .shimmerEffect(
                                    shimmerColor = shimmerBaseColor,
                                    durationMs = 1400,
                                    delayMs = 100,
                                    shimmerWidth = 350f
                                )
                        )
                        
                        // Body skeleton - Line 2 (more delayed for wave effect)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                                .shimmerEffect(
                                    shimmerColor = shimmerBaseColor,
                                    durationMs = 1400,
                                    delayMs = 200,
                                    shimmerWidth = 350f
                                )
                        )
                    }
                } else {
                    // Standard Content
                    // Header: Title + Category + Indicators + New Note Dot
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Title and Icon
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            // Type Icon (Enhanced Container)
                            // If AI created, use AI icon and accent color. Otherwise, use type-specific icon/color.
                            // Memoized to avoid recomputation on every recomposition
                            val iconVector = remember(note.isAiCreated, note.type) {
                                if (note.isAiCreated) Icons.Default.AutoAwesome else getNoteTypeIcon(note.type)
                            }
                            val typeColor = remember(note.type) { getNoteTypeColor(note.type) }
                            val iconTint = if (note.isAiCreated) LocalAccentColor.current else typeColor
                            
                                                // Icon Container with Overlays
                            Box {
                                Surface(
                                    shape = CircleShape,
                                    // Darker container for better Icon contrast
                                    color = iconTint.copy(alpha = 0.15f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = iconVector,
                                            contentDescription = null,
                                            tint = iconTint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Privacy Indicator Overlay (Bottom Right of Icon)
                                if (note.isFullPrivacy || note.excludeFromAiChat) {
                                    PrivacyIndicatorChip(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 2.dp, y = 2.dp) // Adjusted position
                                    )
                                }
                            }

                            Column {
                                // Title with search highlighting
                                if (searchQuery.isNullOrBlank()) {
                                    Text(
                                        text = note.title.ifBlank { "Untitled Note" }, 
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp,
                                            fontSize = 17.sp 
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, 
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    HighlightedText(
                                        text = note.title.ifBlank { "Untitled Note" },
                                        query = searchQuery,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp,
                                            fontSize = 17.sp 
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
                }

                    // Body Content - memoized to avoid recomputation
                    val displayText = remember(note.summary, note.content) {
                        note.summary ?: note.content
                    }
                    // Only show body if we have content
                    if (displayText.isNotBlank()) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium, // Increased from Normal to Medium
                                // Darker, higher contrast text color (Alpha 0.8 -> 0.9)
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f) 
                            ),
                            maxLines = 1, // Limit to 1 line for compact fixed height
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 48.dp) // Aligned with text, bypassing icon
                        )
                    }

                    // YouTube Button
                    val youtubeId = remember(note) {
                        note.sourceUrl?.let { ContentTypeDetector.extractYouTubeId(it) }
                            ?: ContentTypeDetector.extractYouTubeId(note.content)
                            ?: note.summary?.let { ContentTypeDetector.extractYouTubeId(it) }
                    }

                    if (youtubeId != null) {
                         Box(modifier = Modifier.padding(start = 48.dp)) {
                            YouTubePlayButton(
                                onClick = {
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
        color = if (isNew) {
            LocalAccentColor.current.copy(alpha = 0.15f)
        } else {
             // More vivid default state
             LocalAccentColor.current.copy(alpha = 0.1f)
        },
    ) {
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = LocalAccentColor.current, // Always accent color for vitality
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/**
 * OPTIMIZED: Privacy indicator chip with shield icon.
 * Indicates that AI cannot access this note.
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Animation pauses when app is backgrounded (zero CPU in background)
 * - Uses graphicsLayer lambda for GPU-accelerated alpha animation
 * - Lambda version reads animation state during draw phase, reducing recompositions
 */
@Composable
fun PrivacyIndicatorChip(modifier: Modifier = Modifier) {
    val privacyColor = com.example.smarty.ui.theme.SystemBlue

    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by com.example.smarty.ui.utils.rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == com.example.smarty.ui.utils.AnimationLifecycleState.RUNNING

    val shimmerAlpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "privacyShimmer")
        infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = CogniEasing.appleEaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "privacyShimmerAlpha"
        ).value
    } else {
        0.8f // Static value when backgrounded - zero animation overhead
    }

    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = "Private - AI cannot access",
        tint = privacyColor,
        modifier = modifier
            .size(14.dp)
            .graphicsLayer { alpha = shimmerAlpha }
    )
}

/**
 * OPTIMIZED: Processing indicator with pulsing animation
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Animation pauses when app is backgrounded (zero CPU in background)
 * - Uses graphicsLayer lambda for GPU-accelerated alpha animation
 * - Defers state read to draw phase, preventing recomposition on every frame
 */
@Composable
private fun ProcessingIndicator(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by com.example.smarty.ui.utils.rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == com.example.smarty.ui.utils.AnimationLifecycleState.RUNNING

    val alpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "processing")
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(640, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "processingAlpha"
        ).value
    } else {
        0.65f // Static mid-value when backgrounded
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(accentColor)
            .graphicsLayer { this.alpha = alpha }
    )
}

/**
 * Attachment indicator showing file type icon with badge number
 * Compact design: icon with count badge overlay (e.g., "3" or "9+")
 * Single file: icon only, no badge
 */
/**
 * Attachment indicator showing file type icon with badge number
 * Compact design: icon with count badge overlay (e.g., "3" or "9+")
 * Single file: icon only, no badge
 */
@Composable
private fun NoteAttachmentIndicator(
    note: Note,
    attachmentCount: Int,
    modifier: Modifier = Modifier
) {
    val attachments = note.getAttachments()

    // Determine icon and color based on file type
    val (icon, iconColor) = when {
        attachmentCount > 1 -> {
            // Multiple files - determine type from attachments or note type
            val mimeTypes = attachments.map { it.mimeType }
            val allImages = mimeTypes.all { it.startsWith("image/") }
            val allVideos = mimeTypes.all { it.startsWith("video/") }
            val allAudio = mimeTypes.all { it.startsWith("audio/") }

            when {
                allImages || note.type == NoteType.IMAGE -> Icons.Default.Photo to ImageTeal
                allVideos || note.type == NoteType.VIDEO -> Icons.Default.Videocam to VideoRed
                allAudio -> Icons.Default.MusicNote to AudioPink
                else -> Icons.Default.AttachFile to FileGray
            }
        }
        else -> {
            // Single file - determine icon by type
            val mimeType = note.fileMimeType ?: attachments.firstOrNull()?.mimeType
            val noteType = note.type

            when {
                noteType == NoteType.IMAGE || mimeType?.startsWith("image/") == true ->
                    Icons.Default.Photo to ImageTeal
                noteType == NoteType.VIDEO || mimeType?.startsWith("video/") == true ->
                    Icons.Default.Videocam to VideoRed
                mimeType?.contains("pdf") == true ->
                    Icons.AutoMirrored.Filled.Article to DocumentBlue
                mimeType?.contains("document") == true || mimeType?.contains("word") == true ->
                    Icons.AutoMirrored.Filled.Article to DocumentBlue
                mimeType?.contains("sheet") == true || mimeType?.contains("excel") == true ->
                    Icons.Default.TableChart to SpreadsheetGreen
                mimeType?.contains("presentation") == true || mimeType?.contains("powerpoint") == true ->
                    Icons.Default.Slideshow to PresentationOrange
                mimeType?.contains("zip") == true || mimeType?.contains("rar") == true || mimeType?.contains("tar") == true ->
                    Icons.Default.FolderZip to ArchiveYellow
                mimeType == "application/vnd.android.package-archive" ->
                    Icons.Default.Android to ApkGreen
                else -> Icons.Default.AttachFile to FileGray
            }
        }
    }

    Box(modifier = modifier) {
        // File type icon in rounded square
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = iconColor.copy(alpha = 0.12f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = if (attachmentCount > 1) "$attachmentCount files" else "File",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Badge for count > 1
        if (attachmentCount > 1) {
            Surface(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp),
                shape = CircleShape,
                color = iconColor
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = if (attachmentCount > 9) "9+" else attachmentCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Format file size in a compact way - delegates to ContentTypeDetector
 */
private fun formatFileSizeCompact(bytes: Long): String = com.example.smarty.util.ContentTypeDetector.formatSize(bytes)
