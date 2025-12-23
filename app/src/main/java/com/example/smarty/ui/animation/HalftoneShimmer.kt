package com.example.smarty.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.smarty.ui.utils.rememberStaticAwareTransition

/**
 * Direction of the shimmer animation
 */
enum class ShimmerDirection {
    LEFT_TO_RIGHT,  // Speech active (accent color)
    RIGHT_TO_LEFT   // Agent working (different color)
}

/**
 * Halftone Shimmer Effect Modifier with direction and speed control.
 * Applies a dynamic dotted texture animation overlay when visible.
 *
 * OPTIMIZED: Only runs animation when isVisible=true.
 * When not visible, no infinite transition is created = no frame requests = static rendering.
 *
 * @param isVisible Whether the shimmer is visible
 * @param color The color of the shimmer dots
 * @param direction The direction of the shimmer animation
 * @param speed Speed multiplier (1.0 = normal, 3.5 = fast blinking for auto-send)
 */
fun Modifier.directionalShimmer(
    isVisible: Boolean,
    color: Color,
    direction: ShimmerDirection = ShimmerDirection.LEFT_TO_RIGHT,
    speed: Float = 1f
): Modifier = composed {
    // Early exit - no animation resources created when not visible
    if (!isVisible) return@composed this

    // Fade in/out animation for smooth appearance
    val fadeAlpha by animateFloatAsState(
        targetValue = 1f,  // Always 1 when visible (we already checked isVisible)
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "shimmerFade"
    )

    // Only create transition when visible - saves CPU/GPU when static
    val transition = rememberStaticAwareTransition(isActive = true, label = "halftone")

    // Calculate duration based on speed
    val baseDuration = 1800  // Slightly slower for smoother feel
    val actualDuration = (baseDuration / speed).toInt().coerceAtLeast(200)

    // Continuous 0 to 1 progress - we'll handle wrapping in draw
    val progress = if (transition != null) {
        val animatedProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(actualDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "progress"
        )
        animatedProgress
    } else {
        0f  // Static value when paused (lifecycle)
    }

    // Clip to bounds so dots don't appear outside the component
    this.clipToBounds().drawWithContent {
        // Draw the content first (background + text)
        drawContent()

        // --- Halftone Configuration ---
        // Only wave particles visible - big at center, invisible at edges
        val dotMaxRadius = 4.dp.toPx()  // Big dots at wave center
        val spacing = 8.dp.toPx()

        // Wider wave band for better visibility
        val waveWidthRatio = 0.35f  // Wave covers 35% of the area

        // Hexagonal grid packing
        val rowHeight = spacing * 0.866f

        // Calculate max extent for the slanted sweep
        val slantFactor = when (direction) {
            ShimmerDirection.LEFT_TO_RIGHT -> 0.4f
            ShimmerDirection.RIGHT_TO_LEFT -> -0.4f
        }
        val maxExtent = size.width + (size.height * kotlin.math.abs(slantFactor))
        val waveWidth = maxExtent * waveWidthRatio

        // Current wave position (0 to maxExtent, then wraps)
        val baseWavePos = maxExtent * progress

        val cols = (size.width / spacing).toInt() + 2
        val rows = (size.height / rowHeight).toInt() + 2

        for (row in 0 until rows) {
            val y = row * rowHeight
            val xOffset = if (row % 2 == 1) spacing / 2f else 0f

            for (col in 0 until cols) {
                val x = (col * spacing) + xOffset

                // Skip dots outside visible bounds (clipping optimization)
                if (x < -dotMaxRadius || x > size.width + dotMaxRadius ||
                    y < -dotMaxRadius || y > size.height + dotMaxRadius) continue

                // Determine position in the slanted scalar field
                val diagonalPos = when (direction) {
                    ShimmerDirection.LEFT_TO_RIGHT -> x + (y * slantFactor)
                    ShimmerDirection.RIGHT_TO_LEFT -> (size.width - x) + (y * kotlin.math.abs(slantFactor))
                }

                // Calculate intensity considering wave wrapping for seamless loop
                val dist1 = kotlin.math.abs(diagonalPos - baseWavePos)
                val dist2 = kotlin.math.abs(diagonalPos - (baseWavePos - maxExtent))
                val dist3 = kotlin.math.abs(diagonalPos - (baseWavePos + maxExtent))
                val dist = minOf(dist1, dist2, dist3)

                if (dist < waveWidth) {
                    // Smooth intensity falloff - particles fade to invisible at wave edges
                    val normalizedDist = dist / waveWidth
                    val rawIntensity = kotlin.math.cos(normalizedDist * kotlin.math.PI.toFloat() / 2f)
                    val intensity = rawIntensity * rawIntensity * rawIntensity  // Cubic falloff - sharp edges

                    // Only draw if intensity is high enough (invisible at wave edges)
                    if (intensity > 0.15f) {
                        // SIZE scales with intensity - big center, invisible edges
                        val radius = dotMaxRadius * intensity

                        // Lower opacity blue color
                        val alpha = (0.4f * fadeAlpha * intensity).coerceIn(0f, 0.5f)

                        drawCircle(
                            color = color.copy(alpha = alpha),
                            radius = radius,
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Halftone Shimmer Effect Modifier (Legacy - uses LEFT_TO_RIGHT direction)
 * Applies a dynamic dotted texture animation overlay when visible.
 * Features seamless looping for continuous flow effect.
 * Dots are BIG at wave center, SMALL at edges - creates "wave of particles" effect.
 *
 * OPTIMIZED: Only runs animation when isVisible=true.
 * When not visible, no infinite transition is created = no frame requests = static rendering.
 */
fun Modifier.halftoneShimmer(
    isVisible: Boolean,
    color: Color
): Modifier = composed {
    // Early exit - no animation resources created when not visible
    if (!isVisible) return@composed this

    // Fade in/out animation for smooth appearance
    val fadeAlpha by animateFloatAsState(
        targetValue = 1f,  // Always 1 when visible
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "shimmerFade"
    )

    // Only create transition when visible - saves CPU/GPU when static
    val transition = rememberStaticAwareTransition(isActive = true, label = "halftone_legacy")

    // Continuous 0 to 1 progress - we'll handle wrapping in draw
    val progress = if (transition != null) {
        val animatedProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "progress"
        )
        animatedProgress
    } else {
        0f  // Static value when paused
    }

    // Clip to bounds so dots don't appear outside the component
    this.clipToBounds().drawWithContent {
        // Draw the content first (background + text)
        drawContent()

        // --- Halftone Configuration ---
        // Only wave particles visible - big at center, invisible at edges
        val dotMaxRadius = 4.dp.toPx()  // Big dots at wave center
        val spacing = 8.dp.toPx()

        // Wider wave band for better visibility
        val waveWidthRatio = 0.35f  // Wave covers 35% of the area

        // Hexagonal grid packing
        val rowHeight = spacing * 0.866f

        // Calculate max extent for the slanted sweep
        val slantFactor = 0.4f
        val maxExtent = size.width + (size.height * slantFactor)
        val waveWidth = maxExtent * waveWidthRatio

        // Current wave position (0 to maxExtent, then wraps)
        val baseWavePos = maxExtent * progress

        val cols = (size.width / spacing).toInt() + 2
        val rows = (size.height / rowHeight).toInt() + 2

        for (row in 0 until rows) {
            val y = row * rowHeight
            val xOffset = if (row % 2 == 1) spacing / 2f else 0f

            for (col in 0 until cols) {
                val x = (col * spacing) + xOffset

                // Skip dots outside visible bounds (clipping optimization)
                if (x < -dotMaxRadius || x > size.width + dotMaxRadius ||
                    y < -dotMaxRadius || y > size.height + dotMaxRadius) continue

                // Determine position in the slanted scalar field
                val diagonalPos = x + (y * slantFactor)

                // Calculate intensity considering wave wrapping for seamless loop
                val dist1 = kotlin.math.abs(diagonalPos - baseWavePos)
                val dist2 = kotlin.math.abs(diagonalPos - (baseWavePos - maxExtent))
                val dist3 = kotlin.math.abs(diagonalPos - (baseWavePos + maxExtent))
                val dist = minOf(dist1, dist2, dist3)

                if (dist < waveWidth) {
                    // Smooth intensity falloff - particles fade to invisible at wave edges
                    val normalizedDist = dist / waveWidth
                    val rawIntensity = kotlin.math.cos(normalizedDist * kotlin.math.PI.toFloat() / 2f)
                    val intensity = rawIntensity * rawIntensity * rawIntensity  // Cubic falloff - sharp edges

                    // Only draw if intensity is high enough (invisible at wave edges)
                    if (intensity > 0.15f) {
                        // SIZE scales with intensity - big center, invisible edges
                        val radius = dotMaxRadius * intensity

                        // Lower opacity blue color
                        val alpha = (0.4f * fadeAlpha * intensity).coerceIn(0f, 0.5f)

                        drawCircle(
                            color = color.copy(alpha = alpha),
                            radius = radius,
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}
