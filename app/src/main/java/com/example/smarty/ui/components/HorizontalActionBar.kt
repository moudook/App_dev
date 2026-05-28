package com.example.smarty.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.core.domain.model.NavigationTab
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.SmartyIcons

@Composable
private fun resolveIconPainter(tab: NavigationTab, isHistoryMode: Boolean): androidx.compose.ui.graphics.painter.Painter {
    if (tab == NavigationTab.CHAT && isHistoryMode) return painterResource(R.drawable.ic_nav_chat)
    return when (tab) {
        NavigationTab.CHAT -> painterResource(R.drawable.ic_nav_chat)
        NavigationTab.NOTES -> painterResource(R.drawable.ic_nav_notes)
        NavigationTab.CALENDAR -> painterResource(R.drawable.ic_nav_calendar)
        NavigationTab.STACKS -> painterResource(R.drawable.ic_nav_stacks)
        NavigationTab.ARCHIVE -> painterResource(R.drawable.ic_nav_archive)
        NavigationTab.SETTINGS -> painterResource(R.drawable.ic_nav_settings)
        NavigationTab.GAMES -> androidx.compose.ui.graphics.vector.rememberVectorPainter(image = SmartyIcons.Games)
    }
}

/**
 * Horizontal Action Bar — iOS-style pill tab strip.
 * Features:
 * - Floating pill design with ambient shadow
 * - All tabs visible instantly (no rotary drag)
 * - Spring scale animations on selection
 * - Safe area insets handled via windowInsetsPadding so it won't overlap camera hole
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    // Soft iOS blur aesthetic
    val barBg = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Protect from camera cutouts and navigation bars (fixes overlap issue)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = barBg,
            border = BorderStroke(0.5.dp, borderColor),
            // Floating 3D shadow for light mode
            shadowElevation = if (isDark) 0.dp else 16.dp, 
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    TabItem(
                        tab = tab,
                        isSelected = isSelected,
                        accentColor = accentColor,
                        isDark = isDark,
                        isHistoryMode = isHistoryMode,
                        onClick = {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Light, snappy haptic
                                onTabSelected(tab)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: NavigationTab,
    isSelected: Boolean,
    accentColor: Color,
    isDark: Boolean,
    isHistoryMode: Boolean,
    onClick: () -> Unit
) {
    // 1. S-Tier Master Transition
    val transition = updateTransition(targetState = isSelected, label = "TabState")

    val scale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 0.6f, stiffness = 400f) },
        label = "scale"
    ) { selected -> if (selected) 1.15f else 1.0f }

    val bgAlpha by transition.animateFloat(
        transitionSpec = { tween(150) },
        label = "bgAlpha"
    ) { selected -> if (selected) (if (isDark) 0.15f else 0.12f) else 0f }

    val iconTint by transition.animateColor(
        transitionSpec = { tween(150) },
        label = "iconTint"
    ) { selected -> if (selected) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) }

    val iconPainter = resolveIconPainter(tab, isHistoryMode)

    Box(
        modifier = Modifier
            .size(46.dp)
            .squishClick { onClick() } // Universal Physics Engine applied!
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(6.dp) // Shrinks the background circle radius while keeping touch target 46dp
            .clip(CircleShape)
            .background(accentColor.copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = tab.label,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}
