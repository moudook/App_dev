package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.core.domain.model.NavigationTab
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.launch
import kotlin.math.*

// ─── Icon Resolution (pure function, no allocations) ───────────────────
// CUSTOM ICONS: Uses SVG icons from _private/icons folder via drawable resources
// Active = Pink tint, Inactive = Neutral (no pink)

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
    }
}

// Pink color for active icons - hard rule from user
private val ActivePink = Color(0xFFF49BE0)

// ─── Playful Aesthetic Colours (Squircle Design) ─────────────────────

private data class TabColors(val bg: Color, val fg: Color)

private fun getSpicyTabColors(tab: NavigationTab): TabColors {
    return when (tab) {
        NavigationTab.CHAT -> TabColors(bg = Color(0xFF70C2F6), fg = Color(0xFF0767DC))      // Blue
        NavigationTab.NOTES -> TabColors(bg = Color(0xFFFACC4D), fg = Color(0xFFC76906))     // Yellow/Orange
        NavigationTab.CALENDAR -> TabColors(bg = Color(0xFF8AE07E), fg = Color(0xFF0A9122))  // Green
        NavigationTab.STACKS -> TabColors(bg = Color(0xFFF4A895), fg = Color(0xFFDE3717))    // Peach/Red
        NavigationTab.ARCHIVE -> TabColors(bg = Color(0xFFF49BE0), fg = Color(0xFFD2008C))   // Pink
        NavigationTab.SETTINGS -> TabColors(bg = Color(0xFFD29EFA), fg = Color(0xFF6714A6))  // Purple
    }
}

// ─── Label capitalization (cached per tab) ─────────────────────────────

private val tabLabels: Map<NavigationTab, String> = NavigationTab.entries.associateWith { tab ->
    tab.label.replaceFirstChar { c ->
        if (c.isLowerCase()) c.titlecase(java.util.Locale.ROOT) else c.toString()
    }
}

// ─── Constants ─────────────────────────────────────────────────────────

private val TABS = NavigationTab.entries
private const val TAB_COUNT = 6 // NavigationTab.entries.size — compile-time constant
private const val ANGLE_STEP = PI / 6.5
private const val HALF_PI = PI / 2.0
private val COLLAPSED_HEIGHT = 60.dp
private val EXPANDED_HEIGHT = 120.dp
private val ICON_CIRCLE_SIZE = 44.dp
private val ICON_SIZE = 26.dp
private const val AUTO_COLLAPSE_MS = 1800L


