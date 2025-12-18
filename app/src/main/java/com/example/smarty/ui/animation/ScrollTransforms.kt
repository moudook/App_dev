package com.example.smarty.ui.animation

import android.graphics.Camera
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.withSign

/**
 * Creative "Gravitational Wheel" Scroll Effect.
 * 
 * Recreates the premium Feel of the Apple Time Picker (Rolodex) but adapted
 * for larger Note Cards. 
 *
 * Core concepts:
 * 1. **Cylindrical Projection**: Items map to the surface of a virtual cylinder.
 * 2. **Gravitational Bunching**: Items visually "stack" or overlap slightly at the edges
 *    to accentuate the depth, rather than just floating far apart.
 * 3. **Focal Lens**: The center item snaps to full clarity brightness, while edges
 *    fade and blur into the background.
 */
@Composable
fun Modifier.wheelScrollTransform(
    index: Int,
    lazyListState: LazyListState
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
            // We expand the range slightly (viewportHeight / 1.8f) to broaden the "sweet spot" in the middle
            val normalizedDist = ((itemCenter - viewportCenter) / (viewportHeight / 1.6f)).coerceIn(-1f, 1f)
            val absDist = abs(normalizedDist)

            // 1. Rotation X (The "Rolodex" Tilt)
            // Non-linear: The center stays flatter for longer (power of 1.5), edges tilt sharply up to 60 degrees.
            // Items above center (negative dist) tilt BACKWARD (negative degrees) -> Look up
            // Items below center (positive dist) tilt FORWARD (positive degrees) -> Look down
            val rotationDeg = normalizedDist * 60f 
            
            // 2. Scale (Perspective Depth)
            // Center = 1.00, Edges = 0.85
            // Uses quadratic curve for smooth transition
            val scaleVal = 1f - (absDist.pow(2) * 0.15f)

            // 3. Translation Y (The "Bunching" Effect)
            // We pull items closer to the center to simulate the curvature of a cylinder.
            // Items at edges move INWARDS (opposite to their distance sign).
            // This creates the visual overlap "stack" effect.
            val translationYPx = -normalizedDist * (itemInfo.size * 0.15f)
            
            // 4. Alpha (Atmospheric Haze)
            // Sharp drop-off at edges to focus attention on the center
            val alphaVal = (1f - (absDist.pow(1.5f) * 0.8f)).coerceIn(0.2f, 1f)
            
            // Apply transformations
            rotationX = rotationDeg
            scaleX = scaleVal
            scaleY = scaleVal
            translationY = translationYPx
            alpha = alphaVal
            
            // Critical for 3D effect: Camera distance
            // Standard is 8f. High value = weaker perspective. Low value = dramatic perspective.
            cameraDistance = 16f * density.density
            
            // Pivot point: Center of the card
            transformOrigin = TransformOrigin.Center
        } else {
             // Safe defaults if item not found (though graphicsLayer usually skips)
             alpha = 1f
        }
    }
}
