package com.example.smarty.ui.components.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay

/**
 * StreamingCursor - Animated cursor indicator for live text streaming.
 *
 * Single Responsibility: Only displays the streaming cursor animation.
 * DRY: Extracted to avoid duplication and ensure consistent animation.
 */
@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier,
    cursorColor: Color = LocalAccentColor.current,
    isStreaming: Boolean = true,
) {
    if (!isStreaming) return

    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "cursor_opacity",
    )

    Box(
        modifier =
            modifier
                .padding(start = 2.dp, top = 4.dp)
                .size(width = 8.dp, height = 20.dp)
                .background(cursorColor.copy(alpha = if (cursorAlpha > 0.5f) 1f else 0f)),
    )
}

/**
 * TypewriterText - Manages typewriter animation state for streaming text.
 *
 * Single Responsibility: Only handles typewriter animation logic.
 * DRY: Centralized animation logic to avoid duplication.
 *
 * @param fullText The complete text to display
 * @param isStreaming Whether the text is still being streamed
 * @param charsPerFrame Number of characters to reveal per frame
 * @return The visible portion of the text
 */
@Composable
fun rememberTypewriterState(
    fullText: String,
    isStreaming: Boolean,
    charsPerFrame: Int = 2,
): String {
    val targetLength = fullText.length

    // State that persists across content updates within same message
    var displayPosition by remember(fullText) {
        mutableIntStateOf(if (isStreaming) 0 else targetLength)
    }

    // Run typewriter animation
    LaunchedEffect(isStreaming, targetLength) {
        if (isStreaming) {
            // Continue from current position, not restart
            while (displayPosition < targetLength) {
                val remaining = targetLength - displayPosition
                val step = minOf(charsPerFrame, remaining)
                displayPosition += step
                delay(33)
            }
        } else {
            displayPosition = targetLength
        }
    }

    // Get visible content
    return remember(displayPosition, fullText) {
        if (displayPosition >= fullText.length) {
            fullText
        } else {
            fullText.substring(0, displayPosition)
        }
    }
}
