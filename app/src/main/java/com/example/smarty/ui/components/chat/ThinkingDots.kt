package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha

/**
 * ThinkingDots - Animated loading indicator for AI thinking state.
 * 
 * Single Responsibility: Only displays animated thinking dots.
 * DRY: Extracted to avoid duplication across multiple screens.
 * 
 * Uses a single animation with phase-offset sine waves for smooth,
 * resource-efficient animation (all 3 dots from one progress value).
 */
@Composable
fun ThinkingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = LocalAccentColor.current
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val thinkingProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ), label = "thinkDots"
    )
    
    // Derive 3 dot alphas from single progress with 120° phase separation
    val pi2 = 2f * Math.PI.toFloat()
    val dotAlpha1 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2) + 1f) / 2f))
    val dotAlpha2 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2 + pi2 / 3f) + 1f) / 2f))
    val dotAlpha3 = (0.2f + 0.8f * ((kotlin.math.sin(thinkingProgress * pi2 + 2f * pi2 / 3f) + 1f) / 2f))
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(2.dp))
        Dot(alpha = dotAlpha1, color = dotColor)
        Dot(alpha = dotAlpha2, color = dotColor)
        Dot(alpha = dotAlpha3, color = dotColor)
    }
}

@Composable
private fun Dot(alpha: Float, color: Color) {
    Text(
        text = ".",
        color = color.copy(alpha = alpha),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
}