/**
 * Horizontal Action Bar — optimised rotary dial navigation.
 *
 * Performance notes vs previous version:
 *  • Removed .blur() modifier (heavy GPU compositing on every icon per frame).
 *  • Removed dynamic-radius-from-velocity (continuous recomposition from velocity reads).
 *  • Hoisted theme colours outside the icon loop (single read per frame instead of 6×).
 *  • Replaced interactionTrigger counter flood with a simple auto-collapse job reference.
 *  • Fixed logic: "center" icon is now derived from rotation.value (visual center),
 *    not selectedIndex, so the highlighted icon always matches what's visually centred.
 *  • Fixed AI-won't-open bug: after drag, always calls onTabSelected for the landed tab
 *    so the parent can properly open the page.
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
    var isExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val scope = rememberCoroutineScope()

    val selectedIndex = TABS.indexOf(selectedTab)
    val rotation = remember { Animatable(selectedIndex.toFloat()) }
    var lastVelocity by remember { mutableFloatStateOf(0f) }

    // ── Auto-collapse timer (restarts on any interaction) ──
    var collapseKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(isExpanded, collapseKey) {
        if (isExpanded) {
            kotlinx.coroutines.delay(AUTO_COLLAPSE_MS)
            isExpanded = false
        }
    }

    // ── Sync rotation when parent changes selectedTab externally ──
    LaunchedEffect(selectedTab) {
        val target = TABS.indexOf(selectedTab).toFloat()
        val current = rotation.value
        val normalised = ((current % TAB_COUNT) + TAB_COUNT) % TAB_COUNT
        var diff = target - normalised
        if (diff > TAB_COUNT / 2f) diff -= TAB_COUNT
        if (diff < -TAB_COUNT / 2f) diff += TAB_COUNT
        if (abs(diff) > 0.01f) {
            rotation.animateTo(
                targetValue = current + diff,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    // ── Animated height ──
    val height by animateDpAsState(
        targetValue = if (isExpanded) EXPANDED_HEIGHT else COLLAPSED_HEIGHT,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "headerH"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                var totalDrag = 0f
                var wasCollapsed = false

                detectHorizontalDragGestures(
                    onDragStart = {
                        wasCollapsed = !isExpanded
                        totalDrag = 0f
                        isExpanded = true
                        collapseKey++
                        lastVelocity = 0f
                        scope.launch { rotation.stop() }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        collapseKey++ // reset auto-collapse
                        change.consume()
                        totalDrag += abs(dragAmount)

                        // Damping: let the header physically expand before scrolling kicks in
                        val damping = if (wasCollapsed) {
                            ((totalDrag - 20f) / 80f).coerceIn(0f, 1f)
                        } else 1f

                        val delta = -(dragAmount * damping) / 150f
                        lastVelocity = delta
                        scope.launch { rotation.snapTo(rotation.value + delta) }
                    },
                    onDragEnd = {
                        scope.launch {
                            // Momentum decay
                            rotation.animateDecay(
                                initialVelocity = lastVelocity * 80f,
                                animationSpec = exponentialDecay(frictionMultiplier = 1.2f)
                            )
                            // Snap to nearest integer index
                            val snapped = rotation.value.roundToInt()
                            rotation.animateTo(
                                targetValue = snapped.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                            val landedIndex = ((snapped % TAB_COUNT) + TAB_COUNT) % TAB_COUNT
                            val landedTab = TABS[landedIndex]

                            // ALWAYS fire onTabSelected so the parent can open the page.
                            // The parent's handleTabSelection already handles same-tab toggling.
                            onTabSelected(landedTab)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // ── Expanded: rotary dial ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)) + slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(300, easing = EaseOutQuart)
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = tween(200)
            )
        ) {
            RotaryDial(
                selectedTab = selectedTab,
                onTabSelected = { newTab ->
                    onTabSelected(newTab)
                    isExpanded = false
                },
                isHistoryMode = isHistoryMode,
                accentColor = accentColor,
                haptic = haptic,
                rotation = rotation
            )
        }

        // ── Collapsed: single icon pill ──
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(tween(300)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = tween(300, easing = EaseOutQuart)
            ),
            exit = fadeOut(tween(200)) + scaleOut(
                targetScale = 0.85f,
                animationSpec = tween(200)
            )
        ) {
            CollapsedPill(
                selectedTab = selectedTab,
                isHistoryMode = isHistoryMode,
                accentColor = accentColor,
                onClick = {
                    isExpanded = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Collapsed Pill
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun CollapsedPill(
    selectedTab: NavigationTab,
    isHistoryMode: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val surfaceColor = if (isDark)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    else
        MaterialTheme.colorScheme.surface
    val borderColor = if (isDark)
        Color.White.copy(alpha = 0.15f)
    else
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    // Active = Pink, Inactive = neutral (user requirement)
    val iconTint = ActivePink

    val iconPainter = resolveIconPainter(selectedTab, isHistoryMode = isHistoryMode)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(surfaceColor)
                    .border(BorderStroke(0.5.dp, borderColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = selectedTab.label,
                    tint = iconTint,
                    modifier = Modifier.size(ICON_SIZE)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.6f))
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
//  Expanded Rotary Dial  (optimised: no blur, no dynamic radius,
//  theme colors hoisted, center derived from rotation.value)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun RotaryDial(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    isHistoryMode: Boolean,
    accentColor: Color,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    rotation: Animatable<Float, AnimationVector1D>
) {
    val density = LocalDensity.current
    // Fixed radius — eliminates per-frame velocity reads that caused continuous recomposition
    val radiusPx = remember(density) { with(density) { 180.dp.toPx() } }

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val surfaceColor = if (isDark)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    else
        MaterialTheme.colorScheme.surface
    val borderColor = if (isDark)
        Color.White.copy(alpha = 0.15f)
    else
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // Stable interaction sources — one per tab, not recreated on recomposition
    val interactionSources = remember { Array(TAB_COUNT) { MutableInteractionSource() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EXPANDED_HEIGHT),
        contentAlignment = Alignment.BottomCenter
    ) {
        TABS.forEachIndexed { index, tab ->
            // ── Position math ──
            val rawOffset = index - rotation.value
            val positionOffset = ((rawOffset + TAB_COUNT / 2f) % TAB_COUNT + TAB_COUNT) % TAB_COUNT - TAB_COUNT / 2f
            val distFromCenter = abs(positionOffset)

            // Early-out: skip icons far off-screen
            if (distFromCenter > 3f) return@forEachIndexed

            val angle = HALF_PI - (positionOffset * ANGLE_STEP)
            val opacity = (1f - (distFromCenter / 2.8f)).coerceIn(0f, 1f)
            if (opacity < 0.02f) return@forEachIndexed

            val sizeScale = (1f - (distFromCenter * 0.18f)).coerceIn(0.7f, 1f)
            val currentSize = ICON_CIRCLE_SIZE * sizeScale
            val currentIconSize = ICON_SIZE * sizeScale

            // "Center" = visually centred icon, derived from rotation.value
            val visualCenter = ((rotation.value.roundToInt() % TAB_COUNT) + TAB_COUNT) % TAB_COUNT
            val isVisuallyActive = index == visualCenter

            // Active = Pink, Inactive = neutral (user requirement)
            val iconTint = if (isVisuallyActive) ActivePink else onSurfaceColor.copy(alpha = 0.5f * opacity)
            val labelAlpha = if (isVisuallyActive) 1f else (opacity * 0.8f).coerceAtLeast(0.35f)
            val iconPainter = resolveIconPainter(tab, isHistoryMode = isHistoryMode)

            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (radiusPx * cos(angle)).toInt(),
                            y = (radiusPx * sin(angle) - radiusPx - 24.dp.toPx()).toInt()
                        )
                    }
                    .graphicsLayer { alpha = opacity }
                    .clickable(
                        interactionSource = interactionSources[index],
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTabSelected(tab)
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(currentSize)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(BorderStroke(0.5.dp, borderColor), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = tab.label,
                        tint = iconTint,
                        modifier = Modifier.size(currentIconSize)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = tabLabels[tab] ?: tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceColor.copy(alpha = labelAlpha),
                    fontSize = (11f * sizeScale).sp,
                    fontWeight = if (isVisuallyActive) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
