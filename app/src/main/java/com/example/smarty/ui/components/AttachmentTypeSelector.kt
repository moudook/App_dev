package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.CogniMotion
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.theme.ComponentSpacing
import kotlinx.coroutines.delay

/**
 * Attachment type options for the selector
 */
enum class AttachmentOption(
    val label: String
) {
    IMAGE("Photo"),
    VIDEO("Video"),
    DOCUMENT("Doc"),
    AUDIO("Audio"),
    FILE("File"),
    LINK("Link")
}

/**
 * Animated attachment type selector panel
 *
 * Shows a row of attachment type buttons with staggered entry animation
 * Apple-style spring physics for natural feel
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
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = 300f
            ),
            expandFrom = Alignment.Bottom
        ) + fadeIn(
            animationSpec = tween(200, easing = CogniEasing.appleEaseOut)
        ),
        exit = shrinkVertically(
            animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
            shrinkTowards = Alignment.Bottom
        ) + fadeOut(
            animationSpec = tween(150)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp), // Match Chat Mode bottom padding
            contentAlignment = Alignment.Center
        ) {
            // EXACT COPY of Chat Mode pill styling (from CogniInputField.kt lines 260-309)
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = LocalAccentColor.current.copy(alpha = 0.8f), // EXACT: 0.8f alpha like Chat Mode
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = LocalAccentColor.current.copy(alpha = 0.25f) // EXACT: 0.25f border
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), // EXACT: same padding as Chat Mode
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    AttachmentOption.entries.forEachIndexed { index, option ->
                        // Text Item - PURE WHITE (same as Chat Mode)
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium // EXACT: same fontWeight as Chat Mode
                            ),
                            color = androidx.compose.ui.graphics.Color.White, // Pure white like Chat Mode
                            modifier = Modifier.clickable {
                                when (option) {
                                    AttachmentOption.IMAGE -> onSelectImage()
                                    AttachmentOption.VIDEO -> onSelectVideo()
                                    AttachmentOption.DOCUMENT -> onSelectDocument()
                                    AttachmentOption.AUDIO -> onSelectAudio()
                                    AttachmentOption.FILE -> onSelectFile()
                                    AttachmentOption.LINK -> onSelectLink()
                                }
                            }
                        )
                        
                        // Spacer before divider
                        Spacer(modifier = Modifier.width(8.dp))

                        // Separator (if not last) - EXACT: height(10.dp), alpha 0.3f like Chat Mode
                        if (index < AttachmentOption.entries.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(10.dp) // EXACT: same as Chat Mode divider
                                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)) // EXACT: 0.3f alpha
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// Removed AnimatedAttachmentButton as it's replaced by inline text items

/**
 * Filter type selector for search mode.
 * Supports multiple selection with tap-to-toggle behavior.
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
    
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
            expandFrom = Alignment.Bottom
        ) + fadeIn(animationSpec = tween(200, easing = CogniEasing.appleEaseOut)),
        exit = shrinkVertically(
            animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
            shrinkTowards = Alignment.Bottom
        ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (selectedFilters.isNotEmpty()) accentColor.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (selectedFilters.isNotEmpty()) accentColor.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Filter:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (selectedFilters.isNotEmpty()) 
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    
                    AttachmentOption.entries.forEachIndexed { index, option ->
                        val isSelected = option in selectedFilters
                        val backgroundColor by animateColorAsState(
                            targetValue = if (isSelected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f)
                                          else androidx.compose.ui.graphics.Color.Transparent,
                            animationSpec = tween(200), label = "filterBg"
                        )
                        val textColor by animateColorAsState(
                            targetValue = when {
                                isSelected && selectedFilters.isNotEmpty() -> androidx.compose.ui.graphics.Color.White
                                selectedFilters.isNotEmpty() -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                                isSelected -> accentColor
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = tween(200), label = "filterText"
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isSelected) 1.1f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f), label = "filterScale"
                        )
                        
                        Box(
                            modifier = Modifier
                                .scale(scale)
                                .clip(RoundedCornerShape(8.dp))
                                .background(backgroundColor)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onFilterToggle(option)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = textColor
                            )
                        }
                        if (index < AttachmentOption.entries.lastIndex) {
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                    
                    AnimatedVisibility(
                        visible = selectedFilters.isNotEmpty(),
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.width(1.dp).height(10.dp)
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onClearFilters()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}



