package com.example.smarty.ui.components.audio

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.theme.AudioPink
import com.example.smarty.ui.utils.AnimationMath
import kotlin.math.PI
import kotlin.math.min

/**
 * A living orb music visualizer that continuously reacts to audio amplitude.
 *
 * Design principles:
 * - No thresholds: Every amplitude value produces visible change
 * - Continuous motion: Multiple overlapping wave animations
 * - Layered depth: Core, glow, rings, and particles create 3D feel
 * - Mathematical smoothness: Uses sine waves and easing curves
 */
@Composable
fun LivingOrbVisualizer(
    isPlaying: Boolean,
    progress: Float,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    primaryColor: Color = AudioPink,
    secondaryColor: Color = AudioPink.copy(alpha = 0.5f),
    backgroundColor: Color = AudioPink.copy(alpha = 0.1f)
) {
    // Smooth amplitude with fast response for reactivity
    var smoothedAmplitude by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(amplitude, isPlaying) {
        // Fast attack, slower decay for punchy response
        val targetAmplitude = if (isPlaying) amplitude else 0f
        val smoothing = if (targetAmplitude > smoothedAmplitude) 0.4f else 0.15f
        smoothedAmplitude = AnimationMath.lerp(smoothedAmplitude, targetAmplitude, smoothing)
    }

    // Continuous time for wave animations
    val infiniteTransition = rememberInfiniteTransition(label = "orb_time")

    // Main time driver (0 to 1 over 2 seconds, repeating)
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Slower rotation for outer elements
    val slowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slow_rotation"
    )

    // Counter rotation for depth
    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_rotation"
    )

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val maxRadius = min(centerX, centerY)

        // Effective amplitude: always some base activity when playing
        val effectiveAmplitude = if (isPlaying) {
            0.15f + smoothedAmplitude * 0.85f // Minimum 15% activity
        } else {
            smoothedAmplitude * 0.3f // Fade out when stopped
        }

        // === LAYER 1: Outer Ripple Rings ===
        drawRippleRings(
            center = Offset(centerX, centerY),
            maxRadius = maxRadius,
            time = time,
            amplitude = effectiveAmplitude,
            color = primaryColor,
            isPlaying = isPlaying
        )

        // === LAYER 2: Pulsing Glow Field ===
        drawGlowField(
            center = Offset(centerX, centerY),
            maxRadius = maxRadius,
            time = time,
            amplitude = effectiveAmplitude,
            color = secondaryColor,
            isPlaying = isPlaying
        )

        // === LAYER 3: Orbiting Particles ===
        if (isPlaying || effectiveAmplitude > 0.05f) {
            drawOrbitingParticles(
                center = Offset(centerX, centerY),
                baseRadius = maxRadius * 0.55f,
                rotation = slowRotation,
                amplitude = effectiveAmplitude,
                color = primaryColor
            )
        }

        // === LAYER 4: Core Orb ===
        drawCoreOrb(
            center = Offset(centerX, centerY),
            maxRadius = maxRadius,
            time = time,
            amplitude = effectiveAmplitude,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            isPlaying = isPlaying
        )

        // === LAYER 5: Inner Highlight ===
        drawInnerHighlight(
            center = Offset(centerX, centerY),
            coreRadius = maxRadius * 0.35f * (1f + effectiveAmplitude * 0.3f),
            rotation = counterRotation
        )

        // === LAYER 6: Amplitude Ring ===
        if (isPlaying) {
            drawAmplitudeRing(
                center = Offset(centerX, centerY),
                baseRadius = maxRadius * 0.4f,
                amplitude = effectiveAmplitude,
                time = time,
                color = primaryColor
            )
        }
    }
}

/**
 * Draws continuous ripple rings that expand outward.
 * Multiple rings with staggered phases create flowing motion.
 */
