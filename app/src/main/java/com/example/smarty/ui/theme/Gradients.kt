package com.example.smarty.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Centralized Gradient Brushes for the Smarty App.
 * These tokens represent complex visual styles used in specific components.
 */
object SmartyBrushes {

    /** Bottom Scrim Gradient Colors - Light Theme (Lavender Blush Pink) */
    val bottomScrimLightPink = Color(0xFFFFF0F5)

    /** Bottom Scrim Gradient - Light Theme (Pinkish, more opaque/dense) */
    val bottomScrimLight = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            bottomScrimLightPink.copy(alpha = 0.5f),
            bottomScrimLightPink.copy(alpha = 0.75f),
            bottomScrimLightPink.copy(alpha = 0.92f),
            bottomScrimLightPink.copy(alpha = 0.98f),
            bottomScrimLightPink
        )
    )

    /** Bottom Scrim Gradient - Dark Theme */
    val bottomScrimDark = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            Color.Black.copy(alpha = 0.5f),
            Color.Black.copy(alpha = 0.75f),
            Color.Black.copy(alpha = 0.92f),
            Color.Black.copy(alpha = 0.98f),
            Color.Black
        )
    )

    /** Top Scrim Gradient - Light Theme (Pinkish, matches bottom) */
    val topScrimLight = Brush.verticalGradient(
        colors = listOf(
            bottomScrimLightPink,
            bottomScrimLightPink.copy(alpha = 0.98f),
            bottomScrimLightPink.copy(alpha = 0.92f),
            bottomScrimLightPink.copy(alpha = 0.75f),
            bottomScrimLightPink.copy(alpha = 0.5f),
            Color.Transparent
        )
    )

    /** Top Scrim Gradient - Dark Theme (matches bottom) */
    val topScrimDark = Brush.verticalGradient(
        colors = listOf(
            Color.Black,
            Color.Black.copy(alpha = 0.98f),
            Color.Black.copy(alpha = 0.92f),
            Color.Black.copy(alpha = 0.75f),
            Color.Black.copy(alpha = 0.5f),
            Color.Transparent
        )
    )

    /** Metallic Silver gradient for the Coin Toss / Technical elements */
    val metallicSilver = Brush.linearGradient(
        colors = listOf(
            Color(0xFFE0E0E0),
            Color(0xFFFFFFFF),
            Color(0xFFB0B0B0),
            Color(0xFFFFFFFF),
            Color(0xFFE0E0E0)
        ),
        start = Offset(0f, 0f),
        end = Offset(100f, 1000f)
    )

    /** Deep Navy gradient for the Guided Breathing / Zen backgrounds */
    val zenBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1A1A2E),
            Color(0xFF16213E),
            Color(0xFF0F3460)
        )
    )

    /** Cyan/LightBlue gradient for the Breathing Circle */
    val breathingCircle = Brush.radialGradient(
        colors = listOf(
            Color(0xFF4FACFE),
            Color(0xFF00F2FE),
            Color(0xFF4FACFE).copy(alpha = 0.3f)
        )
    )

    /** Technical Gray gradient for the Geometric Background */
    val technicalBackground = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8E8E93),
            Color(0xFFC7C7CC),
            Color(0xFF000000)
        )
    )
    
    /** Soft glowing white for inner overlays */
    val innerGlow = Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.3f),
            Color.Transparent
        )
    )
}
