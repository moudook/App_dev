package com.example.smarty.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.smarty.ui.LocalAccentColor

/**
 * Dark Color Scheme - Claude-Inspired Dark Mode
 * Background: #0D0C11 (deep charcoal), Surface: #181822 (elevated)
 * Primary: #2979FF (electric blue)
 */
private val DarkColorScheme = darkColorScheme(
    // Primary - Electric Blue (brighter for dark mode)
    primary = DarkElectricBlue,
    onPrimary = DarkTextPrimary,
    primaryContainer = DarkElectricBlue.copy(alpha = 0.15f),
    onPrimaryContainer = DarkElectricBlue,

    // Secondary - Pale Blue
    secondary = DarkPaleBlue,
    onSecondary = DarkTextPrimary,
    secondaryContainer = DarkPaleBlue.copy(alpha = 0.15f),
    onSecondaryContainer = DarkTextSecondary,

    // Tertiary - Safety Orange for warnings
    tertiary = SafetyOrange,
    onTertiary = DarkTextPrimary,
    tertiaryContainer = SafetyOrange.copy(alpha = 0.15f),
    onTertiaryContainer = SafetyOrange,

    // Background - Near black
    background = DarkBackground,
    onBackground = DarkTextPrimary,

    // Surface - Elevated dark card
    surface = DarkCard,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkInputBackground,
    onSurfaceVariant = DarkTextSecondary,

    // Borders - Subtle dark
    outline = DarkBorder,
    outlineVariant = DarkBorder.copy(alpha = 0.5f),

    // Error
    error = SafetyOrange,
    onError = DarkTextPrimary,
    errorContainer = SafetyOrange.copy(alpha = 0.15f),
    onErrorContainer = SafetyOrange,

    // Inverse
    inverseSurface = CardWhite,
    inverseOnSurface = TextNearBlack,
    inversePrimary = ElectricBlue,

    // Scrim
    scrim = DarkBackground.copy(alpha = 0.5f)
)

/**
 * Light Color Scheme - Modern Soft Minimalist
 * Background: #F2F4F8, Card: #FFFFFF, Primary: #0066FF
 */
private val LightColorScheme = lightColorScheme(
    // Primary - Electric Blue (vibrant)
    primary = ElectricBlue,
    onPrimary = CardWhite,
    primaryContainer = PaleBlueGrey,
    onPrimaryContainer = ElectricBlue,

    // Secondary - Pale Blue Grey
    secondary = TextCoolGrey,
    onSecondary = CardWhite,
    secondaryContainer = PaleBlueGrey,
    onSecondaryContainer = TextNearBlack,

    // Tertiary - Safety Orange for warnings
    tertiary = SafetyOrange,
    onTertiary = CardWhite,
    tertiaryContainer = SafetyOrange.copy(alpha = 0.1f),
    onTertiaryContainer = SafetyOrange,

    // Background - Soft light grey canvas
    background = SoftBackground,
    onBackground = TextNearBlack,

    // Surface - Pure white cards
    surface = CardWhite,
    onSurface = TextNearBlack,
    surfaceVariant = InputBackground,
    onSurfaceVariant = TextCoolGrey,

    // Borders - Very subtle
    outline = SubtleBorder,
    outlineVariant = SubtleBorder.copy(alpha = 0.5f),

    // Error
    error = SystemRed,
    onError = CardWhite,
    errorContainer = SystemRed.copy(alpha = 0.1f),
    onErrorContainer = SystemRed,

    // Inverse
    inverseSurface = DarkCard,
    inverseOnSurface = DarkTextPrimary,
    inversePrimary = DarkElectricBlue,

    // Scrim
    scrim = TextNearBlack.copy(alpha = 0.3f)
)

// Animation duration for smooth theme transition
// Increased for smoother visual experience
private const val THEME_ANIMATION_DURATION = 500

/**
 * Animate a single color transition
 */
@Composable
private fun animateColor(targetColor: Color): Color {
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = THEME_ANIMATION_DURATION),
        label = "themeColor"
    )
    return animatedColor
}

/**
 * Create an animated color scheme for smooth theme transitions
 */
