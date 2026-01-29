package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Navigation tabs for the centralized UI.
 * Icons designed with psychological metaphors to trigger creativity.
 */
enum class NavigationTab(
    val icon: ImageVector,
    val label: String,
    val opensSheet: Boolean = false
) {
    CHAT(Icons.Outlined.Psychology, "assistant"),
    NOTES(Icons.Outlined.Create, "notes"),
    CALENDAR(Icons.Outlined.Explore, "calendar", opensSheet = true),
    STACKS(Icons.Outlined.Hub, "stacks", opensSheet = true),
    ARCHIVE(Icons.Outlined.AutoStories, "archive", opensSheet = true),
    SETTINGS(Icons.Outlined.DisplaySettings, "settings", opensSheet = true)
}

/**
 * Horizontal Action Bar - Redesigned with a semi-circular rotary dial.
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp), // Increased height for the dial arc
        contentAlignment = Alignment.BottomCenter
    ) {
        RotaryNavigationDial(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            isHistoryMode = isHistoryMode,
            isCalendarMode = isCalendarMode,
            isStacksMode = isStacksMode,
            isArchiveMode = isArchiveMode,
            isSettingsMode = isSettingsMode,
            accentColor = accentColor,
            haptic = haptic
        )
    }
}

/**
 * Semi-circular convex rotary dial for navigation.
 */
@Composable
private fun RotaryNavigationDial(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    isHistoryMode: Boolean,
    isCalendarMode: Boolean,
    isStacksMode: Boolean,
    isArchiveMode: Boolean,
    isSettingsMode: Boolean,
    accentColor: Color,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    val tabs = NavigationTab.entries
    val tabCount = tabs.size

    // Each tab gets an "ideal" angle on the semi-circle (180 degrees)
    // Angles in radians: 0 is right, PI is left.
    // We want the semi-circle to be convex (bulging upwards or downwards?)
    // "Bottom half of a circle" means it arches UPWARDS from the bottom.
    // So angles from PI to 2*PI (or -PI to 0).
    // Let's use 0 to PI and rotate/offset as needed.

    val selectedIndex = tabs.indexOf(selectedTab)

    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(selectedIndex.toFloat()) }
    var lastVelocity by remember { mutableFloatStateOf(0f) }

    // Sync virtual index when selectedTab changes externally (e.g. from clicking an icon)
    LaunchedEffect(selectedTab) {
        val targetIndex = tabs.indexOf(selectedTab).toFloat()
        val currentVirtual = rotation.value

        // Find the shortest path in a circular list of size tabCount
        val diff = (targetIndex - (currentVirtual % tabCount + tabCount) % tabCount).let {
            val half = tabCount / 2f
            when {
                it > half -> it - tabCount
                it < -half -> it + tabCount
                else -> it
            }
        }

        if (abs(diff) > 0.001f) {
            rotation.animateTo(
                targetValue = currentVirtual + diff,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val density = LocalDensity.current
    
    // Dynamic Radius Calculation (Logarithmic increase with velocity)
    // Base radius 160.dp, increases up to +60.dp based on rotation speed
    // Higher sensitivity (0.5f) and larger max range to make the transition visually striking
    val currentVelocity = rotation.velocity
    val velocityFactor = abs(currentVelocity)
    val dynamicRadiusAdd = 60.dp * (1f - exp(-velocityFactor * 0.5f))
    val radiusPx = with(density) { (160.dp + dynamicRadiusAdd).toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        lastVelocity = 0f
                        scope.launch { rotation.stop() }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        // Map pixels to index units
                        val delta = dragAmount / 150f
                        lastVelocity = delta
                        scope.launch {
                            rotation.snapTo(rotation.value + delta)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            // Fling animation with decay (velocity scaled from per-event to per-second)
                            rotation.animateDecay(
                                initialVelocity = lastVelocity * 80f,
                                animationSpec = exponentialDecay(frictionMultiplier = 1f)
                            )

                            // Snap to the nearest tab after decay settles
                            val finalVirtualIndex = rotation.value
                            val targetIndex = finalVirtualIndex.roundToInt()

                            rotation.animateTo(
                                targetValue = targetIndex.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                            
                            // Ensure precise landing for selection
                            rotation.snapTo(targetIndex.toFloat())

                            // Update selection
                            val finalIndex = ((targetIndex % tabCount) + tabCount) % tabCount
                            onTabSelected(tabs[finalIndex])
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Draw the semi-circular background/arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val centerX = canvasWidth / 2f
            val centerY = -radiusPx + 40f // Center is above the screen

            // Subtle gradient arc bulging DOWNWARD
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                    radius = radiusPx + 20f
                ),
                center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                radius = radiusPx + 20f
            )
        }

        // Position icons along the arc
        tabs.forEachIndexed { index, tab ->
            // Calculate the shortest circular distance for this icon to the current focal point
            val rawOffset = (index - rotation.value)
            val positionOffset = ((rawOffset + tabCount / 2f) % tabCount + tabCount) % tabCount - tabCount / 2f

            // Map positionOffset to an angle
            val angleStep = PI / 8.0
            val angle = PI / 2.0 + (positionOffset * angleStep)

            val isCenter = index == selectedIndex

            // Visual properties based on position
            val distanceFromCenter = abs(positionOffset)

            // Refined transitions:
            // Scale goes from 1.2 at center to 0.6 at edges
            val scale = (1.2f - (distanceFromCenter * 0.2f)).coerceIn(0.4f, 1.2f)

            // Opacity: Fade out completely by the time we reach the wrapping point (3.0)
            // We use a slightly steeper curve to ensure it's gone before the jump
            val opacity = (1f - (distanceFromCenter / 2.8f)).coerceIn(0f, 1f)

            // Only show if it's within visible range
            if (opacity > 0.01f) {
                val icon = when {
                    tab == NavigationTab.CHAT && isHistoryMode -> Icons.Outlined.History
                    tab == NavigationTab.CHAT && selectedTab == NavigationTab.CHAT -> Icons.Filled.Psychology
                    tab == NavigationTab.NOTES && selectedTab == NavigationTab.NOTES -> Icons.Filled.Create
                    tab == NavigationTab.CALENDAR && (isCalendarMode || selectedTab == NavigationTab.CALENDAR) -> Icons.Filled.Explore
                    tab == NavigationTab.STACKS && (isStacksMode || selectedTab == NavigationTab.STACKS) -> Icons.Filled.Hub
                    tab == NavigationTab.ARCHIVE && (isArchiveMode || selectedTab == NavigationTab.ARCHIVE) -> Icons.Filled.AutoStories
                    tab == NavigationTab.SETTINGS && (isSettingsMode || selectedTab == NavigationTab.SETTINGS) -> Icons.Filled.DisplaySettings
                    else -> tab.icon
                }

                Box(
                    modifier = Modifier
                        .offset {
                            val x = (radiusPx * cos(angle)).toInt()
                            val y = (radiusPx * sin(angle) - radiusPx - 24.dp.toPx()).toInt()
                            IntOffset(x, y)
                        }

                        .graphicsLayer {
                            this.alpha = opacity
                            this.scaleX = scale
                            this.scaleY = scale
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!isCenter) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTabSelected(tab)
                            }
                        }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.label,
                        tint = if (isCenter) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f * opacity),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

