package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
    NOTES(Icons.Default.StickyNote2, "Notes"),
    CHAT(Icons.Default.AutoAwesome, "Chat"),
    CALENDAR(Icons.Default.CalendarMonth, "Calendar", opensSheet = true),
    STACKS(Icons.Default.GridView, "Stacks", opensSheet = true),
    ARCHIVE(Icons.Default.Archive, "Archive", opensSheet = true),
    SETTINGS(Icons.Default.Settings, "Settings", opensSheet = true)
}

/**
 * Horizontal Action Bar - Pinterest-inspired scrollable tab bar.
 *
 * Provides centralized access to all app features from the main screen.
 * - Notes and Chat are inline (replace content)
 * - Calendar, Stacks, Archive, Settings open as bottom sheets
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
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(NavigationTab.entries) { tab ->
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
 * Individual action pill in the horizontal bar.
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
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected)
            accentColor.copy(alpha = Alpha.medium)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = Alpha.half),
        animationSpec = tween(AnimationDuration.standard),
        label = "pillBg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected)
            accentColor
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(AnimationDuration.standard),
        label = "pillContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pillScale"
    )

    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = backgroundColor,
            modifier = Modifier.scale(scale)
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (isSelected) 16.dp else 12.dp,
                    vertical = 10.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(IconSize.standard)
                )

                // Show label only when selected
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = contentColor
                    )
                }
            }
        }

        // Badge for Archive count
        if (badge != null && badge > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp)
                    .size(18.dp),
                shape = RoundedCornerShape(9.dp),
                color = accentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}
