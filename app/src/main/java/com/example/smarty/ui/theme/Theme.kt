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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.smarty.ui.LocalAccentColor

/**
 * Dark Color Scheme - Monochrome Aesthetic
 * Background: #000000 (true black)
 * Primary: #FFFFFF (pure white)
 */
private val DarkColorScheme =
    darkColorScheme(
        primary = ThemeDarkPrimary,
        onPrimary = ThemeDarkOnPrimary,
        primaryContainer = ThemeDarkPrimary.copy(alpha = 0.15f),
        onPrimaryContainer = ThemeDarkPrimary,
        secondary = ThemeDarkTextSecondary,
        onSecondary = Color.White,
        secondaryContainer = ThemeDarkSurfaceVariant,
        onSecondaryContainer = Color.White,
        tertiary = ThemeDarkTextSecondary.copy(alpha = 0.7f),
        onTertiary = Color.White,
        tertiaryContainer = ThemeDarkSurfaceVariant.copy(alpha = 0.5f),
        onTertiaryContainer = Color.White,
        background = ThemeDarkBackground,
        onBackground = ThemeDarkTextPrimary,
        surface = ThemeDarkSurface,
        onSurface = ThemeDarkTextPrimary,
        surfaceVariant = ThemeDarkSurfaceVariant,
        onSurfaceVariant = ThemeDarkTextSecondary,
        outline = ThemeDarkOutline,
        outlineVariant = ThemeDarkOutline.copy(alpha = 0.5f),
        error = AppleError,
        onError = Color.White,
        errorContainer = AppleError.copy(alpha = 0.15f),
        onErrorContainer = AppleError,
        inverseSurface = ThemeLightSurface,
        inverseOnSurface = ThemeLightTextPrimary,
        inversePrimary = ThemeLightPrimary,
        scrim = Color.Black.copy(alpha = 0.5f),
    )

/**
 * Light Color Scheme - Monochrome Aesthetic
 * Background: #F2F1EE (Warm Stone)
 * Primary: #000000 (Pure Black)
 */
private val LightColorScheme =
    lightColorScheme(
        primary = ThemeLightPrimary,
        onPrimary = ThemeLightOnPrimary,
        primaryContainer = ThemeLightPrimary.copy(alpha = 0.15f),
        onPrimaryContainer = ThemeLightPrimary,
        secondary = ThemeLightTextSecondary,
        onSecondary = Color.White,
        secondaryContainer = ThemeLightSurfaceVariant,
        onSecondaryContainer = Color.Black,
        tertiary = ThemeLightTextSecondary.copy(alpha = 0.7f),
        onTertiary = Color.White,
        tertiaryContainer = ThemeLightSurfaceVariant.copy(alpha = 0.5f),
        onTertiaryContainer = Color.Black,
        background = ThemeLightBackground,
        onBackground = ThemeLightTextPrimary,
        surface = ThemeLightSurface,
        onSurface = ThemeLightTextPrimary,
        surfaceVariant = ThemeLightSurfaceVariant,
        onSurfaceVariant = ThemeLightTextSecondary,
        outline = ThemeLightOutline,
        outlineVariant = ThemeLightOutline.copy(alpha = 0.5f),
        error = AppleError,
        onError = Color.White,
        errorContainer = AppleError.copy(alpha = 0.1f),
        onErrorContainer = AppleError,
        inverseSurface = ThemeDarkSurface,
        inverseOnSurface = ThemeDarkTextPrimary,
        inversePrimary = ThemeDarkPrimary,
        scrim = Color.Black.copy(alpha = 0.3f),
    )

// Animation duration for smooth theme transition
// Standard Material duration for large area transitions
private const val THEME_ANIMATION_DURATION = 350

/**
 * Animate a single color transition
 */
@Composable
private fun animateColor(targetColor: Color): Color {
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec =
            tween(
                durationMillis = THEME_ANIMATION_DURATION,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        label = "themeColor",
    )
    return animatedColor
}

/**
 * Create an animated color scheme for smooth theme transitions
 */
