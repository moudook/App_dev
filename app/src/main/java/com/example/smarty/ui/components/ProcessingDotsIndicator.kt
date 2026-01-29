package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

/**
 * OPTIMIZED: Animated dots indicator showing processing state.
 * Three dots that pulse sequentially in a wave pattern.
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Animation pauses when app is backgrounded (zero CPU in background)
 * - Single animation drives all 3 dots (was 3 separate animations)
 * - 66% reduction in animation overhead
 * - Uses graphicsLayer for GPU-accelerated scaling
 *
 * @param modifier Modifier for the container
 * @param dotSize Size of each dot
 * @param dotSpacing Spacing between dots
 */
@Composable
fun ProcessingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    dotSpacing: Dp = 4.dp
) {
    val accentColor = LocalAccentColor.current

    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    // OPTIMIZED: Single animation drives all 3 dots
    // Phase offsets: dot1=0°, dot2=120°, dot3=240° (evenly spaced)
    val progress = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "dotsProgress"
        ).value
    } else {
        0f // Static when backgrounded
    }

    // Derive each dot's scale from progress with phase offset
    // Using triangle wave: 0→1→0 pattern mapped from progress
    val dotScales = if (shouldAnimate) {
        listOf(0f, 0.333f, 0.666f).map { offset ->
            val phase = (progress + offset) % 1f
            // Triangle wave: goes 0→1→0 over the cycle
            val wave = if (phase < 0.5f) phase * 2f else 2f - phase * 2f
            0.6f + 0.4f * wave // Scale range: 0.6 to 1.0
        }
    } else {
        listOf(0.8f, 0.8f, 0.8f) // Static mid-scale when backgrounded
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dotScales.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.7f)) // Calmer, slightly transparent
                    // OPTIMIZED: graphicsLayer for GPU-accelerated scaling
                    .graphicsLayer { scaleX = scale; scaleY = scale }
            )
        }
    }
}
