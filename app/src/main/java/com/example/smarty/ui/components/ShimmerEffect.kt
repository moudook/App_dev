package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

/**
 * OPTIMIZED: Duolingo-style shimmer effect modifier
 * Creates a sweeping highlight animation across the surface
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Animation pauses when app is backgrounded (zero CPU in background)
 */
fun Modifier.shimmerEffect(
    enabled: Boolean = true,
    baseColor: Color = Color.White.copy(alpha = 0.0f),
    highlightColor: Color = Color.White.copy(alpha = 0.3f),
    durationMillis: Int = 1500
): Modifier = composed {
    if (!enabled) return@composed this

    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    if (!shouldAnimate) {
        // Return static shimmer at mid-point when backgrounded - zero animation overhead
        val staticGradient = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor.copy(alpha = highlightColor.alpha * 0.5f), baseColor)
        )
        return@composed this.background(staticGradient)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = durationMillis,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    this.background(
        brush = Brush.linearGradient(
            colors = listOf(
                baseColor,
                highlightColor,
                baseColor
            ),
            start = Offset(shimmerTranslate - 300f, shimmerTranslate - 300f),
            end = Offset(shimmerTranslate, shimmerTranslate)
        )
    )
}

/**
 * OPTIMIZED: Creates a subtle shimmer brush for use in custom draws
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Returns static brush when app is backgrounded
 */
@Composable
fun rememberShimmerBrush(
    highlightColor: Color = Color.White.copy(alpha = 0.15f),
    durationMillis: Int = 2000
): Brush {
    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    if (!shouldAnimate) {
        // Static brush when backgrounded
        return Brush.linearGradient(
            colors = listOf(Color.Transparent, highlightColor.copy(alpha = highlightColor.alpha * 0.5f), Color.Transparent)
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmerBrush")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = durationMillis,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            highlightColor,
            Color.Transparent
        ),
        start = Offset(shimmerTranslate - 400f, 0f),
        end = Offset(shimmerTranslate, 0f)
    )
}

/**
 * OPTIMIZED: Loading skeleton shimmer effect
 * Use on placeholder elements while content is loading
 *
 * Performance improvements:
 * - LIFECYCLE-AWARE: Animation pauses when app is backgrounded
 */
fun Modifier.skeletonShimmer(): Modifier = composed {
    // Hardcoded neutral colors for consistency across Light/Dark themes
    val baseColor = Color(0xFFE0E0E0)
    val highlightColor = Color(0xFFF5F5F5)

    // LIFECYCLE-AWARE: Only animate when app is in foreground
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    if (!shouldAnimate) {
        // Static skeleton when backgrounded
        val staticGradient = Brush.linearGradient(
            colors = listOf(baseColor, Color(0xFFEAEAEA), baseColor)
        )
        return@composed this.background(staticGradient)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonTranslate"
    )

    this.background(
        brush = Brush.linearGradient(
            colors = listOf(
                baseColor,
                highlightColor,
                baseColor
            ),
            start = Offset(shimmerTranslate - 200f, 0f),
            end = Offset(shimmerTranslate + 200f, 0f)
        )
    )
}
