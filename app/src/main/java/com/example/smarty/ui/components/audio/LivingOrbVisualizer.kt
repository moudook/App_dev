package com.example.smarty.ui.components.audio

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
import com.example.smarty.ui.theme.AudioPink

/**
 * A simplified "Soft Aura" Visualizer.
 * Optimized for small scales (e.g., 48dp mini-player).
 *
 * Features:
 * - Clean, soft edges (no jagged lines).
 * - Multi-layer breathing glow.
 * - Real-time reactive to audio.
 */
@Composable
fun LivingOrbVisualizer(
    isPlaying: Boolean,
    progress: Float = 0f,
    amplitude: Float = 0f,
    bass: Float = 0f,
    mid: Float = 0f,
    treble: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    primaryColor: Color = AudioPink,
    secondaryColor: Color = AudioPink.copy(alpha = 0.5f),
    backgroundColor: Color = AudioPink.copy(alpha = 0.1f),
) {
    // === JELLY PHYSICS ===
    // Low damping + low stiffness = Maximum jiggle!

    val targetAmp = if (isPlaying) amplitude else 0f
    val targetBass = if (isPlaying) bass else 0f

    // Primary jelly spring: Overshoots and wobbles
    val smoothedAmp by animateFloatAsState(
        targetValue = targetAmp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy, // Maximum overshoot (0.15)
                stiffness = Spring.StiffnessLow, // Slow, visible wobble (75f)
            ),
        label = "amplitude_jelly",
    )

    val smoothedBass by animateFloatAsState(
        targetValue = targetBass,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        label = "bass_jelly",
    )

    // Breathing animation for idle state
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val breathePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "breathe_phase",
    )

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val center = Offset(centerX, centerY)
        val w = this.size.width
        val maxRadius = w / 2

        // === BOUNDED Scale Calculations ===
        // Define min/max constraints for predictable, legible visualization

        // Core Radius bounds:
        // - Min: 25% (Always visible, even when silent)
        // - Max: 75% (Allows room for outer auras without clipping)
        val minCoreRadius = maxRadius * 0.25f
        val maxCoreRadius = maxRadius * 0.75f

        // === JELLY LAYER (animated with spring physics) ===
        val jellyCoreRadius =
            (minCoreRadius + (smoothedAmp * 1.2f * (maxCoreRadius - minCoreRadius)))
                .coerceIn(minCoreRadius, maxCoreRadius)

        val jellyInnerAuraRadius = (jellyCoreRadius * 1.5f).coerceAtMost(maxRadius * 0.95f)

        val jellyBassPulse = smoothedBass * maxRadius * 0.3f
        val jellyOuterAuraRadius = (jellyInnerAuraRadius + jellyBassPulse).coerceAtMost(maxRadius)

        // === GHOST LAYER (instant, no animation - reference target) ===
        val ghostCoreRadius =
            (minCoreRadius + (targetAmp * 1.2f * (maxCoreRadius - minCoreRadius)))
                .coerceIn(minCoreRadius, maxCoreRadius)

        val ghostInnerAuraRadius = (ghostCoreRadius * 1.5f).coerceAtMost(maxRadius * 0.95f)

        // Idle breathing (subtle)
        val idleBreathe =
            if (isPlaying) {
                kotlin.math.sin(breathePhase * 2 * kotlin.math.PI.toFloat()) * 0.03f * maxRadius
            } else {
                0f
            }

        // === Drawing ===

        // --- GHOST LAYER (Semi-transparent reference) ---
        // This shows where the jelly is "trying" to settle

        // Ghost Inner Aura
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            primaryColor.copy(alpha = 0.15f),
                            primaryColor.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = ghostInnerAuraRadius,
                ),
            radius = ghostInnerAuraRadius,
            center = center,
        )

        // Ghost Core (very faint outline)
        drawCircle(
            color = primaryColor.copy(alpha = 0.2f),
            radius = ghostCoreRadius,
            center = center,
        )

        // --- JELLY LAYER (Wobbly, reactive) ---

        // Layer 1: Outer Aura (Bass pulse - JELLY)
        if (smoothedBass > 0.05f) {
            val outerAlpha = (0.3f * smoothedBass).coerceIn(0f, 0.5f)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                primaryColor.copy(alpha = outerAlpha),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = jellyOuterAuraRadius,
                    ),
                radius = jellyOuterAuraRadius,
                center = center,
            )
        }

        // Layer 2: Inner Aura (Constant Glow - JELLY)
        val innerAlpha = if (isPlaying) 0.5f + (smoothedAmp * 0.4f) else 0.2f
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            primaryColor.copy(alpha = innerAlpha),
                            primaryColor.copy(alpha = 0.1f),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = jellyInnerAuraRadius + idleBreathe,
                ),
            radius = jellyInnerAuraRadius + idleBreathe,
            center = center,
        )

        // Layer 3: Solid Core (JELLY)
        drawCircle(
            color = primaryColor,
            radius = jellyCoreRadius + idleBreathe,
            center = center,
        )

        // Highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.4f),
            radius = (jellyCoreRadius + idleBreathe) * 0.3f,
            center = center,
        )
    }
}
