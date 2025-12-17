package com.example.smarty.ui.components.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.theme.AudioPink
import kotlin.math.abs

/**
 * Audio waveform visualization component
 * Shows amplitude bars with progress highlighting
 * Supports tap-to-seek interaction
 */
@Composable
fun AudioWaveform(
    waveformData: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color = AudioPink,
    unplayedColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    barWidth: Dp = 3.dp,
    barGap: Dp = 2.dp,
    cornerRadius: Dp = 2.dp,
    onSeek: ((Float) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .then(
                if (onSeek != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val seekProgress = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek(seekProgress)
                        }
                    }
                } else Modifier
            )
    ) {
        val barWidthPx = barWidth.toPx()
        val barGapPx = barGap.toPx()
        val cornerRadiusPx = cornerRadius.toPx()

        // If no waveform data, generate placeholder bars
        val amplitudes = if (waveformData.isEmpty()) {
            // Generate 50 placeholder bars with random-ish heights
            List(50) { i -> 0.3f + (abs((i * 17 % 10) - 5) / 10f) * 0.5f }
        } else {
            waveformData
        }

        val totalBars = amplitudes.size
        if (totalBars == 0) return@Canvas

        val availableWidth = size.width - (barGapPx * (totalBars - 1))
        val actualBarWidth = (availableWidth / totalBars).coerceAtMost(barWidthPx)
        val totalSpacing = (size.width - (actualBarWidth * totalBars)) / (totalBars - 1).coerceAtLeast(1)

        val centerY = size.height / 2
        val maxBarHeight = size.height * 0.9f

        amplitudes.forEachIndexed { index, amplitude ->
            val x = index * (actualBarWidth + totalSpacing)
            val normalizedProgress = index.toFloat() / totalBars

            // Calculate bar height (mirrored from center)
            val barHeight = (amplitude.coerceIn(0.1f, 1f) * maxBarHeight).coerceAtLeast(4f)
            val halfHeight = barHeight / 2

            val color = if (normalizedProgress <= progress) playedColor else unplayedColor

            // Draw bar (rounded rect from center)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - halfHeight),
                size = Size(actualBarWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
        }
    }
}

/**
 * Simple progress bar alternative when waveform data is not available
 */
@Composable
fun AudioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color = AudioPink,
    unplayedColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    height: Dp = 4.dp,
    onSeek: ((Float) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onSeek != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val seekProgress = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek(seekProgress)
                        }
                    }
                } else Modifier
            )
    ) {
        val cornerRadiusPx = size.height / 2

        // Background track
        drawRoundRect(
            color = unplayedColor,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
        )

        // Progress fill
        if (progress > 0) {
            drawRoundRect(
                color = playedColor,
                topLeft = Offset.Zero,
                size = Size(size.width * progress, size.height),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
        }
    }
}
