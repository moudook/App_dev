package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay

/**
 * Animated dots indicator showing processing state.
 * Three dots that pulse sequentially in a wave pattern.
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

    // Infinite transition for continuous animation
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    // Staggered animation for each dot
    val dot1Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing, delayMillis = 133),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    val dot3Scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing, delayMillis = 266),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dot1Scale, dot2Scale, dot3Scale).forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
    }
}
