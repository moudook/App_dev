package com.example.smarty.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 val micro: Dp = 2.dp, // Hairline gaps
 val tiny: Dp = 4.dp, // Icon padding, minimal gaps

 // Small spacing - for related elements
 val extraSmall: Dp = 8.dp, // Between related items
 val small: Dp = 8.dp, // Base unit

 // Medium spacing - for content sections
 val medium: Dp = 12.dp, // 1.5x Base unit
 val default: Dp = 16.dp, // 2x Base unit - between groups

 // Large spacing - for major sections
 val large: Dp = 24.dp, // 3x Base unit - card padding
 val extraLarge: Dp = 32.dp, // 4x Base unit - large card padding

 // Extra large - for screen margins and major gaps
 val huge: Dp = 48.dp, // 6x Base unit
 val massive: Dp = 64.dp // 8x Base unit
)

/**
 * Component-specific spacing constants
 * Updated for Modern Soft Minimalist "airy" feel
 */
object ComponentSpacing {
 // Basic units for reuse
 val micro = 2.dp
 val tiny = 4.dp
 val extraSmall = 8.dp
 val small = 8.dp

 // Card spacing - GENEROUS for "airy" feel
 val cardPadding = 24.dp // Increased from 16dp
 val cardPaddingLarge = 32.dp // For larger cards/modals
 val cardContentGap = 16.dp // Increased from 12dp
 val cardHeaderGap = 8.dp // Keep for tight header elements
 val cardCornerRadius = 28.dp // Increased from 20dp (super-rounded)

 // List spacing - more breathing room
 val listItemGap = 16.dp // Increased from 12dp
 val listContentPadding = 20.dp // Increased from 16dp

 // Icon spacing
 val iconSize = 20.dp
 val iconSizeSmall = 16.dp
 val iconSizeLarge = 24.dp
 val iconGap = 8.dp

 // Button spacing
 val buttonPadding = 16.dp
 val buttonPaddingLarge = 20.dp // For prominent buttons
 val buttonGap = 12.dp
 val buttonCornerRadius = 12.dp // Reduced from 20dp (inner elements)

 // Input field spacing
 val inputPadding = 16.dp
 val inputCornerRadius = 16.dp // Inner element radius

 // Screen spacing - generous margins
 val screenPadding = 20.dp // Increased from 16dp
 val sectionGap = 32.dp // Increased from 24dp
 val headerGap = 32.dp
 val elementGap = 16.dp // Between distinct groups
 val relatedGap = 8.dp // Between related items

 // PIN screen specific
 val pinDotSize = 20.dp
 val pinDotGap = 24.dp
 val pinButtonSize = 72.dp
 val pinButtonGap = 24.dp
 val pinRowGap = 16.dp

 // Large spacing
 val huge = 48.dp

 // Bottom sheet constants
 val sheetPadding = 24.dp
 val sheetHeaderGap = 16.dp
 val sheetDragHandleWidth = 36.dp
 val sheetDragHandleHeight = 4.dp
 val sheetCornerRadius = 28.dp

 // Message bubble constants
 val bubblePadding = 18.dp
 val bubblePaddingVertical = 14.dp
 val bubbleMaxWidth = 320.dp
 val bubbleCornerLarge = 24.dp
 val bubbleCornerSmall = 6.dp

 // Thinking section constants
 val thinkingPaddingHorizontal = 12.dp
 val thinkingPaddingVertical = 10.dp
 val thinkingHeaderGap = 8.dp
 val thinkingTextGap = 8.dp
 val thinkingLineGap = 12.dp
 val thinkingLineMargin = 4.dp
 val thinkingLineWidth = 1.5.dp
 val thinkingIndicatorSize = 16.dp
 val thinkingMarginBottom = 12.dp

 // Note card constants
 val noteCardHeight = 100.dp
 val noteCardPaddingHorizontal = 20.dp
 val noteCardPaddingVertical = 12.dp

 // Action bar constants
 val actionBarPadding = 16.dp
 val actionBarItemSpacing = 8.dp
 val actionBarHeight = 72.dp

 // Audio player constants
 val miniPlayerHeight = 72.dp
 val waveformHeight = 36.dp
 val albumArtSize = 56.dp

 // Size constants used across all screens 
 /** Standard full-width button height used on Login, Settings, etc. */
 val buttonHeight = 56.dp

 /** Pill/navigation bar height (FloatingActionBar, HorizontalActionBar, InputField) */
 val pillHeight = 44.dp

 /** Input Field Specifics */
 val inputCircleSize = 44.dp
 val inputCircleIconSize = 22.dp
 val inputPillHeight = 44.dp
 val inputPillCornerRadius = 22.dp

 /** Icon button touch target size (action bars, floating bars) */
 val iconButtonSize = 36.dp

 /** Corner radius for pill-shaped buttons (Login, Settings cards) */
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
