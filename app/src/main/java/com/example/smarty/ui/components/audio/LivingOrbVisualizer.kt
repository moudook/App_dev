package com.example.smarty.ui.components.audio

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.theme.AudioPink
import com.example.smarty.ui.utils.AnimationMath
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.min

/**
 * A vibrant "Living Pulse" music visualizer that feels ALIVE.
 *
 * Key features:
 * - Pulsing frequency bars that react to bass
 * - Spinning orbital rings that accelerate with treble
 * - A breathing core that pumps with the beat
 * - Energy particles that burst on peaks
 * - CONTINUOUS motion - never stops animating
 */
@Composable
fun LivingOrbVisualizer(
    isPlaying: Boolean,
    progress: Float,
    amplitude: Float = 0f,
    bass: Float = 0f,
    mid: Float = 0f,
    treble: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    primaryColor: Color = AudioPink,
    secondaryColor: Color = AudioPink.copy(alpha = 0.5f),
    backgroundColor: Color = AudioPink.copy(alpha = 0.1f)
) {
    // Smoothed values - updated CONTINUOUSLY via animation loop
    var smoothedBass by remember { mutableFloatStateOf(0f) }
    var smoothedMid by remember { mutableFloatStateOf(0f) }
    var smoothedTreble by remember { mutableFloatStateOf(0f) }
    var peakBass by remember { mutableFloatStateOf(0f) }

    // CONTINUOUS animation loop - runs every frame regardless of input changes
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16) // ~60fps
            // Always interpolate towards current targets
            val targetBass = if (isPlaying) bass else 0f
            val targetMid = if (isPlaying) mid else 0f
            val targetTreble = if (isPlaying) treble else 0f

            // Responsive attack, smooth decay
            smoothedBass = AnimationMath.lerp(smoothedBass, targetBass, if (targetBass > smoothedBass) 0.4f else 0.12f)
            smoothedMid = AnimationMath.lerp(smoothedMid, targetMid, if (targetMid > smoothedMid) 0.35f else 0.1f)
            smoothedTreble = AnimationMath.lerp(smoothedTreble, targetTreble, if (targetTreble > smoothedTreble) 0.5f else 0.15f)

            // Peak tracking
            if (targetBass > peakBass) peakBass = targetBass
            else peakBass = AnimationMath.lerp(peakBass, 0f, 0.06f)
        }
    }

    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    // OPTIMIZED: Single infinite transition for all animations (reduces CPU overhead)
    val infiniteTransition = rememberInfiniteTransition(label = "orb_animations")

    // Main time - continuous pulse
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )
    val effectiveTime = if (shouldAnimate) time else 0f

    // Spin rotation - continuous
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )
    val baseSpinSpeed = if (shouldAnimate) spin else 0f

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val maxRadius = min(centerX, centerY)
        val center = Offset(centerX, centerY)

        // Fallback to amplitude if no frequency data
        val hasBands = bass + mid + treble > 0.01f
        val activeBass = if (hasBands) smoothedBass else amplitude
        val activeMid = if (hasBands) smoothedMid else amplitude * 0.7f
        val activeTreble = if (hasBands) smoothedTreble else amplitude * 0.5f
        val activePeak = if (hasBands) peakBass else amplitude

        // === LAYER 1: OUTER GLOW (Always breathing) ===
        drawBreathingGlow(
            center = center,
            maxRadius = maxRadius,
            time = effectiveTime,
            energy = activeMid,
            color = secondaryColor,
            isPlaying = isPlaying
        )

        // === LAYER 2: FREQUENCY BARS (Bass reactive) ===
        drawFrequencyBars(
            center = center,
            maxRadius = maxRadius,
            bassEnergy = activeBass,
            midEnergy = activeMid,
            trebleEnergy = activeTreble,
            rotation = baseSpinSpeed,
            color = primaryColor,
            isPlaying = isPlaying
        )

        // === LAYER 3: ORBITAL RINGS (Treble accelerates) ===
        drawOrbitalRings(
            center = center,
            maxRadius = maxRadius * 0.7f,
            rotation = baseSpinSpeed + (activeTreble * 180f),
            energy = activeTreble,
            color = primaryColor
        )

        // === LAYER 4: PULSING CORE (Bass pumps) ===
        drawPulsingCore(
            center = center,
            maxRadius = maxRadius * 0.35f,
            bassEnergy = activeBass,
            time = effectiveTime,
            color = primaryColor
        )

        // === LAYER 5: PEAK BURST (On heavy beats) ===
        if (activePeak > 0.4f) {
            drawPeakBurst(
                center = center,
                maxRadius = maxRadius,
                peakEnergy = activePeak,
                color = primaryColor
            )
        }
    }
}

/**
 * Soft outer glow that breathes continuously
 */
