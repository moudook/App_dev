package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay

/**
 * VoiceWaveformIcon - Animated waveform icon for voice input.
 * 
 * Single Responsibility: Only displays voice waveform animation.
 * DRY: Centralized waveform animation logic.
 */
@Composable
fun VoiceWaveformIcon(
    isListening: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = LocalAccentColor.current,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_waveform")
    
    // Animated height for bars
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "bar1"
    )
    
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 100),
            repeatMode = RepeatMode.Reverse
        ), label = "bar2"
    )
    
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ), label = "bar3"
    )
    
    val iconColor = if (isListening) activeColor else inactiveColor
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Bar(heightFraction = bar1Height, color = iconColor)
        Bar(heightFraction = bar2Height, color = iconColor)
        Bar(heightFraction = bar3Height, color = iconColor)
    }
}

@Composable
private fun Bar(heightFraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(16.dp * heightFraction)
            .background(color = color, shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
    )
}

/**
 * ShimmerOverlay - Animated shimmer effect for loading states.
 * 
 * Single Responsibility: Only displays shimmer animation.
 * DRY: Centralized shimmer logic for consistent effects.
 */
@Composable
fun ShimmerOverlay(
    isShimmering: Boolean,
    modifier: Modifier = Modifier,
    shimmerColor: Color = LocalAccentColor.current.copy(alpha = 0.2f),
    durationMs: Int = 1200
) {
    if (!isShimmering) return
    
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "shimmer_offset"
    )
    
    Box(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        shimmerColor,
                        Color.Transparent
                    ),
                    start = Offset(shimmerOffset * 1000, 0f),
                    end = Offset((shimmerOffset + 1) * 1000, 0f)
                )
            )
    )
}

/**
 * InputPillBorder - Animated border for input field.
 *
 * Single Responsibility: Only handles border animation.
 */
@Composable
fun InputPillBorder(
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    borderColor: Color = LocalAccentColor.current,
    defaultBorderColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val currentBorderColor by androidx.compose.animation.core.animateColorAsState(
        targetValue = if (isFocused) borderColor else defaultBorderColor,
        animationSpec = androidx.compose.animation.core.tween(200)
    )

    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isFocused) 1.5.dp else 0.5.dp,
        animationSpec = androidx.compose.animation.core.tween(200)
    )

    Box(
        modifier = modifier
            .border(
                width = borderWidth,
                color = currentBorderColor,
                shape = RoundedCornerShape(24.dp)
            )
    )
}
