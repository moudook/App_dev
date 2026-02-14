package com.example.smarty.features.chat.ui.thinking

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A high-end, organic thinking indicator that feels fluid and alive.
 * Replaces traditional dots with a pulsing, breathing monochrome orb.
 *
 * Design:
 * - Monochrome (Silver/Grey)
 * - Fluid breathing animation
 * - Layered soft glows
 */
@Composable
fun OrganicThinkingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    baseColor: Color = Color.Gray.copy(alpha = 0.6f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrganicThinking")

    // Breathing phase for the radius and alpha
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "BreathePhase"
    )

    // Secondary wobble phase for organic feel
    val wobble by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WobblePhase"
    )

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val center = Offset(centerX, centerY)
        val maxRadius = this.size.width / 2

        // Main breathing calculation
        val breatheFactor = (sin(phase * 2 * PI.toFloat()) + 1f) / 2f // 0 to 1
        val wobbleFactor = sin(wobble * 2 * PI.toFloat()) * 0.1f // small fluctuation

        // Layers of the organic orb

        // 1. Outer Soft Glow (Pulse)
        val outerRadius = maxRadius * (0.6f + breatheFactor * 0.4f + wobbleFactor)
        val outerAlpha = (0.1f + breatheFactor * 0.15f).coerceIn(0f, 0.3f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    baseColor.copy(alpha = outerAlpha),
                    Color.Transparent
                ),
                center = center,
                radius = outerRadius
            ),
            radius = outerRadius,
            center = center
        )

        // 2. Inner Core (Fluid)
        val coreRadius = maxRadius * (0.35f + breatheFactor * 0.15f + wobbleFactor * 0.5f)
        val coreAlpha = (0.4f + breatheFactor * 0.2f).coerceIn(0f, 0.8f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    baseColor.copy(alpha = coreAlpha),
                    baseColor.copy(alpha = coreAlpha * 0.5f),
                    Color.Transparent
                ),
                center = center,
                radius = coreRadius * 1.5f
            ),
            radius = coreRadius * 1.5f,
            center = center
        )

        // 3. Solid Center (The "Heart")
        val heartRadius = coreRadius * 0.6f
        drawCircle(
            color = baseColor.copy(alpha = coreAlpha + 0.1f),
            radius = heartRadius,
            center = center
        )

        // Highlight (makes it feel 3D/glassy)
        drawCircle(
            color = Color.White.copy(alpha = 0.3f),
            radius = heartRadius * 0.4f,
            center = Offset(centerX - heartRadius * 0.2f, centerY - heartRadius * 0.2f)
        )
    }
}
