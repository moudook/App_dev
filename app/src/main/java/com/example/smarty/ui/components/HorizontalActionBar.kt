package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.theme.Alpha

/**
 * Navigation tabs for the centralized UI.
 */
enum class NavigationTab(
    val icon: ImageVector,
    val label: String,
    val opensSheet: Boolean = false
) {
    NOTES(Icons.AutoMirrored.Rounded.StickyNote2, "Note"),
    CHAT(Icons.Rounded.AutoAwesome, "Chat"),
    CALENDAR(Icons.Rounded.CalendarMonth, "Calendar", opensSheet = true),
    STACKS(Icons.Rounded.GridView, "Stacks", opensSheet = true),
    ARCHIVE(Icons.Rounded.Archive, "Archive", opensSheet = true),
    SETTINGS(Icons.Rounded.Settings, "Settings", opensSheet = true)
}

/**
 * Horizontal Action Bar - Redesigned with "Autumn Sky" aesthetics.
 * 
 * Theme:
 * - Fluid animations
 * - Gradient highlights (Blue to Warm Gold)
 * - Glass-like, floated integration
 * - Premium typography and spacing
 */
@Composable
fun HorizontalActionBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    isChatMode: Boolean = false,
    isHistoryMode: Boolean = false,
    isCalendarMode: Boolean = false,
    isStacksMode: Boolean = false,
    isArchiveMode: Boolean = false,
    isSettingsMode: Boolean = false,
    archiveCount: Int = 0
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),    
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTab.entries.forEach { tab ->
                val isSelected = when (tab) {
                    NavigationTab.CHAT -> isChatMode
                    NavigationTab.NOTES -> !isChatMode && selectedTab == NavigationTab.NOTES
                    else -> selectedTab == tab
                }

                // Special handling for each tab in its respective inline mode
                val isHistoryChat = isHistoryMode && tab == NavigationTab.CHAT
                val isCalendarActive = isCalendarMode && tab == NavigationTab.CALENDAR
                val isStacksActive = isStacksMode && tab == NavigationTab.STACKS
                val isArchiveActive = isArchiveMode && tab == NavigationTab.ARCHIVE
                val isSettingsActive = isSettingsMode && tab == NavigationTab.SETTINGS
                
                val displayIcon = when {
                    isHistoryChat -> Icons.Outlined.History
                    isCalendarActive -> Icons.Rounded.CalendarMonth
                    isStacksActive -> Icons.Filled.Folder
                    isArchiveActive -> Icons.Filled.Archive
                    isSettingsActive -> Icons.Filled.Settings
                    else -> tab.icon
                }
                val displayLabel = when {
                    isHistoryChat -> "History"
                    isCalendarActive -> "Calendar"
                    isStacksActive -> "Stacks"
                    isArchiveActive -> "Archive"
                    isSettingsActive -> "Settings"
                    else -> tab.label
                }
                val showLabel = isHistoryChat || isCalendarActive || isStacksActive || 
                               isArchiveActive || isSettingsActive

                ActionPill(
                    icon = displayIcon,
                    label = displayLabel,
                    isSelected = isSelected,
                    showLabel = showLabel,
                    accentColor = accentColor,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTabSelected(tab)
                    }
                )
            }
        }
    }
}

/**
 * Individual action pill.
 * Adapts to show label for specific states (like History).
 */
@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    showLabel: Boolean = false,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    
    // Animate background color - Matches "Saved Audio" Pill Blue
    val targetBgColor = if (isSelected) {
        if (isDark) Color(0xFF004F70) else Color(0xFFC4E7FF)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    
    val backgroundColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(AnimationDuration.standard),
        label = "pillBg"
    )

    // Animate icon/text color
    val targetContentColor = if (isSelected) {
        if (isDark) Color(0xFFC2E8FF) else Color(0xFF004C6D)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    }
    
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(AnimationDuration.standard),
        label = "pillContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pillScale"
    )
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = modifier
            .scale(scale)
            .height(30.dp) // Maintain consistent height
            .animateContentSize() // Smooth expansion
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Icon centering logic: 
            // If explicit label shown, use standard spacing.
            // If no label (icon only), ensure it's a 30dp square for alignment.
            
            Box(
                modifier = Modifier
                    .size(if (showLabel) 20.dp else 30.dp), // layout size
                contentAlignment = Alignment.Center
            ) {
                 Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            if (showLabel) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = contentColor
                )
            }
        }
    }
}
