package com.example.smarty.ui.animation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * OPTIMIZED: Creative "Gravitational Wheel" Scroll Effect.
 *
 * Recreates the premium feel of the Apple Time Picker (Rolodex) but adapted
 * for larger Note Cards.
 *
 * Performance improvements:
 * - Fast power functions: x² = x*x, x^1.5 = x*sqrt(x)
 * - No kotlin.math.pow() calls (avoids boxing/unboxing overhead)
 * - Pre-computed constants to reduce per-frame arithmetic
 * - graphicsLayer lambda already defers to draw phase
 *
 * Core concepts:
 * 1. **Cylindrical Projection**: Items map to the surface of a virtual cylinder.
 * 2. **Gravitational Bunching**: Items visually "stack" at edges for depth.
 * 3. **Focal Lens**: Center item at full clarity, edges fade.
 */
@Composable
fun Modifier.wheelScrollTransform(
    index: Int,
    lazyListState: LazyListState,
): Modifier {
    val density = LocalDensity.current

    return graphicsLayer {
        val layoutInfo = lazyListState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }

        if (itemInfo != null) {
            val viewportHeight = layoutInfo.viewportSize.height.toFloat()
            val viewportCenter = viewportHeight / 2f

            // Item center Y
            val itemCenter = itemInfo.offset + (itemInfo.size / 2f)

            // Normalized distance: -1.0 (Top) to 0.0 (Center) to 1.0 (Bottom)
            // Pre-computed divisor: viewportHeight / 1.6f
            val normalizedDist = ((itemCenter - viewportCenter) * 1.6f / viewportHeight).coerceIn(-1f, 1f)
            val absDist = abs(normalizedDist)

            // 1. Rotation X (The "Rolodex" Tilt) - 60° max
            val rotationDeg = normalizedDist * 60f

            // 2. Scale (Perspective Depth)
            // OPTIMIZED: x² = x*x (faster than pow(2))
            // Center = 1.00, Edges = 0.85
            val absDist2 = absDist * absDist
            val scaleVal = 1f - (absDist2 * 0.15f)

            // 3. Translation Y (The "Bunching" Effect)
            val translationYPx = -normalizedDist * (itemInfo.size * 0.15f)

            // 4. Alpha (Atmospheric Haze)
            // OPTIMIZED: x^1.5 = x * sqrt(x) (faster than pow(1.5f))
            // sqrt is hardware-accelerated on most CPUs
            val absDist15 = absDist * sqrt(absDist)
            val alphaVal = (1f - (absDist15 * 0.8f)).coerceIn(0.2f, 1f)

            // Apply transformations
            rotationX = rotationDeg
            scaleX = scaleVal
            scaleY = scaleVal
            translationY = translationYPx
            alpha = alphaVal

            // Critical for 3D effect: Camera distance
            cameraDistance = 16f * density.density

            // Pivot point: Center of the card
            transformOrigin = TransformOrigin.Center
        } else {
            // Safe defaults if item not found
            alpha = 1f
        }
    }
}
