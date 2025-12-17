package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.theme.YoutubeRed

/**
 * YouTube watch button for note cards
 * Shows play icon with optional loading state - opens video player
 */
@Composable
fun YouTubePlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isCompact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) YoutubeRed.copy(alpha = 0.9f) else YoutubeRed,
        animationSpec = tween(100),
        label = "bgColor"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isLoading) {
                            isPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tryAwaitRelease()
                            isPressed = false
                        }
                    },
                    onTap = {
                        if (!isLoading) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    }
                )
            },
        shape = RoundedCornerShape(if (isCompact) 8.dp else 12.dp),
        color = backgroundColor,
        shadowElevation = if (isPressed) 2.dp else 4.dp
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isCompact) 8.dp else 12.dp,
                vertical = if (isCompact) 6.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (isCompact) 16.dp else 20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Watch video",
                    tint = Color.White,
                    modifier = Modifier.size(if (isCompact) 16.dp else 20.dp)
                )
            }

            if (!isCompact) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isLoading) "Loading..." else "Watch",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Circular YouTube play button - minimal style
 */
@Composable
fun YouTubePlayButtonCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    size: Int = 36
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "scale"
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .background(
                color = YoutubeRed,
                shape = RoundedCornerShape(50)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isLoading) {
                            isPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tryAwaitRelease()
                            isPressed = false
                        }
                    },
                    onTap = {
                        if (!isLoading) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size((size * 0.5f).dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play audio",
                tint = Color.White,
                modifier = Modifier.size((size * 0.6f).dp)
            )
        }
    }
}
