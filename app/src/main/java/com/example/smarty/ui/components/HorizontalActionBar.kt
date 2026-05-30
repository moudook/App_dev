package com.example.smarty.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
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
import androidx.compose.ui.platform.LocalConfiguration
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
    archiveCount: Int = 0,
    isScrolling: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    // Subtle Pill State
    var isExpanded by remember { mutableStateOf(true) }

    // Auto-collapse timer
    LaunchedEffect(isExpanded, selectedTab, isScrolling) {
        if (isScrolling) {
            isExpanded = false
        } else if (isExpanded) {
            delay(3000L)
            isExpanded = false
        }
    }

    // Soft iOS blur aesthetic
    val barBg = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Protect from camera cutouts (fixes overlap issue)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopStart // Anchors the shrinking animation to the left
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = barBg,
            border = BorderStroke(0.5.dp, borderColor),

            modifier = Modifier
                // 1. S-TIER FIX: Let the container fluidly hug the expanding/shrinking contents
                .animateContentSize(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f))
                .let { if (isExpanded) it.fillMaxWidth() else it.wrapContentWidth() }
                .then(if (!isExpanded) Modifier.clickable { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isExpanded = true 
                } else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .let { if (isExpanded) it.fillMaxWidth() else it.wrapContentWidth() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = if (isExpanded) Arrangement.SpaceBetween else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    
                    // S-TIER ANIMATION: Unselected tabs scale down as they fade out, making the collapse feel physical
                    AnimatedVisibility(
                        visible = isExpanded || isSelected,
                        // 2. S-TIER FIX: Physically allocate space organically. Pushes the pill open like liquid.
                        enter = fadeIn(tween(300, easing = LinearOutSlowInEasing)) + 
                                expandHorizontally(
                                    expandFrom = Alignment.CenterHorizontally,
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
                                ) +
                                scaleIn(initialScale = 0.6f, animationSpec = spring(0.7f, 400f)),
                                
                        // 3. S-TIER FIX: Physically collapse the space. Pulls the pill closed without any clipping line!
                        exit = fadeOut(tween(150, easing = FastOutLinearInEasing)) + 
                               shrinkHorizontally(
                                   shrinkTowards = Alignment.CenterHorizontally,
                                   animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f)
                               ) +
                               scaleOut(targetScale = 0.5f, animationSpec = spring(0.8f, 350f))
                    ) {
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
                                isExpanded = true
                            }
                        )
                    }
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
        transitionSpec = { spring(dampingRatio = 0.6f, stiffness = 500f) },
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
