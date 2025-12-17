package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AttachmentType
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange
import kotlinx.coroutines.delay

/**
 * Horizontal scrollable row of attachment previews
 *
 * Features:
 * - Image thumbnails for images/videos
 * - File type icons for documents
 * - Remove button on each attachment
 * - Staggered entry animations
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = attachments.isNotEmpty(),
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = 300f
            ),
            expandFrom = Alignment.Bottom
        ) + fadeIn(animationSpec = tween(200)),
        exit = shrinkVertically(
            animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
            shrinkTowards = Alignment.Bottom
        ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = ComponentSpacing.cardHeaderGap),
            horizontalArrangement = Arrangement.spacedBy(ComponentSpacing.cardHeaderGap)
        ) {
            attachments.forEachIndexed { index, attachment ->
                key(attachment.id) {
                    AnimatedAttachmentChip(
                        attachment = attachment,
                        index = index,
                        onRemove = { onRemoveAttachment(attachment.id) }
                    )
                }
            }
        }
    }
}

/**
 * Individual attachment preview chip with animations
 */
@Composable
private fun AnimatedAttachmentChip(
    attachment: Attachment,
    index: Int,
    onRemove: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isRemovePressed by remember { mutableStateOf(false) }

    // Staggered entry animation
    var appeared by remember { mutableStateOf(false) }
    val staggerDelay = StaggerCalculator.logarithmic(index, 40)

    LaunchedEffect(Unit) {
        delay(staggerDelay.toLong())
        appeared = true
    }

    // Entry animations
    val entryScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "entryScale"
    )

    val entryAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
        label = "entryAlpha"
    )

    // Remove button press animation
    val removeScale by animateFloatAsState(
        targetValue = if (isRemovePressed) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "removeScale"
    )

    val attachmentType = attachment.getAttachmentType()
    val shapes = LocalShapes.current

    Surface(
        modifier = Modifier
            .scale(entryScale)
            .graphicsLayer { alpha = entryAlpha }
            .height(if (attachmentType == AttachmentType.AUDIO) 56.dp else 72.dp) // Slimmer for audio pill
            .widthIn(
                min = 80.dp, 
                max = if (attachmentType == AttachmentType.AUDIO) 280.dp else 140.dp // Wider for audio player
            ),
        shape = if (attachmentType == AttachmentType.AUDIO) CircleShape else shapes.input,
        color = if (attachmentType == AttachmentType.AUDIO) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Content based on type
            when (attachmentType) {
                AttachmentType.IMAGE, AttachmentType.VIDEO -> {
                    // Show thumbnail
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(attachment.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = attachment.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shapes.input)
                    )

                    // Video overlay icon
                    if (attachmentType == AttachmentType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = "Video",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                AttachmentType.AUDIO -> {
                    // Premium Music Player Style (Glass Pill)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LocalAccentColor.current.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = LocalAccentColor.current.copy(alpha = 0.15f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause circular button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(LocalAccentColor.current)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote, // Or PlayArrow if playing
                                contentDescription = "Play",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Filename and fake waveform/progress
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = attachment.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Fake progress bar line
                             Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(LocalAccentColor.current.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f) // 40% progress
                                        .fillMaxHeight()
                                        .background(LocalAccentColor.current)
                                )
                            }
                        }
                        
                        // Duration (Mock)
                        Text(
                            text = "0:00", // Placeholder
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                else -> {
                    // Show icon + filename
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(ComponentSpacing.cardHeaderGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = getAttachmentIcon(attachmentType),
                            contentDescription = null,
                            tint = getAttachmentColor(attachmentType),
                            modifier = Modifier.size(28.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = attachment.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Remove button (top-right corner)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .scale(removeScale)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(SafetyOrange)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isRemovePressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                tryAwaitRelease()
                                isRemovePressed = false
                            },
                            onTap = { onRemove() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Get icon for attachment type
 */
private fun getAttachmentIcon(type: AttachmentType): ImageVector {
    return when (type) {
        AttachmentType.IMAGE -> Icons.Default.Photo
        AttachmentType.VIDEO -> Icons.Default.Videocam
        AttachmentType.AUDIO -> Icons.Default.MusicNote
        AttachmentType.DOCUMENT -> Icons.AutoMirrored.Filled.Article
        AttachmentType.SPREADSHEET -> Icons.Default.TableChart
        AttachmentType.PRESENTATION -> Icons.Default.Slideshow
        AttachmentType.APK -> Icons.Default.Android
        AttachmentType.ARCHIVE -> Icons.Default.FolderZip
        AttachmentType.FILE -> Icons.Default.AttachFile
    }
}

/**
 * Get color for attachment type
 */
@Composable
private fun getAttachmentColor(type: AttachmentType): androidx.compose.ui.graphics.Color {
    return when (type) {
        AttachmentType.IMAGE -> LocalAccentColor.current
        AttachmentType.VIDEO -> com.example.smarty.ui.theme.VideoRed
        AttachmentType.AUDIO -> com.example.smarty.ui.theme.AudioPink
        AttachmentType.DOCUMENT -> com.example.smarty.ui.theme.DocumentBlue
        AttachmentType.SPREADSHEET -> com.example.smarty.ui.theme.SpreadsheetGreen
        AttachmentType.PRESENTATION -> com.example.smarty.ui.theme.PresentationOrange
        AttachmentType.APK -> com.example.smarty.ui.theme.ApkGreen
        AttachmentType.ARCHIVE -> com.example.smarty.ui.theme.ArchiveYellow
        AttachmentType.FILE -> com.example.smarty.ui.theme.FileGray
    }
}
