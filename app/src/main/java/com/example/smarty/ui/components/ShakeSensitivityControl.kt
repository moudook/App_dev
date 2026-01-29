package com.example.smarty.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.*
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ElectricBlue

// Colors for the gradient arc - Technical Palette
private val LowSensitivityColor = Color(0xFF90CAF9) // Soft Blue
private val HighSensitivityColor = Color(0xFFB39DDB) // Soft Purple (Replaced hardcoded ElectricBlue)

// Baseline sensitivity - the recommended default
private const val BASELINE_SENSITIVITY = 0.63f

/**
 * Arch slider for shake sensitivity control.
 * - 180-degree arc on the top (Rainbow shape)
 * - Pointer slides smoothly along the arc via drag
 * - Low sensitivity (left) = large movement needed
 * - High sensitivity (right) = light shake triggers
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

    // Arc dimensions
    val arcDiameter = 200.dp
    val arcDiameterPx = with(density) { arcDiameter.toPx() }
    val strokeWidth = 12.dp
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val pointerRadius = 16.dp
    val pointerRadiusPx = with(density) { pointerRadius.toPx() }

    // Box dimensions
    // Width accommodates the full diameter + pointer padding
    // Height accommodates the radius (half diameter) + pointer padding
    val boxWidth = arcDiameter + pointerRadius * 2
    val boxHeight = (arcDiameter / 2) + pointerRadius * 2

    // Radius of the actual arc path
    val arcRadius = (arcDiameterPx / 2) - strokeWidthPx / 2

    // Calculate center of the arc (relative to the Box/Canvas)
    // Center X is middle of the box
    // Center Y is at the bottom, allowing space for the pointer
    val centerXPx = with(density) { (boxWidth / 2).toPx() }
    val centerYPx = with(density) { (boxHeight - pointerRadius).toPx() } 

    // Convert sensitivity (0-1) to angle (180-360 degrees)
    // 0.0 (Low) -> 180 degrees (Left)
    // 1.0 (High) -> 360 degrees (Right)
    val pointerAngle = 180f + (sensitivity * 180f)

    // Pointer position on the arc
    val pointerX = centerXPx + arcRadius * cos(Math.toRadians(pointerAngle.toDouble())).toFloat()
    val pointerY = centerYPx + arcRadius * sin(Math.toRadians(pointerAngle.toDouble())).toFloat()

    // Current pointer color based on sensitivity
    val accentColor = LocalAccentColor.current
    val pointerColor = lerp(LowSensitivityColor, accentColor, sensitivity)

    // Capture theme colors outside Canvas
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .width(boxWidth)
            .height(boxHeight),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()

                        val touchX = change.position.x
                        val touchY = change.position.y

                        // Calculate angle from center
                        // Note: centerYPx is at the bottom of the arc
                        val dx = touchX - centerXPx
                        val dy = touchY - centerYPx

                        // atan2 returns -180 to 180
                        // Top-Left quadrant: x<0, y<0 -> -180 to -90
                        // Top-Right quadrant: x>0, y<0 -> -90 to 0
                        // We strictly want the top half (negative y relative to center)

                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

                        // Map valid input range (-180 to 0) to sensitivity
                        // We want -180 (Left) -> 0.0
                        // We want 0 (Right) -> 1.0

                        val newSensitivity = if (angle in -180.0..0.0) {
                            (angle + 180f) / 180f
                        } else if (angle > 0 && angle < 90) {
                             1f // Cap at right end
                        } else if (angle < -180 || angle > 90) {
                             0f // Cap at left end (atan2 logic wrap)
                        } else {
                            sensitivity // Should be covered
                        }

                        onSensitivityChange(newSensitivity.coerceIn(0f, 1f))
                    }
                }
        ) {
            // Draw the arc background (track)
            // Start at 180 (Left), Sweep 180 (Clockwise to Right)
            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(centerXPx - arcRadius, centerYPx - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Draw the active arc (gradient)
            // Start at 180, Sweep based on sensitivity
            val sweepAngle = sensitivity * 180f

            // Gradient mapping: 0.5 (180deg) to 1.0 (360deg) covers our arch
            drawArc(
                brush = Brush.sweepGradient(
                    0.5f to LowSensitivityColor,
                    0.75f to lerp(LowSensitivityColor, accentColor, 0.5f),
                    1.0f to accentColor,
                    center = Offset(centerXPx, centerYPx)
                ),
                startAngle = 180f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(centerXPx - arcRadius, centerYPx - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Draw baseline indicator at 63%
            val baselineAngle = 180f + (BASELINE_SENSITIVITY * 180f)  // ~293.4 degrees
            val baselineX = centerXPx + arcRadius * cos(Math.toRadians(baselineAngle.toDouble())).toFloat()
            val baselineY = centerYPx + arcRadius * sin(Math.toRadians(baselineAngle.toDouble())).toFloat()

            // Draw tick mark at baseline
            val tickLength = 12.dp.toPx()
            val tickAngleRad = Math.toRadians(baselineAngle.toDouble())
            val tickStartX = baselineX - (tickLength / 2) * cos(tickAngleRad).toFloat()
            val tickStartY = baselineY - (tickLength / 2) * sin(tickAngleRad).toFloat()
            val tickEndX = baselineX + (tickLength / 2) * cos(tickAngleRad).toFloat()
            val tickEndY = baselineY + (tickLength / 2) * sin(tickAngleRad).toFloat()

            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(tickStartX, tickStartY),
                end = Offset(tickEndX, tickEndY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Draw the pointer circle
            drawCircle(
                color = pointerColor,
                radius = pointerRadiusPx,
                center = Offset(pointerX, pointerY)
            )

            // Draw pointer inner circle (white)
            drawCircle(
                color = Color.White,
                radius = pointerRadiusPx * 0.6f,
                center = Offset(pointerX, pointerY)
            )
        }

        // Sensitivity label in the center (bottom of the arch)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Move it up a bit into the empty space of the arch
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Text(
                text = "${(sensitivity * 100).toInt()}%",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = pointerColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "baseline:_63%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Linear interpolation between two colors
 */
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}
