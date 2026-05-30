package com.example.smarty.features.breathing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.theme.ComponentColors
import com.example.smarty.ui.theme.SmartyBrushes
import kotlinx.coroutines.delay

/**
 * Breathing phases for the guided breathing exercise.
 */
enum class BreathPhase {
    INHALE,
    HOLD,
    EXHALE,
    REST,
}

/**
 * Guided Breathing Exercise Screen
 */
@Composable
fun GuidedBreathingScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    var phase by remember { mutableStateOf(BreathPhase.INHALE) }
    var cycleCount by remember { mutableStateOf(0) }
    var isRunning by remember { mutableStateOf(true) }

    // Animation for the breathing circle
    val scale by animateFloatAsState(
        targetValue =
            when (phase) {
                BreathPhase.INHALE -> 1.0f
                BreathPhase.HOLD -> 1.0f
                BreathPhase.EXHALE -> 0.6f
                BreathPhase.REST -> 0.6f
            },
        animationSpec =
            tween(
                durationMillis =
                    when (phase) {
                        BreathPhase.INHALE -> 4000
                        BreathPhase.HOLD -> 0
                        BreathPhase.EXHALE -> 6000
                        BreathPhase.REST -> 0
                    },
                easing = LinearEasing,
            ),
        label = "scale",
    )

    // Phase timing
    LaunchedEffect(isRunning) {
        while (isRunning) {
            when (phase) {
                BreathPhase.INHALE -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(4000)
                    phase = BreathPhase.HOLD
                }
                BreathPhase.HOLD -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(2000)
                    phase = BreathPhase.EXHALE
                }
                BreathPhase.EXHALE -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(6000)
                    phase = BreathPhase.REST
                }
                BreathPhase.REST -> {
                    delay(1000)
                    cycleCount++
                    if (cycleCount >= 3) {
                        isRunning = false
                    } else {
                        phase = BreathPhase.INHALE
                    }
                }
            }
        }
    }

    // Completion dialog
    if (!isRunning && cycleCount >= 3) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Well done.") },
            text = { Text("3 cycles done. Feeling calmer?") },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cycleCount = 0
                    isRunning = true
                    phase = BreathPhase.INHALE
                }) {
                    Text("Do More")
                }
            },
        )
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(SmartyBrushes.zenBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text =
                    when (phase) {
                        BreathPhase.INHALE -> "Breathe in"
                        BreathPhase.HOLD -> "Hold"
                        BreathPhase.EXHALE -> "Breathe out"
                        BreathPhase.REST -> "Rest"
                    },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontSize = 28.sp,
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier =
                    Modifier
                        .size(200.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(SmartyBrushes.breathingCircle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(SmartyBrushes.innerGlow),
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Cycle $cycleCount of 3",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < cycleCount) {
                                        ComponentColors.breathingAccent
                                    } else {
                                        Color.White.copy(alpha = 0.3f)
                                    },
                                ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            TextButton(onClick = onDismiss) {
                Text("Skip", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun BreathingExerciseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = ComponentColors.breathingAccent,
            ),
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Breathing Exercise",
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Breathing Exercise")
    }
}
