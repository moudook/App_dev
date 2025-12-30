package com.example.smarty.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized icon size constants for the Cogni design system.
 * Replaces 8+ different hardcoded icon sizes throughout the codebase.
 */
object IconSize {
    val micro = 12.dp      // Tiny indicators
    val small = 14.dp      // Inline icons, badges
    val default = 16.dp    // Standard icons
    val medium = 18.dp     // Slightly larger
    val standard = 20.dp   // Common usage (most common)
    val large = 22.dp      // Prominent icons
    val xl = 24.dp         // Hero icons
    val xxl = 28.dp        // Large feature icons
    val huge = 32.dp       // Major visual anchors
    val massive = 44.dp    // Icon containers (settings sections)
    val container = 48.dp  // Icon button containers
}
