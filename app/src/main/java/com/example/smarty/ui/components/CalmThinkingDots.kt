package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.shouldAnimationRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calm Thinking Dots - A premium, organic replacement for CircularProgressIndicator.
 * Three dots that perform a gentle, rhythmic wave animation.
 *
 * Features:
 * - Organic wave motion (Translation Y)
 * - Alpha breathing
 * - Dynamic color tinting
 */
@Composable
fun CalmThinkingDots(
    modifier: Modifier = Modifier,
    color: Color = LocalAccentColor.current,
    dotSize: Dp = 8.dp, // Slightly larger for better visibility
    dotSpacing: Dp = 6.dp
) {
    val shouldAnimate = shouldAnimationRun()

    // Staggered animation state
    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )

    if (shouldAnimate) {
        LaunchedEffect(Unit) {
            dots.forEachIndexed { index, animatable ->
                launch {
                    delay(index * 150L) // Stagger start times
                    animatable.animateTo(
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 1200
                                0f at 0 using LinearOutSlowInEasing // Start
                                1f at 600 using LinearOutSlowInEasing // Peak
                                0f at 1200 using LinearOutSlowInEasing // End
                            },
                            repeatMode = RepeatMode.Restart
                        )
                    )
                }
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { animatable ->
            // Wave parameters
            val offset = -6f * animatable.value // Move up by 6dp
            val alpha = 0.4f + (0.6f * animatable.value) // Fade in/out
            val scale = 0.85f + (0.15f * animatable.value) // Slight scale up

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = offset * density // Convert dp-like value to pixels roughly (simplified)
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(color, CircleShape)
            )
        }
    }
}