private fun DrawScope.drawBreathingGlow(
    center: Offset,
    maxRadius: Float,
    time: Float,
    energy: Float,
    color: Color,
    isPlaying: Boolean
) {
    val breathe = AnimationMath.sinWaveNormalized(time, 0.5f)
    val baseAlpha = if (isPlaying) 0.15f else 0.08f
    val glowRadius = maxRadius * (0.9f + breathe * 0.1f + energy * 0.15f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = baseAlpha + energy * 0.15f),
                color.copy(alpha = baseAlpha * 0.5f),
                Color.Transparent
            ),
            center = center,
            radius = glowRadius
        ),
        radius = glowRadius,
        center = center
    )
}

/**
 * Frequency bars radiating from center - the heart of the visualizer feel
 */
private fun DrawScope.drawFrequencyBars(
    center: Offset,
    maxRadius: Float,
    bassEnergy: Float,
    midEnergy: Float,
    trebleEnergy: Float,
    rotation: Float,
    color: Color,
    isPlaying: Boolean
) {
    val barCount = 12
    val baseLength = maxRadius * 0.25f
    val maxLength = maxRadius * 0.5f

    rotate(degrees = rotation, pivot = center) {
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f
            val angleRad = angle * (PI.toFloat() / 180f)

            // Each bar responds to different frequency based on position
            val freqResponse = when {
                i % 3 == 0 -> bassEnergy
                i % 3 == 1 -> midEnergy
                else -> trebleEnergy
            }

            // Bar length based on frequency + always some base movement
            val baseMotion = if (isPlaying) 0.3f else 0.1f
            val barLength = baseLength + (maxLength - baseLength) * (baseMotion + freqResponse * 0.7f)

            // Starting position (inside the core)
            val innerRadius = maxRadius * 0.4f
            val startX = center.x + AnimationMath.fastCos(angleRad) * innerRadius
            val startY = center.y + AnimationMath.fastSin(angleRad) * innerRadius

            // End position
            val endX = center.x + AnimationMath.fastCos(angleRad) * (innerRadius + barLength)
            val endY = center.y + AnimationMath.fastSin(angleRad) * (innerRadius + barLength)

            // Width and alpha based on energy
            val barWidth = (2f + freqResponse * 3f).dp.toPx()
            val barAlpha = (0.5f + freqResponse * 0.5f).coerceIn(0.3f, 1f)

            drawLine(
                color = color.copy(alpha = barAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Spinning orbital rings - speed responds to treble
 */
private fun DrawScope.drawOrbitalRings(
    center: Offset,
    maxRadius: Float,
    rotation: Float,
    energy: Float,
    color: Color
) {
    val ringCount = 2

    for (i in 0 until ringCount) {
        val ringRotation = rotation + (i * 90f)
        val ringRadius = maxRadius * (0.8f + i * 0.15f)
        val arcSweep = 60f + energy * 30f

        rotate(degrees = ringRotation, pivot = center) {
            val path = Path().apply {
                val startAngle = -arcSweep / 2
                addArc(
                    oval = androidx.compose.ui.geometry.Rect(
                        center.x - ringRadius,
                        center.y - ringRadius,
                        center.x + ringRadius,
                        center.y + ringRadius
                    ),
                    startAngleDegrees = startAngle,
                    sweepAngleDegrees = arcSweep
                )
            }

            drawPath(
                path = path,
                color = color.copy(alpha = 0.6f + energy * 0.4f),
                style = Stroke(width = (1.5f + energy * 2f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * The core that pulses with bass - the heartbeat
 */
private fun DrawScope.drawPulsingCore(
    center: Offset,
    maxRadius: Float,
    bassEnergy: Float,
    time: Float,
    color: Color
) {
    val pump = bassEnergy * bassEnergy
    val breathe = AnimationMath.sinWaveNormalized(time, 0.3f) * 0.1f
    val coreRadius = maxRadius * (0.7f + pump * 0.5f + breathe)

    drawCircle(
        color = color.copy(alpha = 0.9f),
        radius = coreRadius,
        center = center
    )

    val highlightOffset = Offset(center.x - coreRadius * 0.2f, center.y - coreRadius * 0.25f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f),
                Color.White.copy(alpha = 0.15f),
                Color.Transparent
            ),
            center = highlightOffset,
            radius = coreRadius * 0.4f
        ),
        radius = coreRadius * 0.5f,
        center = highlightOffset
    )
}

/**
 * Burst effect on heavy beats
 */
private fun DrawScope.drawPeakBurst(
    center: Offset,
    maxRadius: Float,
    peakEnergy: Float,
    color: Color
) {
    val burstRadius = maxRadius * (0.5f + peakEnergy * 0.5f)
    val burstAlpha = ((peakEnergy - 0.4f) / 0.6f).coerceIn(0f, 0.6f)

    drawCircle(
        color = color.copy(alpha = burstAlpha),
        radius = burstRadius,
        center = center,
        style = Stroke(width = 3.dp.toPx())
    )

    if (peakEnergy > 0.6f) {
        drawCircle(
            color = color.copy(alpha = burstAlpha * 0.5f),
            radius = burstRadius * 1.2f,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}
