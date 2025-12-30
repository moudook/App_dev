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
    NOTES(Icons.Rounded.StickyNote2, "Note"),
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
    archiveCount: Int = 0
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),    
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

                ActionPill(
                    icon = tab.icon,
                    label = tab.label,
                    isSelected = isSelected,
                    accentColor = accentColor,
                    badge = if (tab == NavigationTab.ARCHIVE && archiveCount > 0) archiveCount else null,
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
 * Individual action pill - Icon Only.
 */
@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    // Animate background color
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(AnimationDuration.standard),
        label = "pillBg"
    )

    // Animate icon color
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        animationSpec = tween(AnimationDuration.standard),
        label = "pillContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pillScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(40.dp) // Fixed square/circle size
            .clip(RoundedCornerShape(50)) // Circle
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp) 
        )
        
        // Badge logic
        if (badge != null && badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                 // Dot only for cleaner look on icon-only UI
            }
        }
    }
}
