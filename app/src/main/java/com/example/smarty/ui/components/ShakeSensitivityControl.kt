package com.example.smarty.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import kotlin.math.*

// Colors for the gradient
private val LowSensitivityColor = Color(0xFF90CAF9) // Soft Blue
private val HighSensitivityColor = Color(0xFFB39DDB) // Soft Purple

// Baseline sensitivity - the recommended default
private const val BASELINE_SENSITIVITY = 0.63f

/**
 * Linear slider for shake sensitivity control.
 * - Horizontal track with segmented markers
 * - Pointer slides smoothly along the track via drag
 * - Low sensitivity (left) = "Stable" (hard to shake)
 * - High sensitivity (right) = "Sensitive" (easy to shake)
 *
 * @param sensitivity Current sensitivity value (0f to 1f)
 * @param onSensitivityChange Callback when sensitivity changes
 */
@Composable
fun ShakeSensitivityControl(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current

    // Slider dimensions
    val trackHeight = 12.dp
    val thumbRadius = 12.dp

    // Derived colors
    val activeTrackBrush = Brush.horizontalGradient(
        colors = listOf(LowSensitivityColor, accentColor)
    )
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = Color.White
    val thumbShadowColor = Color.Black.copy(alpha = 0.2f)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sensitivity Label and Value
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.shake_sensitivity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "${(sensitivity * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = accentColor
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp) // Touch target height
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newSensitivity = (offset.x / size.width).coerceIn(0f, 1f)
                        onSensitivityChange(newSensitivity)
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val newSensitivity = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSensitivityChange(newSensitivity)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val trackY = height / 2
                val trackHeightPx = trackHeight.toPx()
                val thumbRadiusPx = thumbRadius.toPx()

                // Draw inactive track (background)
                drawRoundRect(
                    color = inactiveTrackColor,
                    topLeft = Offset(0f, trackY - trackHeightPx / 2),
                    size = Size(width, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2)
                )

                // Draw segmented ticks (every 10%)
                val segments = 10
                for (i in 1 until segments) {
                    val x = width * (i / segments.toFloat())
                    // drawCircle(
                    //     color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    //     radius = 1.dp.toPx(),
                    //     center = Offset(x, trackY)
                    // )
                }

                // Draw active track (fill)
                val activeWidth = width * sensitivity
                drawRoundRect(
                    brush = activeTrackBrush,
                    topLeft = Offset(0f, trackY - trackHeightPx / 2),
                    size = Size(activeWidth, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2)
                )

                // Draw baseline marker (recommended setting)
                val baselineX = width * BASELINE_SENSITIVITY
                drawLine(
                    color = Color.White.copy(alpha = 0.8f),
                    start = Offset(baselineX, trackY - trackHeightPx / 2 + 2.dp.toPx()),
                    end = Offset(baselineX, trackY + trackHeightPx / 2 - 2.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Draw thumb
                val thumbX = width * sensitivity

                // Thumb shadow
                drawCircle(
                    color = thumbShadowColor,
                    radius = thumbRadiusPx + 2.dp.toPx(),
                    center = Offset(thumbX, trackY + 2.dp.toPx())
                )

                // Thumb border
                drawCircle(
                    color = accentColor.copy(alpha = 0.2f),
                    radius = thumbRadiusPx + 4.dp.toPx(),
                    center = Offset(thumbX, trackY)
                )

                // Thumb body
                drawCircle(
                    color = thumbColor,
                    radius = thumbRadiusPx,
                    center = Offset(thumbX, trackY)
                )

                // Thumb inner dot
                drawCircle(
                    color = accentColor,
                    radius = 4.dp.toPx(),
                    center = Offset(thumbX, trackY)
                )
            }
        }

        // Labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.less_sensitive),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.baseline_label),
                style = MaterialTheme.typography.labelSmall,
                color = if (abs(sensitivity - BASELINE_SENSITIVITY) < 0.05f) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 24.dp) // Offset to align roughly with 63%
            )

            Text(
                text = stringResource(R.string.more_sensitive),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
