package com.example.smarty.ui.utils

/**
 * Shared animation specifications for consistent motion across the app.
 * 
 * Principles:
 * - DRY: Single source of truth for animation specs
 * - Consistency: Same easing and durations everywhere
 * - Maintainability: Change once, apply everywhere
 */
object SmartyAnimationSpecs {
    
    // Default durations
    const val INSTANT_MS = 0
    const val FAST_MS = 150
    const val NORMAL_MS = 250
    const val SLOW_MS = 400
    const val VERY_SLOW_MS = 600
    
    // Spring stiffness values
    const val STIFFNESS_LOW = 100f
    const val STIFFNESS_NORMAL = 200f
    const val STIFFNESS_HIGH = 400f
    const val STIFFNESS_VERY_HIGH = 800f
    
    // Damping ratios
    const val DAMPING_BOUNCY = 0.5f
    const val DAMPING_NORMAL = 0.7f
    const val DAMPING_GENTLE = 0.9f
    
    /**
     * Default spring for most UI interactions
     */
    fun defaultSpring() = androidx.compose.animation.core.spring(
        dampingRatio = DAMPING_NORMAL,
        stiffness = STIFFNESS_HIGH
    )
    
    /**
     * Bouncy spring for playful interactions
     */
    fun bouncySpring() = androidx.compose.animation.core.spring(
        dampingRatio = DAMPING_BOUNCY,
        stiffness = STIFFNESS_HIGH
    )
    
    /**
     * Gentle spring for subtle transitions
     */
    fun gentleSpring() = androidx.compose.animation.core.spring(
        dampingRatio = DAMPING_GENTLE,
        stiffness = STIFFNESS_NORMAL
    )
    
    /**
     * Tween for timed animations
     */
    fun tween(
        durationMs: Int = NORMAL_MS,
        easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing
    ) = androidx.compose.animation.core.tween(durationMs, easing = easing)
    
    /**
     * Infinite repeatable for loading indicators
     */
    fun infiniteRepeatable(
        durationMs: Int = NORMAL_MS,
        easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.LinearEasing
    ) = androidx.compose.animation.core.infiniteRepeatable(
        animation = androidx.compose.animation.core.tween(durationMs, easing = easing),
        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
    )
}

/**
 * Pre-defined animation specs for common use cases
 */
object AnimationPresets {
    
    // Button press scale
    val buttonPressSpring = spring<Float>(
        dampingRatio = 0.6f,
        stiffness = 500f
    )
    
    // Card expansion
    val cardExpandSpring = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 400f
    )
    
    // Dialog appearance
    val dialogFadeTween = tween<Int>(
        durationMs = 250,
        easing = FastOutSlowInEasing
    )
    
    // Shimmer effect
    val shimmerTween = tween<Int>(
        durationMs = 1200,
        easing = LinearEasing
    )
    
    // Thinking dots animation
    val thinkingDotsTween = tween<Float>(
        durationMs = 1200,
        easing = FastOutSlowInEasing
    )
    
    // Streaming cursor blink
    val cursorBlinkTween = tween<Float>(
        durationMs = 1000,
        easing = LinearEasing
    )
}