@Composable
private fun animateColorScheme(targetScheme: ColorScheme): ColorScheme {
    return ColorScheme(
        primary = animateColor(targetScheme.primary),
        onPrimary = animateColor(targetScheme.onPrimary),
        primaryContainer = animateColor(targetScheme.primaryContainer),
        onPrimaryContainer = animateColor(targetScheme.onPrimaryContainer),
        inversePrimary = animateColor(targetScheme.inversePrimary),
        secondary = animateColor(targetScheme.secondary),
        onSecondary = animateColor(targetScheme.onSecondary),
        secondaryContainer = animateColor(targetScheme.secondaryContainer),
        onSecondaryContainer = animateColor(targetScheme.onSecondaryContainer),
        tertiary = animateColor(targetScheme.tertiary),
        onTertiary = animateColor(targetScheme.onTertiary),
        tertiaryContainer = animateColor(targetScheme.tertiaryContainer),
        onTertiaryContainer = animateColor(targetScheme.onTertiaryContainer),
        background = animateColor(targetScheme.background),
        onBackground = animateColor(targetScheme.onBackground),
        surface = animateColor(targetScheme.surface),
        onSurface = animateColor(targetScheme.onSurface),
        surfaceVariant = animateColor(targetScheme.surfaceVariant),
        onSurfaceVariant = animateColor(targetScheme.onSurfaceVariant),
        surfaceTint = animateColor(targetScheme.surfaceTint),
        inverseSurface = animateColor(targetScheme.inverseSurface),
        inverseOnSurface = animateColor(targetScheme.inverseOnSurface),
        error = animateColor(targetScheme.error),
        onError = animateColor(targetScheme.onError),
        errorContainer = animateColor(targetScheme.errorContainer),
        onErrorContainer = animateColor(targetScheme.onErrorContainer),
        outline = animateColor(targetScheme.outline),
        outlineVariant = animateColor(targetScheme.outlineVariant),
        scrim = animateColor(targetScheme.scrim),
        surfaceBright = animateColor(targetScheme.surfaceBright),
        surfaceDim = animateColor(targetScheme.surfaceDim),
        surfaceContainer = animateColor(targetScheme.surfaceContainer),
        surfaceContainerHigh = animateColor(targetScheme.surfaceContainerHigh),
        surfaceContainerHighest = animateColor(targetScheme.surfaceContainerHighest),
        surfaceContainerLow = animateColor(targetScheme.surfaceContainerLow),
        surfaceContainerLowest = animateColor(targetScheme.surfaceContainerLowest)
    )
}

/**
 * Theme mode options
 */
enum class ThemeMode {
    SYSTEM,  // Follow system
    LIGHT,   // Always light
    DARK     // Always dark
}

/**
 * Cogni Theme - Modern Soft Minimalist
 *
 * Design Archetype: "Clean Tech" / "Refined Bento-Grid"
 * - High readability, soft shadows, generous whitespace
 * - Electric Blue (#0066FF) accent against clean canvas
 * - Supports both dark and light themes
 * - Includes smooth animated transitions between themes
 * - System theme detection with manual override
 */
@Composable
fun CogniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Animate all colors for smooth theme transition
    val animatedColorScheme = animateColorScheme(targetColorScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        // BUG-057 fix: Use animated background to prevent flash during theme switch
        val animatedBackground = animatedColorScheme.background
        val animatedSurface = animatedColorScheme.surface

        SideEffect {
            val window = (view.context as Activity).window

            // Set window background to animated color for smooth transition
            window.decorView.setBackgroundColor(animatedBackground.toArgb())

            // Use semi-transparent system bars that blend with content
            // This provides a smoother visual experience during theme switch
            @Suppress("DEPRECATION")
            window.statusBarColor = animatedBackground.copy(alpha = 0.8f).toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = animatedSurface.copy(alpha = 0.9f).toArgb()

            // Update system bar icon colors based on theme
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalShapes provides CogniShapes(),
        LocalAccentColor provides animatedColorScheme.primary
    ) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = CogniTypography,
            content = content
        )
    }
}
