package com.example.smarty.ui.components.audio

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A calming "Breathing Orb" animation - smooth, circular, and gentle.
 * 
 * Design principles for comfort:
 * - Perfect circular shapes (no irregular blobs)
 * - Slow, predictable breathing motion
 * - Soft gradients with low contrast
 * - Minimal movement (no wandering nucleus)
 * - Gentle floating particles (not orbiting chaotically)
 */
@Composable
fun LivingOrganism(
    isPlaying: Boolean,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = Color(0xFFE91E63)
) {
    // Smooth amplitude for gentle reaction
    val smoothedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 0.15f + amplitude * 0.5f else 0.1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "amplitude"
    )

    // Very slow breathing cycle - 16 seconds for one complete breath
    val infiniteTransition = rememberInfiniteTransition(label = "calm_breathing")
    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val maxRadius = minOf(centerX, centerY)
        val center = Offset(centerX, centerY)
        
        // Gentle breathing - very subtle size change
        val breathScale = 1f + 0.03f * sin(breathPhase)
        val baseRadius = maxRadius * 0.55f * breathScale * (0.9f + smoothedAmplitude * 0.2f)

        // Layer 1: Outer soft glow (largest, most transparent)
        drawCalmCircle(
            center = center,
            radius = baseRadius * 1.6f,
            color = color.copy(alpha = 0.08f),
            breathPhase = breathPhase,
            layerOffset = 0f
        )

        // Layer 2: Middle glow
        drawCalmCircle(
            center = center,
            radius = baseRadius * 1.3f,
            color = color.copy(alpha = 0.15f),
            breathPhase = breathPhase,
            layerOffset = 0.5f
        )

        // Layer 3: Core orb (solid but soft)
        drawCalmCircle(
            center = center,
            radius = baseRadius,
            color = color.copy(alpha = 0.5f),
            breathPhase = breathPhase,
            layerOffset = 1f
        )

        // Layer 4: Inner bright center
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.6f),
                    Color.White.copy(alpha = 0.2f),
                    Color.Transparent
                ),
                center = center,
                radius = baseRadius * 0.5f
            ),
            radius = baseRadius * 0.5f,
            center = center
        )

        // Gentle floating particles (stationary with subtle pulse)
        if (isPlaying || smoothedAmplitude > 0.12f) {
            drawGentleParticles(
                center = center,
                radius = baseRadius,
                breathPhase = breathPhase,
                color = color
            )
        }
    }
}

/**
 * Draws a perfectly circular layer with soft radial gradient.
 * No distortion, just gentle opacity pulsing.
 */
private fun DrawScope.drawCalmCircle(
    center: Offset,
    radius: Float,
    color: Color,
    breathPhase: Float,
    layerOffset: Float
) {
    // Very subtle opacity pulse - synchronized with breath but offset per layer
    val opacityPulse = 1f + 0.1f * sin(breathPhase + layerOffset * PI.toFloat())
    val adjustedAlpha = (color.alpha * opacityPulse).coerceIn(0f, 1f)
    
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = adjustedAlpha),
                color.copy(alpha = adjustedAlpha * 0.5f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * Draws gentle, mostly stationary particles that pulse in opacity.
 * They don't orbit chaotically - just float in place with gentle twinkling.
 */
private fun DrawScope.drawGentleParticles(
    center: Offset,
    radius: Float,
    breathPhase: Float,
    color: Color
) {
    val particleCount = 8  // Fewer particles for less visual noise
    val orbitRadius = radius * 1.5f
    
    // Fixed positions for particles - no random movement
    val particleAngles = floatArrayOf(
        0.3f, 0.9f, 1.6f, 2.3f, 3.1f, 3.8f, 4.6f, 5.4f
    )
    val particleDistances = floatArrayOf(
        1.0f, 1.1f, 0.95f, 1.05f, 1.0f, 0.9f, 1.08f, 0.98f
    )
    
    for (i in 0 until particleCount) {
        // Fixed angle - particles don't orbit, just stay in place
        val angle = particleAngles[i]
        val distance = orbitRadius * particleDistances[i]
        
        // Very subtle floating motion (not orbiting, just gentle drift)
        val floatX = sin(breathPhase + i * 0.5f) * 3f  // Just 3 pixels of drift
        val floatY = cos(breathPhase + i * 0.7f) * 3f
        
        val pX = center.x + cos(angle) * distance + floatX
        val pY = center.y + sin(angle) * distance + floatY
        
        // Gentle size pulse
        val sizePulse = 1f + 0.2f * sin(breathPhase + i)
        val particleSize = 2.5f.dp.toPx() * sizePulse
        
        // Gentle opacity pulse (twinkling)
        val alphaPulse = 0.25f + 0.15f * sin(breathPhase * 0.5f + i * 0.8f)
        
        drawCircle(
            color = color.copy(alpha = alphaPulse.coerceIn(0.1f, 0.4f)),
            radius = particleSize,
            center = Offset(pX, pY)
        )
    }
}