@Composable
private fun animateColorScheme(targetScheme: ColorScheme): ColorScheme =
    ColorScheme(
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
        surfaceContainerLowest = animateColor(targetScheme.surfaceContainerLowest),
        primaryFixed = animateColor(targetScheme.primaryFixed),
        primaryFixedDim = animateColor(targetScheme.primaryFixedDim),
        onPrimaryFixed = animateColor(targetScheme.onPrimaryFixed),
        onPrimaryFixedVariant = animateColor(targetScheme.onPrimaryFixedVariant),
        secondaryFixed = animateColor(targetScheme.secondaryFixed),
        secondaryFixedDim = animateColor(targetScheme.secondaryFixedDim),
        onSecondaryFixed = animateColor(targetScheme.onSecondaryFixed),
        onSecondaryFixedVariant = animateColor(targetScheme.onSecondaryFixedVariant),
        tertiaryFixed = animateColor(targetScheme.tertiaryFixed),
        tertiaryFixedDim = animateColor(targetScheme.tertiaryFixedDim),
        onTertiaryFixed = animateColor(targetScheme.onTertiaryFixed),
        onTertiaryFixedVariant = animateColor(targetScheme.onTertiaryFixedVariant),
    )

/**
 * Theme mode options
 */
enum class ThemeMode {
    SYSTEM, // Follow system
    LIGHT, // Always light
    DARK, // Always dark
}

/**
 * Helper to get proper monochrome accent color based on active Theme
 * Handles Light/Dark mode correctly by checking surface luminance
 */
@Composable
fun rememberMonochromeAccent(): Color {
    // If surface is light (luminance > 0.5), we want BLACK text/accent
    // If surface is dark (luminance <= 0.5), we want WHITE text/accent
    val isLightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return if (isLightSurface) Color.Black else Color.White
}

/**
 * Smarty Theme - Monochrome Aesthetic
 *
 * Design Archetype: "Pure Monochrome" / "Minimalist Noir"
 * - High readability, soft shadows, generous whitespace
 * - Blacks and Whites for primary accents
 * - Supports both dark and light themes
 */
@Composable
fun SmartyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isTransparent: Boolean = false, // Set to true for transparent overlays (e.g., AssistActivity)
    content: @Composable () -> Unit,
) {
    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Animate all colors for smooth theme transition
    val animatedColorScheme = animateColorScheme(targetColorScheme)

    val view = LocalView.current
    if (!view.isInEditMode && !isTransparent) {
        // BUG-057 fix: Use animated background to prevent flash during theme switch
        // SKIP for transparent activities (AssistActivity) to preserve transparency
        val animatedBackground = animatedColorScheme.background
        val animatedSurface = animatedColorScheme.surface

        // Optimize: Cache Window and Controller to avoid repetitive lookups
        val context = view.context
        val window = remember(context) { (context as? Activity)?.window }
        val insetsController =
            remember(window, view) {
                window?.let { WindowCompat.getInsetsController(it, view) }
            }

        SideEffect {
            window?.let { win ->
                val decorView = win.decorView
                // Only update if changed to avoid unnecessary JNI calls
                val bgColorArgb = animatedBackground.toArgb()

                // Note: decorView.setBackgroundColor(bgColorArgb) is relatively cheap if hardware accelerated.
                decorView.setBackgroundColor(bgColorArgb)

                // Use semi-transparent system bars that blend with content
                @Suppress("DEPRECATION")
                val targetStatusBarColor = animatedBackground.copy(alpha = 0.8f).toArgb()
                @Suppress("DEPRECATION")
                if (win.statusBarColor != targetStatusBarColor) {
                    win.statusBarColor = targetStatusBarColor
                }

                @Suppress("DEPRECATION")
                val targetNavBarColor = animatedSurface.copy(alpha = 0.9f).toArgb()
                @Suppress("DEPRECATION")
                if (win.navigationBarColor != targetNavBarColor) {
                    win.navigationBarColor = targetNavBarColor
                }

                // Update system bar icon colors
                insetsController?.apply {
                    if (isAppearanceLightStatusBars != !darkTheme) {
                        isAppearanceLightStatusBars = !darkTheme
                    }
                    if (isAppearanceLightNavigationBars != !darkTheme) {
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalShapes provides SmartyShapes(),
        LocalAppleSpacing provides AppleSpacing(),
        LocalAppleShapes provides AppleShapes(),
        LocalAccentColor provides animatedColorScheme.primary,
    ) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = SmartyTypography,
            content = content,
        )
    }
}