private fun DrawScope.drawRippleRings(
    center: Offset,
    maxRadius: Float,
    time: Float,
    amplitude: Float,
    color: Color,
    isPlaying: Boolean
) {
    val ringCount = 3

    for (i in 0 until ringCount) {
        // Stagger each ring's phase
        val phase = (time + i * 0.33f) % 1f

        // Ring expands from 40% to 100% of max radius
        val ringRadius = maxRadius * (0.4f + phase * 0.6f)

        // Alpha fades as ring expands (inverse relationship)
        val baseAlpha = (1f - phase) * 0.6f
        val amplitudeBoost = amplitude * 0.4f
        val finalAlpha = (baseAlpha + amplitudeBoost) * if (isPlaying) 1f else 0.3f

        // Ring thickness varies with amplitude
        val strokeWidth = (1f + amplitude * 2f).dp.toPx()

        if (finalAlpha > 0.01f) {
            drawCircle(
                color = color.copy(alpha = finalAlpha.coerceIn(0f, 0.8f)),
                radius = ringRadius,
                center = center,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Draws a pulsing glow field behind the core orb.
 * Uses radial gradient with amplitude-reactive size.
 */
private fun DrawScope.drawGlowField(
    center: Offset,
    maxRadius: Float,
    time: Float,
    amplitude: Float,
    color: Color,
    isPlaying: Boolean
) {
    // Glow pulses with sine wave + amplitude
    val breathe = AnimationMath.sinWaveNormalized(time, 0.5f)
    val glowPulse = 0.9f + breathe * 0.2f + amplitude * 0.3f
    val glowRadius = maxRadius * 0.65f * glowPulse

    val glowAlpha = if (isPlaying) {
        0.25f + amplitude * 0.35f
    } else {
        0.1f + amplitude * 0.1f
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = glowAlpha),
                color.copy(alpha = glowAlpha * 0.5f),
                color.copy(alpha = glowAlpha * 0.2f),
                Color.Transparent
            ),
            center = center,
            radius = glowRadius * 1.5f
        ),
        radius = glowRadius * 1.5f,
        center = center
    )
}

/**
 * Draws small particles orbiting around the core.
 * Creates dynamic, living feel.
 * Optimized: Uses fast sin/cos approximations.
 */
private fun DrawScope.drawOrbitingParticles(
    center: Offset,
    baseRadius: Float,
    rotation: Float,
    amplitude: Float,
    color: Color
) {
    val particleCount = 6
    val rotationRad = rotation * PI.toFloat() / 180f

    for (i in 0 until particleCount) {
        val angle = rotationRad + (i * 2f * PI.toFloat() / particleCount)

        // Radius varies with amplitude (breathing effect) - using fast sin
        val radiusVariation = 1f + AnimationMath.fastSin(angle * 2f) * amplitude * 0.2f
        val particleRadius = baseRadius * radiusVariation

        // Using fast sin/cos for position calculation
        val x = center.x + AnimationMath.fastCos(angle) * particleRadius
        val y = center.y + AnimationMath.fastSin(angle) * particleRadius

        // Particle size based on amplitude
        val particleSize = (1.5f + amplitude * 2f).dp.toPx()

        // Alpha varies by position for depth - using fast sin
        val particleAlpha = 0.4f + amplitude * 0.4f + AnimationMath.fastSin(angle) * 0.2f

        drawCircle(
            color = color.copy(alpha = particleAlpha.coerceIn(0.2f, 0.9f)),
            radius = particleSize,
            center = Offset(x, y)
        )
    }
}

/**
 * Draws the main core orb with gradient.
 * Size and intensity react to amplitude.
 */
private fun DrawScope.drawCoreOrb(
    center: Offset,
    maxRadius: Float,
    time: Float,
    amplitude: Float,
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean
) {
    // Core size: base + amplitude + subtle breathing
    val breathe = AnimationMath.sinWaveNormalized(time, 0.7f)
    val baseScale = if (isPlaying) 1f else 0.9f
    val coreScale = baseScale + amplitude * 0.35f + breathe * 0.05f
    val coreRadius = maxRadius * 0.35f * coreScale

    // Alpha increases with amplitude
    val coreAlpha = if (isPlaying) {
        0.75f + amplitude * 0.25f
    } else {
        0.5f + amplitude * 0.2f
    }

    // Draw core with radial gradient
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                primaryColor.copy(alpha = coreAlpha),
                primaryColor.copy(alpha = coreAlpha * 0.85f),
                secondaryColor.copy(alpha = coreAlpha * 0.6f)
            ),
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center
    )
}

/**
 * Draws a small highlight on the core for 3D depth.
 * Optimized: Uses fast sin/cos approximations.
 */
private fun DrawScope.drawInnerHighlight(
    center: Offset,
    coreRadius: Float,
    rotation: Float
) {
    val rotationRad = rotation * PI.toFloat() / 180f

    // Highlight offset from center (top-left area) - using fast sin/cos
    val offsetX = AnimationMath.fastCos(rotationRad) * coreRadius * 0.15f
    val offsetY = -coreRadius * 0.3f + AnimationMath.fastSin(rotationRad) * coreRadius * 0.1f

    val highlightCenter = Offset(center.x + offsetX, center.y + offsetY)
    val highlightRadius = coreRadius * 0.35f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.4f),
                Color.White.copy(alpha = 0.15f),
                Color.Transparent
            ),
            center = highlightCenter,
            radius = highlightRadius
        ),
        radius = highlightRadius,
        center = highlightCenter
    )
}

/**
 * Draws a ring around the core that pulses with amplitude.
 * Thickness and opacity directly tied to audio level.
 */
private fun DrawScope.drawAmplitudeRing(
    center: Offset,
    baseRadius: Float,
    amplitude: Float,
    time: Float,
    color: Color
) {
    // Ring expands with amplitude
    val ringRadius = baseRadius * (1.15f + amplitude * 0.25f)

    // Thickness scales with amplitude (always visible when playing)
    val minThickness = 0.5f.dp.toPx()
    val maxThickness = 3f.dp.toPx()
    val thickness = minThickness + amplitude * (maxThickness - minThickness)

    // Alpha: base visibility + amplitude boost
    val ringAlpha = 0.2f + amplitude * 0.6f

    // Subtle wave distortion for organic feel
    val waveOffset = AnimationMath.sinWave(time, 2f, amplitude * 0.1f)

    drawCircle(
        color = color.copy(alpha = ringAlpha.coerceIn(0.1f, 0.8f)),
        radius = ringRadius + waveOffset * baseRadius,
        center = center,
        style = Stroke(width = thickness, cap = StrokeCap.Round)
    )
}
