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
 * Dark Color Scheme - Modern Soft Minimalist (Dark Mode)
 * Background: #111111, Card: #1C1C1E, Primary: #2979FF
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
private const val THEME_ANIMATION_DURATION = 400

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
        SideEffect {
            val window = (view.context as Activity).window
            // Use transparent status bar for edge-to-edge
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
            // Set window background to animated background color to prevent flash
            window.decorView.setBackgroundColor(animatedBackground.toArgb())
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
