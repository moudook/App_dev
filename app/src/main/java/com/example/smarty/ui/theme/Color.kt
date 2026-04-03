package com.example.smarty.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Smarty Color System - Modern Soft Minimalist
 *
 * Design Archetype: "Clean Tech" / "Refined Bento-Grid"
 * High readability, soft shadows, generous whitespace,
 * high-contrast Electric Blue accent against clean canvas.
 */

// =============================================================================
// MODERN SOFT MINIMALIST - LIGHT THEME (Comfort Edition)
// =============================================================================
val SoftBackground = Color(0xFFFDFDFD) // Light theme background
val CardWhite = Color(0xFFFFFFFF) // Pure White cards for crispness
val ElectricBlue = Color(0xFF0066FF) // Primary Accent (Brand)
val PaleBlueGrey = Color(0xFFEBEFF5) // Secondary Accent - cool tint
val TextNearBlack = Color(0xFF1A1A1C) // Text Primary - softer black
val TextCoolGrey = Color(0xFF58585E) // Text Secondary
val SubtleBorder = Color(0xFFE5E5EA) // Borders - very subtle
val InputBackground = Color(0xFFF2F2F7) // Input field - standard iOS-like gray
val SecondaryButtonBg = Color(0xFFE5E5EA)
val SmartyChipGrayLight = Color(0xFFEDEDED)
val SmartyChipSeparatorLight = Color(0xFFFFFFFF)

// Pink Theme Tokens
val PinkAccent = Color(0xFFF49BE0) // Main Pink accent
val PinkLight = Color(0xFFFFF0F5) // Light Pink background
val PinkMedium = Color(0xFFF49BE0).copy(alpha = 0.35f) // Stronger pink tint
val PinkDark = Color(0xFFD2008C) // Dark Pink for text/icons on pink backgrounds
val PinkText = Color(0xFF1A1A1C) // Text color for pink-themed elements (not pink)

// =============================================================================
// MODERN SOFT MINIMALIST - DARK THEME (True Black AMOLED)
// =============================================================================
val DarkBackground = Color(0xFF000000) // True black for AMOLED
val DarkCard = Color(0xFF0A0A0A) // Near-black card
val DarkElectricBlue = Color(0xFF0A84FF) // Brighter blue for dark mode
val DarkPaleBlue = Color(0xFF0A0A0A) // Secondary
val DarkTextPrimary = Color(0xFFFFFFFF)
val DarkTextSecondary = Color(0xFF8A8A8E)
val DarkBorder = Color(0xFF1A1A1C)
val DarkInputBackground = Color(0xFF252528) // Lighter input block for better visibility
val DarkSurfaceElevated = Color(0xFF141416)
val SmartyChipGrayDark = Color(0xFF414141)
val SmartyChipSeparatorDark = Color(0xFFFFFFFF).copy(alpha = 0.08f)

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
val MinimalRed = Color(0xFFE57373) // Minimal/Soft Red for less "poppy" alerts
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
// NOTE TYPE COLORS - Softened Technical Palette
// =============================================================================
val YoutubeRed = Color(0xFFEF9A9A)
val TwitterBlue = Color(0xFF90CAF9)
val WebGray = Color(0xFFB0BEC5)
val ImageTeal = Color(0xFFA5D6A7)
val BrainDumpPurple = Color(0xFFB39DDB)

// =============================================================================
// SYSTEM COLORS - Softened for Calm Aesthetic
// =============================================================================
val SystemBlue = Color(0xFF90CAF9)
val SystemRed = Color(0xFFEF9A9A)
val SystemGreen = Color(0xFFA5D6A7)
val SystemOrange = Color(0xFFFFCC80)
val SystemGray6 = Color(0xFFF2F2F7)
val SystemGray5 = Color(0xFFE5E5EA)

// =============================================================================
// COMPONENT SPECIFIC SEMANTIC COLORS
// =============================================================================
/** Thinking section colors - Differentiated from standard response */
val ThinkingBackgroundLight = Color(0xFFF2F2F7).copy(alpha = 0.6f)
val ThinkingBorderLight = Color(0xFFE5E5EA).copy(alpha = 0.4f)
val ThinkingTextLight = Color(0xFF58585E)

