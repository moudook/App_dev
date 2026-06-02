package com.example.smarty.ui.components.animations

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Fade In Animation
 * Smooth fade-in effect for content appearing
 */
@Composable
fun AnimatedFadeIn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 300,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis)),
        exit = fadeOut(animationSpec = tween(durationMillis)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Slide In From Bottom Animation
 * Material design style slide up animation
 */
@Composable
fun AnimatedSlideInBottom(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 300,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis)),
        exit =
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Scale In Animation
 * Zoom in effect for cards and buttons
 */
@Composable
fun AnimatedScaleIn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 200,
    initialScale: Float = 0.8f,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            scaleIn(
                initialScale = initialScale,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis)),
        exit =
            scaleOut(
                targetScale = initialScale,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Expand/Collapse Animation
 * For accordion-style content
 */
@Composable
fun AnimatedExpandCollapse(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 250,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter =
            expandVertically(
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis)),
        exit =
            shrinkVertically(
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * List Item Animation
 * Staggered animation for list items appearing
 */
@Composable
fun AnimatedListItem(
    index: Int,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    staggerDelay: Int = 50,
    content: @Composable () -> Unit,
) {
    val delay = index * staggerDelay

    AnimatedVisibility(
        visible = visible,
        enter =
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300 + delay, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(300 + delay)),
        exit =
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Shimmer Loading Animation
 * Continuous shimmer effect for loading states
 */
@Composable
fun rememberShimmerTransition(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    return infiniteTransition
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "shimmerProgress",
        ).value
}

/**
 * Pulse Animation
 * Subtle pulse for attention
 */
@Composable
fun rememberPulseTransition(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    return infiniteTransition
        .animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "pulseScale",
        ).value
}

/**
 * Bounce Animation
 * Playful bounce effect
 */
@Composable
fun AnimatedBounce(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationMillis: Int = 500,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            scaleIn(
                initialScale = 0f,
                animationSpec =
                    spring(
                        dampingRatio = 0.6f,
                        stiffness = Spring.StiffnessLow,
                    ),
            ) + fadeIn(animationSpec = tween(durationMillis)),
        exit =
            scaleOut(
                targetScale = 0f,
                animationSpec =
                    spring(
                        dampingRatio = 0.6f,
                        stiffness = Spring.StiffnessLow,
                    ),
            ) + fadeOut(animationSpec = tween(durationMillis)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Rotate Animation
 * For loading spinners or toggle icons
 */
@Composable
fun rememberRotateTransition(
    targetValue: Float,
    durationMillis: Int = 300,
): State<Float> {
    val transition = updateTransition(targetValue, label = "rotate")
    return transition.animateFloat(
        transitionSpec = { tween(durationMillis, easing = FastOutSlowInEasing) },
        label = "rotation",
    ) { value -> value }
}

/**
 * Color Transition
 * Smooth color changes
 */
@Composable
fun rememberColorTransition(
    targetValue: Boolean,
    durationMillis: Int = 300,
): State<Float> {
    val transition = updateTransition(targetValue, label = "color")
    return transition.animateFloat(
        transitionSpec = { tween(durationMillis, easing = FastOutSlowInEasing) },
        label = "colorProgress",
    ) { value -> if (value) 1f else 0f }
}
