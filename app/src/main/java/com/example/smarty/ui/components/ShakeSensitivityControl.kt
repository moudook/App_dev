package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MonoFont
import kotlin.math.*

private const val BASELINE_SENSITIVITY = 0.63f
private const val START_ANGLE = 140f
private const val SWEEP_ANGLE = 260f

/**
 * Reimagined Shake Sensitivity Control: The Kinetic Vibration Hub.
 *
 * Featuring:
 * - Dynamic Vibration: Central icon physically shakes based on sensitivity.
 * - Seismic Ripples: Organic waves replace technical tick marks.
 * - User-Friendly Context: Labels focused on physical effort and responsiveness.
 */
@Composable
fun ShakeSensitivityControl(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f

    // Smoothly animate the value for visual continuity
    val animatedSensitivity by animateFloatAsState(
        targetValue = sensitivity,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "sensitivity_anim",
    )

    val options =
        listOf(
            0.00f to "0%",
            0.35f to "35%",
            0.50f to "50%",
            0.75f to "75%",
            1.00f to "100%",
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Percentage Value Display (Large & Clear)
        Text(
            text = "${(animatedSensitivity * 100).toInt()}%",
            style =
                MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFont,
                    letterSpacing = (-1).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "SHAKE SENSITIVITY",
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 5-Option Premium Selection (Pill Style)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                val isSelected = abs(sensitivity - value) < 0.01f

                // Colors following PREFERRED_UI_REFERENCE.md Section 1
                val pillBackground =
                    if (isDark) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.85f,
                        )
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                val pillBorder =
                    if (isDark) {
                        Color.White.copy(
                            alpha = 0.15f,
                        )
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    }
                val focusHighlightColor = if (isDark) Color.White else Color.Black

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onSensitivityChange(value)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(26.dp), // History Pill Shape
                    color = pillBackground,
                    border =
                        BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) focusHighlightColor else pillBorder,
                        ),
                    tonalElevation = if (isSelected) 8.dp else 0.dp,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                            color = if (isSelected) focusHighlightColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Context Helper Text
        Text(
            text =
                when {
                    sensitivity == 0.00f -> "Shake gestures disabled."
                    sensitivity <= 0.35f -> "Requires a strong, intentional shake."
                    sensitivity >= 0.75f -> "Triggers with minimal movement."
                    else -> "Balanced for daily use."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
