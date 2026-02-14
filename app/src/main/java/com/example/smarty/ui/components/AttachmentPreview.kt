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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.smarty.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AttachmentType
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.softCardShadow
import kotlinx.coroutines.delay

/**
 * Horizontal scrollable row of attachment previews
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp), // Space for shadows
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Leading padding for scroll
        Spacer(Modifier.width(4.dp))

        attachments.forEachIndexed { index, attachment ->
            key(attachment.id) {
                AnimatedAttachmentChip(
                    attachment = attachment,
                    index = index,
                    onRemove = { onRemoveAttachment(attachment.id) }
                )
            }
        }

        // Trailing padding
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * Individual attachment preview chip with animations
 * Refined for Soft Minimalist aesthetic.
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
        animationSpec = tween(200, easing = SmartyEasing.appleEaseOut),
        label = "entryAlpha"
    )

    // Remove button press animation
    val removeScale by animateFloatAsState(
        targetValue = if (isRemovePressed) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "removeScale"
    )

    val attachmentType = attachment.getAttachmentType()

    // Soft Minimalist Styling
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)

    val shape = if (attachmentType == AttachmentType.AUDIO) CircleShape else RoundedCornerShape(24.dp)

    Surface(
        modifier = Modifier
            .scale(entryScale)
            .graphicsLayer { alpha = entryAlpha }
            .height(if (attachmentType == AttachmentType.AUDIO) 56.dp else 72.dp)
            .widthIn(
                min = 80.dp,
                max = if (attachmentType == AttachmentType.AUDIO) 280.dp else 140.dp
            )
            .softCardShadow(
                elevation = 4.dp,
                shape = shape,
                spotColor = Color.Black.copy(alpha = 0.1f)
            ),
        shape = shape,
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
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
                            .clip(shape)
                    )

                    // Video overlay icon
                    if (attachmentType == AttachmentType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = stringResource(R.string.type_video),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                AttachmentType.AUDIO -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause circular button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(LocalAccentColor.current.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = stringResource(R.string.open),
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Filename
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = attachment.fileName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = stringResource(R.string.type_audio),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    // Show icon + filename
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = getAttachmentIcon(attachmentType),
                            contentDescription = null,
                            tint = getAttachmentColor(attachmentType),
                            modifier = Modifier.size(24.dp)
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
                    .offset(x = (-4).dp, y = 4.dp)
                    .scale(removeScale)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
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
                    contentDescription = stringResource(R.string.remove_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
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
        AttachmentType.IMAGE -> Icons.Default.Image
        AttachmentType.VIDEO -> Icons.Default.Videocam
        AttachmentType.AUDIO -> Icons.Default.Audiotrack
        AttachmentType.DOCUMENT -> Icons.Default.Description
        AttachmentType.SPREADSHEET -> Icons.Default.TableChart
        AttachmentType.PRESENTATION -> Icons.Default.Slideshow
        AttachmentType.APK -> Icons.Default.Android
        AttachmentType.ARCHIVE -> Icons.Default.Archive
        AttachmentType.FILE -> Icons.Default.AttachFile
    }
}

/**
 * Get color for attachment type
 */
@Composable
private fun getAttachmentColor(type: AttachmentType): Color {
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
