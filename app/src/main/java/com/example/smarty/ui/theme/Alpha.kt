package com.example.smarty.ui.theme

/**
 * Standardized alpha/opacity values for consistent transparency levels.
 * Use these instead of arbitrary alpha values like 0.08f, 0.12f scattered in code.
 */
object Alpha {
    const val invisible = 0f
    const val faint = 0.04f       // Skeleton backgrounds
    const val subtle = 0.06f      // Very subtle overlays
    const val hint = 0.08f        // Borders, subtle buttons
    const val soft = 0.1f         // Light backgrounds
    const val light = 0.12f       // Chip backgrounds
    const val medium = 0.15f      // Selected states
    const val moderate = 0.2f     // Stronger overlays
    const val strong = 0.25f      // Active glow
    const val emphasis = 0.3f     // Prominent overlays
    const val half = 0.5f         // Semi-transparent
    const val prominent = 0.6f    // Strong overlay
    const val heavy = 0.7f        // Near-opaque
    const val mostlyOpaque = 0.8f
    const val nearlyOpaque = 0.85f
    const val almostOpaque = 0.95f
    const val opaque = 1f
}
