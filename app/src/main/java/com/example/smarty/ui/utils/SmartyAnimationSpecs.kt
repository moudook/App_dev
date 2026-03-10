package com.example.smarty.ui.utils

import androidx.compose.animation.core.*

/**
 * Shared animation specifications for consistent motion across the app.
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
    fun defaultSpring(): SpringSpec<Float> = spring(
        dampingRatio = DAMPING_NORMAL,
        stiffness = STIFFNESS_HIGH
    )
    
    /**
     * Bouncy spring for playful interactions
     */
    fun bouncySpring(): SpringSpec<Float> = spring(
        dampingRatio = DAMPING_BOUNCY,
        stiffness = STIFFNESS_HIGH
    )
    
    /**
     * Gentle spring for subtle transitions
     */
    fun gentleSpring(): SpringSpec<Float> = spring(
        dampingRatio = DAMPING_GENTLE,
        stiffness = STIFFNESS_NORMAL
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
    
    // Shimmer effect (duration in ms)
    const val SHIMMER_DURATION_MS = 1200
    
    // Thinking dots animation (duration in ms)
    const val THINKING_DOTS_DURATION_MS = 1200
    
    // Streaming cursor blink (duration in ms)
    const val CURSOR_BLINK_DURATION_MS = 1000
}
