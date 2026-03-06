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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.smarty.ui.LocalAccentColor

/**
 * Thinking Section Specific Colors
 */
data class ThinkingColors(
    val background: Color,
    val border: Color,
    val text: Color
)

val LocalThinkingColors = staticCompositionLocalOf {
    ThinkingColors(
        background = Color.Transparent,
        border = Color.Transparent,
        text = Color.Unspecified
    )
}

/**
 * Access thinking colors from any composable
 */
val MaterialTheme.thinkingColors: ThinkingColors
    @Composable
    @ReadOnlyComposable
    get() = LocalThinkingColors.current

/**
 * Dark Color Scheme - Monochrome Aesthetic
 * Background: #000000 (true black)
 * Primary: #FFFFFF (pure white)
 */
private val DarkColorScheme = darkColorScheme(
    // Primary - Monochrome White
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White.copy(alpha = 0.15f),
    onPrimaryContainer = Color.White,

    // Secondary - Grayscale
    secondary = DarkTextSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color.White.copy(alpha = 0.1f),
    onSecondaryContainer = Color.White,

    // Tertiary - Grayscale
    tertiary = Color.White.copy(alpha = 0.7f),
    onTertiary = Color.White,
    tertiaryContainer = Color.White.copy(alpha = 0.1f),
    onTertiaryContainer = Color.White,

    // Background - True black for OLED
    background = Color.Black,
    onBackground = Color.White,

    // Surface - Near black card
    surface = Color(0xFF0A0A0A),
    onSurface = Color.White,
    surfaceVariant = SmartyChipGrayDark,
    onSurfaceVariant = DarkTextSecondary,

    // Borders - Subtle dark
    outline = SmartyChipSeparatorDark,
    outlineVariant = DarkBorder.copy(alpha = 0.5f),

    // Error - System Red (Calmer than Safety Orange)
    error = SystemRed,
    onError = Color.White,
    errorContainer = SystemRed.copy(alpha = 0.15f),
    onErrorContainer = SystemRed,

    // Inverse
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = Color.Black,

    // Scrim
    scrim = Color.Black.copy(alpha = 0.5f)
)

/**
 * Light Color Scheme - Monochrome Aesthetic
 * Background: #F2F1EE (Warm Stone)
 * Primary: #000000 (Pure Black)
 */
private val LightColorScheme = lightColorScheme(
    // Primary - Pink Theme
    primary = PinkAccent,
    onPrimary = PinkDark,
    primaryContainer = PinkLight,
    onPrimaryContainer = PinkDark,

    // Secondary - Grayscale
    secondary = TextCoolGrey,
    onSecondary = Color.White,
    secondaryContainer = Color.Black.copy(alpha = 0.05f),
    onSecondaryContainer = Color.Black,

    // Tertiary - Grayscale
    tertiary = Color.Black.copy(alpha = 0.7f),
    onTertiary = Color.White,
    tertiaryContainer = Color.Black.copy(alpha = 0.05f),
    onTertiaryContainer = Color.Black,

    // Background - Warm light grey canvas
    background = SoftBackground,
    onBackground = Color.Black,

    // Surface - Pure white cards
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = SmartyChipGrayLight,
    onSurfaceVariant = TextCoolGrey,

    // Borders - Very subtle
    outline = SmartyChipSeparatorLight,
    outlineVariant = SubtleBorder.copy(alpha = 0.5f),

    // Error
    error = SystemRed,
    onError = Color.White,
    errorContainer = SystemRed.copy(alpha = 0.1f),
    onErrorContainer = SystemRed,

    // Inverse
    inverseSurface = Color(0xFF0A0A0A),
    inverseOnSurface = Color.White,
    inversePrimary = Color.White,

    // Scrim
    scrim = Color.Black.copy(alpha = 0.3f)
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
        animationSpec = tween(
            durationMillis = THEME_ANIMATION_DURATION,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
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
    content: @Composable () -> Unit
) {
    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Animate all colors for smooth theme transition
    val animatedColorScheme = animateColorScheme(targetColorScheme)

    // Setup Thinking Section Colors based on theme
    val thinkingColors = remember(darkTheme) {
        if (darkTheme) {
            ThinkingColors(
                background = ThinkingBackgroundDark,
                border = ThinkingBorderDark,
                text = ThinkingTextDark
            )
        } else {
            ThinkingColors(
                background = ThinkingBackgroundLight,
                border = ThinkingBorderLight,
                text = ThinkingTextLight
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode && !isTransparent) {
        // BUG-057 fix: Use animated background to prevent flash during theme switch
        // SKIP for transparent activities (AssistActivity) to preserve transparency
        val animatedBackground = animatedColorScheme.background
        val animatedSurface = animatedColorScheme.surface

        // Optimize: Cache Window and Controller to avoid repetitive lookups
        val context = view.context
        val window = remember(context) { (context as? Activity)?.window }
        val insetsController = remember(window, view) {
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
        LocalThinkingColors provides thinkingColors,
        LocalAccentColor provides animatedColorScheme.primary
    ) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = SmartyTypography,
            content = content
        )
    }
}
