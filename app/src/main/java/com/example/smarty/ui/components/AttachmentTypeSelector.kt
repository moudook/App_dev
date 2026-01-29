package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.softCardShadow

/**
 * Attachment type options
 */
enum class AttachmentOption(
    val labelResId: Int,
    val icon: ImageVector
) {
    IMAGE(R.string.photo, Icons.Default.Image),
    VIDEO(R.string.video, Icons.Default.Videocam),
    DOCUMENT(R.string.document, Icons.Default.Description),
    AUDIO(R.string.audio_label, Icons.Default.Audiotrack),
    FILE(R.string.file, Icons.Default.AttachFile),
    LINK(R.string.link, Icons.Default.Link)
}

/**
 * Attachment Type Selector - Horizontal Pill Design.
 * Matches the Soft Minimalist aesthetic.
 */
@Composable
fun AttachmentTypeSelector(
    visible: Boolean,
    onSelectImage: () -> Unit,
    onSelectVideo: () -> Unit,
    onSelectDocument: () -> Unit,
    onSelectAudio: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }

    // Soft minimalist colors
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = backgroundColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                modifier = Modifier.softCardShadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    spotColor = Color.Black.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachmentOption.entries.forEachIndexed { index, option ->

                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (option) {
                                         AttachmentOption.IMAGE -> onSelectImage()
                                         AttachmentOption.VIDEO -> onSelectVideo()
                                         AttachmentOption.DOCUMENT -> onSelectDocument()
                                         AttachmentOption.AUDIO -> onSelectAudio()
                                         AttachmentOption.FILE -> onSelectFile()
                                         AttachmentOption.LINK -> onSelectLink()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(option.labelResId),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (index < AttachmentOption.entries.lastIndex) {
                            // Separator
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(14.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Filter type selector for search mode
 * Reused UI pattern.
 */
@Composable
fun SearchFilterTypeSelector(
    visible: Boolean,
    selectedFilters: Set<AttachmentOption>,
    onFilterToggle: (AttachmentOption) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }

    // Soft minimalist colors
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)

    // Only visible if passed true
    if (visible) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = backgroundColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                modifier = Modifier.softCardShadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    spotColor = Color.Black.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachmentOption.entries.forEachIndexed { index, option ->
                        val isSelected = option in selectedFilters

                        val itemBgColor by animateColorAsState(
                            if (isSelected) accentColor else Color.Transparent,
                            label = "bg"
                        )
                        val itemContentColor by animateColorAsState(
                            if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            label = "txt"
                        )

                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(itemBgColor)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onFilterToggle(option)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(option.labelResId),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = itemContentColor
                            )
                        }

                        if (index < AttachmentOption.entries.lastIndex) {
                            // Separator, but hide if adjacent items are selected
                            val nextSelected = AttachmentOption.entries[index + 1] in selectedFilters
                            if (!isSelected && !nextSelected) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(14.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                )
                            } else {
                                Spacer(Modifier.width(1.dp)) // Maintain spacing logic
                            }
                        }
                    }
                }
            }
        }
    }
}
