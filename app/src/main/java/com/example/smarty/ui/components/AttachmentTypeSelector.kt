package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor

/**
 * Attachment type options
 */
enum class AttachmentOption(
    val label: String,
    val icon: ImageVector
) {
    IMAGE("Photo", Icons.Default.Image),
    VIDEO("Video", Icons.Default.Videocam),
    DOCUMENT("Doc", Icons.AutoMirrored.Filled.Article),
    AUDIO("Audio", Icons.Default.Mic),
    FILE("File", Icons.Default.Folder),
    LINK("Link", Icons.Default.Link)
}

/**
 * Attachment Type Selector - Horizontal Pill Design.
 * Matches the SearchFilterTypeSelector exactly.
 * Minimal, flat, no external shadows.
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
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
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
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        if (index < AttachmentOption.entries.lastIndex) {
                            // Separator
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(12.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
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
    
    // Only visible if passed true
    if (visible) {
        Box(
            modifier = modifier // Use passed modifier (padding etc)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachmentOption.entries.forEachIndexed { index, option ->
                        val isSelected = option in selectedFilters
                        val backgroundColor by animateColorAsState(if (isSelected) accentColor else Color.Transparent, label = "bg")
                        val contentColor by animateColorAsState(if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "txt")
                        
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(backgroundColor)
                                .clickable { 
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onFilterToggle(option) 
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = contentColor
                            )
                        }
                        if (index < AttachmentOption.entries.lastIndex) Spacer(Modifier.width(2.dp))
                    }
                }
            }
        }
    }
}
