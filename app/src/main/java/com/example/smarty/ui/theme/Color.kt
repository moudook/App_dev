package com.example.smarty.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cogni Color System - Modern Soft Minimalist
 *
 * Design Archetype: "Clean Tech" / "Refined Bento-Grid"
 * High readability, soft shadows, generous whitespace,
 * high-contrast Electric Blue accent against clean canvas.
 */

// =============================================================================
// MODERN SOFT MINIMALIST - LIGHT THEME (Comfort Edition)
// =============================================================================
val SoftBackground = Color(0xFFEBE8E0)       // Warm Stone - deeper, warmer background (less reflective)
val CardWhite = Color(0xFFF7F5F0)            // Antique White - paper-like, zero blue light
val ElectricBlue = Color(0xFF0066FF)         // Primary Accent (Brand) - vibrant
val PaleBlueGrey = Color(0xFFE3E0D9)         // Secondary Accent - blended with background
val TextNearBlack = Color(0xFF383530)        // Text Primary - deep warm earthy grey
val TextCoolGrey = Color(0xFF4A4640)         // Text Secondary - warm taupe
val SubtleBorder = Color(0xFFC4BFAC)         // Borders - refined stone grey
val InputBackground = Color(0xFFE3E0D9)      // Input field - blends with secondary
val SecondaryButtonBg = Color(0xFFD6D2CA)    // Secondary button background

// =============================================================================
// MODERN SOFT MINIMALIST - DARK THEME (Claude-inspired)
// =============================================================================
val DarkBackground = Color(0xFF0D0C11)       // Background - deep charcoal (Claude-style)
val DarkCard = Color(0xFF181822)             // Card Background - slightly elevated
val DarkElectricBlue = Color(0xFF2979FF)     // Primary Accent - brighter for dark
val DarkPaleBlue = Color(0xFF1E3A5F)         // Secondary Accent - dark variant
val DarkTextPrimary = Color(0xFFFFFFFF)      // Text Primary - white
val DarkTextSecondary = Color(0xFF9A9BA1)    // Text Secondary - slightly brighter grey
val DarkBorder = Color(0xFF252530)           // Borders - subtle purple-tinted dark
val DarkInputBackground = Color(0xFF181822)  // Input field background
val DarkSurfaceElevated = Color(0xFF1F1F2A)  // Higher elevation surface

// =============================================================================
// LEGACY COLORS (kept for compatibility)
// =============================================================================
val DeepBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF1C1C1E)
val SurfaceDarkElevated = Color(0xFF2C2C2E)
val BorderDark = Color(0xFF38383A)
val PureWhite = Color(0xFFFFFFFF)
val SurfaceLight = Color(0xFFF2F2F7)
val SurfaceLightElevated = Color(0xFFFFFFFF)
val BorderLight = Color(0xFFD1D1D6)

// Legacy accent colors (may be used in animations)
val AcidGreen = Color(0xFFCCFF00)
val AcidGreenDark = Color(0xFF9ECC00)
val BrightOrange = Color(0xFFFF6B00)
val SafetyOrange = Color(0xFFFF4D00)
val NeonPurple = Color(0xFFBB86FC)

// =============================================================================
// TEXT COLORS - Dark Theme
// =============================================================================
val TextPrimaryDark = Color(0xFFFFFFFF)
val TextSecondaryDark = Color(0xFF8E8E93)
val TextTertiaryDark = Color(0xFF636366)

// =============================================================================
// TEXT COLORS - Light Theme
// =============================================================================
val TextPrimaryLight = Color(0xFF000000)
val TextSecondaryLight = Color(0xFF3C3C43)
val TextTertiaryLight = Color(0xFF8E8E93)

// =============================================================================
// NOTE TYPE COLORS - Semantic (preserved)
// =============================================================================
val YoutubeRed = Color(0xFFFF0000)
val TwitterBlue = Color(0xFF1DA1F2)
val WebGray = Color(0xFF8E8E93)
val ImageTeal = Color(0xFF30D158)
val BrainDumpPurple = Color(0xFFBF5AF2)

// =============================================================================
// APPLE SYSTEM COLORS (iOS Standard - preserved for semantic use)
// =============================================================================
val SystemBlue = Color(0xFF007AFF)
val SystemRed = Color(0xFFFF3B30)
val SystemGreen = Color(0xFF34C759)
val SystemOrange = Color(0xFFFF9500)
val SystemGray6 = Color(0xFFF2F2F7)
val SystemGray5 = Color(0xFFE5E5EA)
val SystemGray4 = Color(0xFFD1D1D6)
val SystemGray3 = Color(0xFFC7C7CC)
val SystemGray = Color(0xFF8E8E93)

// =============================================================================
// FILE TYPE COLORS - Semantic (preserved)
// =============================================================================
val DocumentBlue = SystemBlue
val SpreadsheetGreen = SystemGreen
val PresentationOrange = SystemOrange
val VideoRed = SystemRed
val AudioPink = Color(0xFFFF2D55)
val CodeCyan = Color(0xFF5AC8FA)
val ArchiveYellow = Color(0xFFFFCC00)
val ApkGreen = Color(0xFF3DDC84)
val FileGray = SystemGray

// =============================================================================
// GEMINI-STYLE COLORS - For Assistant Overlay
// =============================================================================

/**
 * Google Brand Colors for Gemini-style four-color glow animation.
 * Used in the assistant overlay for input bar border and mic button ring.
 */
object GeminiColors {
    val Red = Color(0xFFEA4335)
    val Yellow = Color(0xFFFBBC05)
    val Green = Color(0xFF34A853)
    val Blue = Color(0xFF4285F4)

    /** Four-color gradient for glow animation */
    val glowColors = listOf(Red, Yellow, Green, Blue)

    /** Dominant color after initial glow animation */
    val dominantBlue = Blue

    // Surface colors (Material 3 from Gemini)
    val SurfaceLight = Color(0xFFFEFBFF)
    val SurfaceDark = Color(0xFF121212)  // True black for dark mode
    val SurfaceContainer = Color(0xFFF3EDF7)
    val SurfaceContainerDark = Color(0xFF1D1B20)
}
