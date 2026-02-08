package com.example.smarty.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.graphics.BlurMaskFilter
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * Applies a glow border effect when active.
 * Used to provide visual feedback when shaking to switch modes.
 *
 * @param isActive Whether the glow effect should be visible
 * @param glowColor The color of the glow border
 * @param onAnimationComplete Callback when the fade-out animation completes
 */
fun Modifier.shakeGlowEffect(
    isActive: Boolean,
    glowColor: Color,
    onAnimationComplete: () -> Unit = {}
): Modifier = composed {
    val glowAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f, // Full visible when active
        animationSpec = tween(
            durationMillis = if (isActive) 100 else 300, // Fast in, smooth out
            easing = FastOutSlowInEasing
        ),
        finishedListener = {
            if (!isActive) onAnimationComplete()
        },
        label = "glowAlpha"
    )

    // Animate the blur radius for a expanding cloud effect
    val blurRadius by animateFloatAsState(
        targetValue = if (isActive) 60f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "blurRadius"
    )

    if (glowAlpha > 0.01f) {
        this.drawBehind {
            drawIntoCanvas { canvas ->
                val paint = Paint()
                val frameworkPaint = paint.asFrameworkPaint()
                
                frameworkPaint.color = glowColor.copy(alpha = glowAlpha).toArgb()
                
                // Use NORMAL blur on a Stroke to create a fuzzy "cloud" line
                frameworkPaint.maskFilter = BlurMaskFilter(
                    blurRadius * density, 
                    BlurMaskFilter.Blur.NORMAL
                )
                
                frameworkPaint.style = android.graphics.Paint.Style.STROKE
                val strokeWidth = 50f * density // Thick stroke for the cloud body
                frameworkPaint.strokeWidth = strokeWidth
                
                // Rounded corners
                val cornerRadius = 32.dp.toPx() 
                
                // Inset by half stroke width so the "cloud" is centered on the edge
                // but mostly visible. Since this is a "border", we want it on the perimeter.
                val inset = strokeWidth / 2
                
                canvas.drawRoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    radiusX = cornerRadius,
                    radiusY = cornerRadius,
                    paint = paint
                )
            }
        }
    } else {
        this
    }
}

/**
 * State holder for shake glow animation.
 * Tracks whether the glow is currently active.
 */
class ShakeGlowState {
    var isGlowing by mutableStateOf(false)
        private set

    /**
     * Trigger the glow animation (pulse).
     * Suspend function that handles the full animation cycle.
     */
    suspend fun triggerGlow() {
        isGlowing = true
        // Keep glow active for longer duration (cloudy linger)
        try {
            kotlinx.coroutines.delay(1500)
        } finally {
            isGlowing = false
        }
    }

    /**
     * Called when glow animation completes to reset state.
     */
    fun onGlowComplete() {
        isGlowing = false
    }
}

/**
 * Remember a ShakeGlowState instance.
 */
@Composable
fun rememberShakeGlowState(): ShakeGlowState {
    return remember { ShakeGlowState() }
}