val ThinkingBackgroundDark = Color(0xFF1C1C1E).copy(alpha = 0.5f)
val ThinkingBorderDark = Color(0xFF38383A).copy(alpha = 0.3f)
val ThinkingTextDark = Color(0xFF8E8E93)
val SystemGray4 = Color(0xFFD1D1D6)
val SystemGray3 = Color(0xFFC7C7CC)
val SystemGray = Color(0xFF8E8E93)

// =============================================================================
// FILE TYPE / CATEGORY COLORS - Technical Palette
// =============================================================================
val DocumentBlue = SystemBlue
val SpreadsheetGreen = SystemGreen
val PresentationOrange = SystemOrange
val VideoRed = SystemRed
val AudioPink = Color(0xFFF48FB1)
val CodeCyan = Color(0xFF80DEEA)
val ArchiveYellow = Color(0xFFFFE082)
val ApkGreen = Color(0xFFA5D6A7)
val FileGray = SystemGray

// ==================== ASSISTANT COLORS ====================

/**
 * Softened Technical Palette for assistant-related UI elements (glows, overlays).
 * Optimized for the Calm Aesthetic.
 */
object AssistantColors {
 val Red = Color(0xFFEF9A9A) // Soft Red
 val Yellow = Color(0xFFFFE082) // Soft Yellow
 val Green = Color(0xFFA5D6A7) // Soft Green
 val Blue = Color(0xFF90CAF9) // Soft Blue

 /** Four-color gradient for glow animation */
 val glowColors = listOf(Red, Yellow, Green, Blue)

 /** Dominant color after initial glow animation */
 val dominantBlue = Blue

 // Surface colors (Material 3 style)
 val SurfaceLight = Color(0xFFFEFBFF)
 val SurfaceDark = Color(0xFF121212) // True black for dark mode
 val SurfaceContainer = Color(0xFFF3EDF7)
 val SurfaceContainerDark = Color(0xFF1D1B20)
}

// =============================================================================
// SEMANTIC COLORS — Status / Meaning-bearing tokens
// (Merged from SemanticColors.kt — import from this file going forward)
// =============================================================================
/**
 * Semantic color tokens — iOS-style semantic colors.
 * These are theme-independent (same in light/dark) because they convey
 * meaning through color (success, error, info) rather than adapting to
 * the surface. Use MaterialTheme.colorScheme for surface-adaptive colors.
 *
 * Usage:
 * import com.example.smarty.ui.theme.SemanticColors
 * Icon(tint = SemanticColors.success)
 */
object SemanticColors {
 // Status Colors 
 /** Success / Active / Positive (iOS Green) */
 val success = Color(0xFF34C759)
 /** Error / Destructive / Danger (iOS Red) */
 val error = Color(0xFFFF3B30)
 /** Info / Interactive / Link (iOS Blue) */
 val info = Color(0xFF007AFF)
 /** Warning / Caution (iOS Yellow/Amber) */
 val warning = Color(0xFFEAB308)

 // Content Type Colors 
 /** Neutral / General file / Media (System Gray) */
 val neutral = Color(0xFF8E8E93)


 // Bubble Colors 
 /** User bubble background — inverted: light on dark, dark on light */
 val userBubbleLight = Color(0xFFF5F5F5)
 val userBubbleDark = Color(0xFF1A1A1A)
}

/**
 * Component-specific color roles for consistent application-wide styling.
 */
object ComponentColors {
 /**
 * Technical Surface (Glassy feel) Background.
 * Used in overlays and floating panels.
 * OPAQUE - No transparency for solid background
 */
 val technicalSurfaceLight = Color(0xFF1A1C1E) // Opaque dark
 val technicalSurfaceDark = Color(0xFF050E1E) // Opaque darker blue

 /** Breath instruction/skipped button color */
 val breathingAccent = Color(0xFF4FACFE)

 /** AI/Assistant Accent Colors (HAL aesthetic) */
 val assistantPurple = Color(0xFFB39DDB)
 val assistantCyan = Color(0xFF00F2FE)

 /** Voice Input Accent Color - Active/Listening state */
 val voiceAccent = ElectricBlue

 /** Input Field Background Colors */
 val inputPillBackgroundLight = Color(0xFFF2F2F7)
 val inputPillBackgroundDark = Color(0xFF1A1A1E) // Darker than default for better contrast
}
