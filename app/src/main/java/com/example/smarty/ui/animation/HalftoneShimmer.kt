package com.example.smarty.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue

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
    if (!isVisible) return@composed this

    val transition = rememberInfiniteTransition(label = "halftone")

    // Calculate duration based on speed
    val baseDuration = 1400
    val actualDuration = (baseDuration / speed).toInt().coerceAtLeast(200)

    // Animate the wave position across the component
    val (initialValue, targetValue) = when (direction) {
        ShimmerDirection.LEFT_TO_RIGHT -> -0.3f to 1.3f
        ShimmerDirection.RIGHT_TO_LEFT -> 1.3f to -0.3f
    }

    val progress by transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(actualDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    this.drawWithContent {
        // Draw the content first (background + text)
        drawContent()

        // --- Halftone Configuration ---
        val dotBaseRadius = 1.2.dp.toPx()
        val spacing = 5.dp.toPx() // Tighter spacing for finer texture
        val waveWidth = 350.dp.toPx() // Wide wave for smoothness

        // Hexagonal grid packing (sin 60 degrees) or basic stagger
        val rowHeight = spacing * 0.866f

        // Calculate max extent for the slanted sweep
        val slantFactor = when (direction) {
            ShimmerDirection.LEFT_TO_RIGHT -> 0.4f
            ShimmerDirection.RIGHT_TO_LEFT -> -0.4f
        }
        val maxExtent = size.width + (size.height * kotlin.math.abs(slantFactor))
        val wavePos = maxExtent * progress

        val cols = (size.width / spacing).toInt() + 2
        val rows = (size.height / rowHeight).toInt() + 2

        for (row in 0 until rows) {
            val y = row * rowHeight

            // Stagger every odd row by half spacing for hex grid feel
            val xOffset = if (row % 2 == 1) spacing / 2f else 0f

            for (col in 0 until cols) {
                val x = (col * spacing) + xOffset

                // Determine position in the slanted scalar field
                val diagonalPos = when (direction) {
                    ShimmerDirection.LEFT_TO_RIGHT -> x + (y * slantFactor)
                    ShimmerDirection.RIGHT_TO_LEFT -> (size.width - x) + (y * kotlin.math.abs(slantFactor))
                }

                // Distance from the current wave front
                val dist = kotlin.math.abs(diagonalPos - wavePos)

                if (dist < waveWidth) {
                    // Normalize distance (0..1) -> 1 at center, 0 at edges
                    val rawIntensity = 1f - (dist / waveWidth)

                    // Apply easing curve for visual punch
                    val intensity = rawIntensity * rawIntensity * rawIntensity

                    if (intensity > 0.02f) { // Optimization cull
                        // visual parameters based on intensity
                        val radius = dotBaseRadius * (0.7f + (0.6f * intensity))

                        // Scale alpha: faint background presence to distinct shimmer
                        val alpha = (0.05f + (0.25f * intensity)).coerceIn(0f, 0.3f)

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
 */
fun Modifier.halftoneShimmer(
    isVisible: Boolean,
    color: Color
): Modifier = composed {
    if (!isVisible) return@composed this

    val transition = rememberInfiniteTransition(label = "halftone")
    // Animate the wave position across the component diagonally
    val progress by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )
//
    this.drawWithContent {
        // Draw the content first (background + text)
        drawContent()
        
        // --- Halftone Configuration ---
        val dotBaseRadius = 1.2.dp.toPx()
        val spacing = 5.dp.toPx() // Tighter spacing for finer texture
        val waveWidth = 350.dp.toPx() // Wide wave for smoothness
        
        // Hexagonal grid packing (sin 60 degrees) or basic stagger
        val rowHeight = spacing * 0.866f 
        
        // Calculate max extent for the slanted sweep
        // Use a slanted gradient (steeper angle: ~22 degrees) instead of pure 45 degree
        val slantFactor = 0.4f
        val maxExtent = size.width + (size.height * slantFactor)
        val wavePos = maxExtent * progress

        val cols = (size.width / spacing).toInt() + 2
        val rows = (size.height / rowHeight).toInt() + 2
        
        for (row in 0 until rows) {
            val y = row * rowHeight
            
            // Stagger every odd row by half spacing for hex grid feel
            val xOffset = if (row % 2 == 1) spacing / 2f else 0f
            
            for (col in 0 until cols) {
                val x = (col * spacing) + xOffset
                
                // Determine position in the slanted scalar field
                val diagonalPos = x + (y * slantFactor)
                
                // Distance from the current wave front
                val dist = kotlin.math.abs(diagonalPos - wavePos)
                
                if (dist < waveWidth) {
                    // Normalize distance (0..1) -> 1 at center, 0 at edges
                    val rawIntensity = 1f - (dist / waveWidth)
                    
                    // Apply easing curve for visual punch
                    // Cubic or quartic ease-in makes the edge falloff sharper and center brighter
                    val intensity = rawIntensity * rawIntensity * rawIntensity
                    
                    if (intensity > 0.02f) { // Optimization cull
                        // visual parameters based on intensity
                        // Grow radius slightly at peak
                        val radius = dotBaseRadius * (0.7f + (0.6f * intensity))
                        
                        // Scale alpha: faint background presence to distinct shimmer
                        // Max alpha 0.25f ensures text remains readable
                        val alpha = (0.05f + (0.25f * intensity)).coerceIn(0f, 0.3f)
                        
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
