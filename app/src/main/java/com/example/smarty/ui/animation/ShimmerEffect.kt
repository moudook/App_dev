package com.example.smarty.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Creates a premium shimmering/skeleton loading effect similar to YouTube
 * or new iOS loading states.
 * 
 * Enhanced version with:
 * - Wider, smoother gradient band
 * - Wave-like motion with variable speed
 * - Configurable shimmer intensity and direction
 * - Staggered animation support for multiple skeleton elements
 */
fun Modifier.shimmerEffect(
    shimmerColor: Color = Color.White.copy(alpha = 0.6f),
    durationMs: Int = 1200,
    delayMs: Int = 0,
    shimmerWidth: Float = 400f
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    
    // Primary sweep animation - moves diagonally across the element
    val translateAnimation by transition.animateFloat(
        initialValue = -shimmerWidth,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = durationMs,
                delayMillis = delayMs,
                easing = FastOutSlowInEasing // Smoother acceleration/deceleration
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    
    // Secondary pulse animation - subtle intensity variation
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (durationMs * 0.7).toInt(),
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerPulse"
    )
    
    // Enhanced gradient with multiple color stops for smoother transition
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.2f to shimmerColor.copy(alpha = shimmerColor.alpha * 0.3f * pulseAlpha),
            0.4f to shimmerColor.copy(alpha = shimmerColor.alpha * 0.7f * pulseAlpha),
            0.5f to shimmerColor.copy(alpha = shimmerColor.alpha * pulseAlpha),
            0.6f to shimmerColor.copy(alpha = shimmerColor.alpha * 0.7f * pulseAlpha),
            0.8f to shimmerColor.copy(alpha = shimmerColor.alpha * 0.3f * pulseAlpha),
            1.0f to Color.Transparent
        ),
        start = Offset(x = translateAnimation, y = translateAnimation * 0.5f),
        end = Offset(x = translateAnimation + shimmerWidth, y = translateAnimation * 0.5f + shimmerWidth * 0.6f),
        tileMode = TileMode.Clamp
    )
    
    this.background(gradient)
}

/**
 * Premium wave shimmer effect - creates a flowing wave appearance
 * Ideal for important loading states that need to grab attention
 */
fun Modifier.waveShimmerEffect(
    baseColor: Color = Color.White,
    durationMs: Int = 1500
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "waveShimmer")
    
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    
    val waveAmplitude by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAmplitude"
    )
    
    val offset = wavePhase * 1500f
    
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.15f to baseColor.copy(alpha = 0.1f * waveAmplitude),
            0.3f to baseColor.copy(alpha = 0.3f * waveAmplitude),
            0.5f to baseColor.copy(alpha = 0.5f * waveAmplitude),
            0.7f to baseColor.copy(alpha = 0.3f * waveAmplitude),
            0.85f to baseColor.copy(alpha = 0.1f * waveAmplitude),
            1.0f to Color.Transparent
        ),
        start = Offset(offset - 400f, offset * 0.3f),
        end = Offset(offset, offset * 0.3f + 200f),
        tileMode = TileMode.Clamp
    )
    
    this.background(gradient)
}

