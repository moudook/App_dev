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
 */
fun Modifier.shimmerEffect(
    shimmerColor: Color = Color.White.copy(alpha = 0.5f),
    durationMs: Int = 1500
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = durationMs,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            shimmerColor,
            Color.Transparent
        ),
        start = Offset(x = translateAnimation, y = translateAnimation),
        end = Offset(x = translateAnimation + 200f, y = translateAnimation + 200f),
        tileMode = TileMode.Mirror
    )
    
    this.background(gradient)
}
