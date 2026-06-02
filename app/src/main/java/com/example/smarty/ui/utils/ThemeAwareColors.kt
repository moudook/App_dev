package com.example.smarty.ui.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Theme-Aware Colors Utility.
 *
 * Single Responsibility: Only provides theme-aware color calculations.
 * DRY: Replaces repeated luminance checks and color calculations.
 *
 * Usage:
 * ```
 * // Get theme-aware background
 * val background = ThemeAwareColors.surfaceBackground()
 *
 * // Get theme-aware variant
 * val variant = ThemeAwareColors.surfaceVariant()
 *
 * // Check if dark theme
 * if (ThemeAwareColors.isDark()) { ... }
 * ```
 */
object ThemeAwareColors {
    /**
     * Check if current theme is dark.
     */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    /**
     * Get surface background color based on theme.
     */
    @Composable
    @ReadOnlyComposable
    fun surfaceBackground(): Color =
        if (isDark()) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    /**
     * Get surface variant color with proper alpha.
     */
    @Composable
    @ReadOnlyComposable
    fun surfaceVariant(alpha: Float = 1f): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    /**
     * Get outline variant color based on theme.
     */
    @Composable
    @ReadOnlyComposable
    fun outlineVariant(alpha: Float = 0.3f): Color =
        if (isDark()) {
            Color.White.copy(alpha = alpha)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)
        }

    /**
     * Get input pill background color.
     */
    @Composable
    @ReadOnlyComposable
    fun inputPillBackground(): Color =
        if (isDark()) {
            Color(0xFF1E1E1E)
        } else {
            Color(0xFFFFF5F7)
        }

    /**
     * Get input pill border color.
     */
    @Composable
    @ReadOnlyComposable
    fun inputPillBorder(): Color =
        if (isDark()) {
            Color.White.copy(alpha = 0.15f)
        } else {
            Color(0xFFE8B4C7)
        }

    /**
     * Get card background color.
     */
    @Composable
    @ReadOnlyComposable
    fun cardBackground(): Color =
        if (isDark()) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    /**
     * Get card border color.
     */
    @Composable
    @ReadOnlyComposable
    fun cardBorder(): Color = outlineVariant(0.2f)

    /**
     * Get dialog background.
     */
    @Composable
    @ReadOnlyComposable
    fun dialogBackground(): Color =
        if (isDark()) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    /**
     * Get dialog border color.
     */
    @Composable
    @ReadOnlyComposable
    fun dialogBorder(): Color = outlineVariant(0.4f)

    /**
     * Get menu background.
     */
    @Composable
    @ReadOnlyComposable
    fun menuBackground(): Color = dialogBackground()

    /**
     * Get menu border color.
     */
    @Composable
    @ReadOnlyComposable
    fun menuBorder(): Color = dialogBorder()

    /**
     * Get text color based on theme and emphasis.
     */
    @Composable
    @ReadOnlyComposable
    fun textColor(emphasis: TextEmphasis = TextEmphasis.HIGH): Color =
        when (emphasis) {
            TextEmphasis.HIGH -> MaterialTheme.colorScheme.onSurface
            TextEmphasis.MEDIUM -> MaterialTheme.colorScheme.onSurfaceVariant
            TextEmphasis.LOW -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }

    /**
     * Get icon color based on theme and emphasis.
     */
    @Composable
    @ReadOnlyComposable
    fun iconColor(emphasis: IconEmphasis = IconEmphasis.HIGH): Color =
        when (emphasis) {
            IconEmphasis.HIGH -> MaterialTheme.colorScheme.onSurface
            IconEmphasis.MEDIUM -> MaterialTheme.colorScheme.onSurfaceVariant
            IconEmphasis.LOW -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }

    /**
     * Get accent color with proper contrast.
     */
    @Composable
    @ReadOnlyComposable
    fun accentColor(): Color = MaterialTheme.colorScheme.primary

    /**
     * Get error color.
     */
    @Composable
    @ReadOnlyComposable
    fun errorColor(): Color = MaterialTheme.colorScheme.error

    /**
     * Get success color (green).
     */
    @Composable
    @ReadOnlyComposable
    fun successColor(): Color = Color(0xFF4CAF50)

    /**
     * Get warning color (orange).
     */
    @Composable
    @ReadOnlyComposable
    fun warningColor(): Color = Color(0xFFFF9800)
}

/**
 * Text emphasis levels.
 */
enum class TextEmphasis {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Icon emphasis levels.
 */
enum class IconEmphasis {
    HIGH,
    MEDIUM,
    LOW,
}
