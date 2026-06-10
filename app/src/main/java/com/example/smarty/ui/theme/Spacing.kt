package com.example.smarty.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

// =============================================================================
// APPLE HIG 8-PT SPACING SYSTEM
// =============================================================================
data class AppleSpacing(
    val tiny: Dp = 4.dp,     // Micro padding
    val small: Dp = 8.dp,    // Related elements
    val medium: Dp = 16.dp,  // STANDARD SCREEN MARGIN & PADDING
    val large: Dp = 24.dp,   // Section gaps
    val extraLarge: Dp = 32.dp, // Major breaks
    val huge: Dp = 48.dp     // Empty states
)

val LocalAppleSpacing = staticCompositionLocalOf { AppleSpacing() }

val MaterialTheme.appleSpacing: AppleSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppleSpacing.current


/**
 * Modern Soft Minimalist Spacing System
 *
 * Philosophy: Generous and airy. Elements "breathe."
 * Based on 8pt grid for consistent rhythm and alignment.
 *
 * Key principles:
 * - Card Internal Padding: 24dp or 32dp (never let text touch edges)
 * - Element Spacing: 16dp between groups, 8dp between related items
 * - Margins: Elements not packed tight
 *
 * Usage: LocalSpacing.current.medium
 */
data class Spacing(
    // Micro spacing - for tight elements
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val micro: Dp = 2.dp, // Hairline gaps
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val tiny: Dp = 4.dp, // Icon padding, minimal gaps
    // Small spacing - for related elements
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val extraSmall: Dp = 8.dp, // Between related items
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val small: Dp = 8.dp, // Base unit
    // Medium spacing - for content sections
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val medium: Dp = 12.dp, // 1.5x Base unit
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val default: Dp = 16.dp, // 2x Base unit - between groups
    // Large spacing - for major sections
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val large: Dp = 24.dp, // 3x Base unit - card padding
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val extraLarge: Dp = 32.dp, // 4x Base unit - large card padding
    // Extra large - for screen margins and major gaps
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val huge: Dp = 48.dp, // 6x Base unit
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val massive: Dp = 64.dp, // 8x Base unit
)

/**
 * Component-specific spacing constants
 * Updated for Modern Soft Minimalist "airy" feel
 */
object ComponentSpacing {
    // Basic units for reuse
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val micro = 2.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val tiny = 4.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val extraSmall = 8.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val small = 8.dp

    // Card spacing - GENEROUS for "airy" feel
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val cardPadding = 24.dp // Increased from 16dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val cardPaddingLarge = 32.dp // For larger cards/modals
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val cardContentGap = 16.dp // Increased from 12dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val cardHeaderGap = 8.dp // Keep for tight header elements
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val cardCornerRadius = 28.dp // Increased from 20dp (super-rounded)

    // List spacing - more breathing room
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val listItemGap = 16.dp // Increased from 12dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val listContentPadding = 20.dp // Increased from 16dp

    // Icon spacing
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val iconSize = 20.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val iconSizeSmall = 16.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val iconSizeLarge = 24.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val iconGap = 8.dp

    // Button spacing
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val buttonPadding = 16.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val buttonPaddingLarge = 20.dp // For prominent buttons
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val buttonGap = 12.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val buttonCornerRadius = 12.dp // Reduced from 20dp (inner elements)

    // Input field spacing
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputPadding = 16.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputCornerRadius = 16.dp // Inner element radius

    // Screen spacing - generous margins
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val screenPadding = 20.dp // Increased from 16dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sectionGap = 32.dp // Increased from 24dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val headerGap = 32.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val elementGap = 16.dp // Between distinct groups
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val relatedGap = 8.dp // Between related items

    // PIN screen specific
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pinDotSize = 20.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pinDotGap = 24.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pinButtonSize = 72.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pinButtonGap = 24.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pinRowGap = 16.dp

    // Large spacing
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val huge = 48.dp

    // Bottom sheet constants
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sheetPadding = 24.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sheetHeaderGap = 16.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sheetDragHandleWidth = 36.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sheetDragHandleHeight = 4.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val sheetCornerRadius = 28.dp

    // Message bubble constants
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val bubblePadding = 18.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val bubblePaddingVertical = 14.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val bubbleMaxWidth = 320.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val bubbleCornerLarge = 24.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val bubbleCornerSmall = 6.dp

    // Note card constants
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val noteCardHeight = 100.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val noteCardPaddingHorizontal = 20.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val noteCardPaddingVertical = 12.dp

    // Action bar constants
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val actionBarPadding = 16.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val actionBarItemSpacing = 8.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val actionBarHeight = 72.dp

    // Audio player constants
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val miniPlayerHeight = 72.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val waveformHeight = 36.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val albumArtSize = 56.dp

    // Size constants used across all screens
    /** Standard full-width button height used on Login, Settings, etc. */
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val buttonHeight = 56.dp

    /** Pill/navigation bar height (FloatingActionBar, HorizontalActionBar, InputField) */
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pillHeight = 44.dp

    /** Input Field Specifics */
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputCircleSize = 44.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputCircleIconSize = 22.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputPillHeight = 44.dp
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val inputPillCornerRadius = 26.dp

    /** Icon button touch target size (action bars, floating bars) */
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val iconButtonSize = 36.dp

    /** Corner radius for pill-shaped buttons (Login, Settings cards) */
    @Deprecated("Migrating to Apple HIG 8-pt Spacing Grid")
    val pillButtonCornerRadius = 26.dp
}

/**
 * Typography scale
 */
object TypeScale {
    const val caption = 12f // Caption/Label from spec
    const val body = 16f // Body from spec
    const val subtitle = 18f
    const val title = 20f
    const val headline = 24f // H2 from spec
    const val display = 32f // H1/Hero from spec
}

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/**
 * Access spacing from any composable
 */
val spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

