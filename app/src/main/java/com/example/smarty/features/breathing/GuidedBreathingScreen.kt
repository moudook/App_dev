package com.example.smarty.features.breathing

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Breathing phases for the guided breathing exercise.
 */
enum class BreathPhase {
    INHALE, HOLD, EXHALE, REST
}

/**
 * Guided Breathing Exercise Screen
 * 
 * A micro-intervention for anxiety and pre-decision grounding.
 * Provides a simple animated circle with haptic pacing for breathing exercises.
 * 
 * Per ultimate.md: "Not in current feature list but mentioned in product thesis.
 * Add as a micro-intervention alongside coin toss and tic-tac-toe. Tiny footprint."
 */
@Composable
fun GuidedBreathingScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    var phase by remember { mutableStateOf(BreathPhase.INHALE) }
    var cycleCount by remember { mutableStateOf(0) }
    var isRunning by remember { mutableStateOf(true) }
    
    // Animation for the breathing circle
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (phase) {
                    BreathPhase.INHALE -> 4000
                    BreathPhase.HOLD -> 0 // No animation during hold
                    BreathPhase.EXHALE -> 6000
                    BreathPhase.REST -> 0
                },
                easing = LinearEasing
            ),
            repeatMode = if (phase == BreathPhase.INHALE) RepeatMode.Reverse else RepeatMode.Restart
        ),
        label = "scale"
    )
    
    // Phase timing
    LaunchedEffect(isRunning) {
        while (isRunning) {
            when (phase) {
                BreathPhase.INHALE -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(4000) // 4 seconds inhale
                    phase = BreathPhase.HOLD
                }
                BreathPhase.HOLD -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(2000) // 2 seconds hold
                    phase = BreathPhase.EXHALE
                }
                BreathPhase.EXHALE -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(6000) // 6 seconds exhale
                    phase = BreathPhase.REST
                }
                BreathPhase.REST -> {
                    delay(1000) // 1 second rest
                    cycleCount++
                    if (cycleCount >= 3) {
                        isRunning = false // Complete after 3 cycles
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
            title = { Text("Well Done!") },
            text = { Text("You've completed 3 breathing cycles. Feel calmer?") },
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
            }
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e),
                        Color(0xFF0f3460)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Phase instruction
            Text(
                text = when (phase) {
                    BreathPhase.INHALE -> "Breathe In"
                    BreathPhase.HOLD -> "Hold"
                    BreathPhase.EXHALE -> "Breathe Out"
                    BreathPhase.REST -> "Rest"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontSize = 28.sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Breathing circle
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (phase == BreathPhase.INHALE) scale else if (phase == BreathPhase.EXHALE) 1.4f - (scale - 0.6f) * 2 else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF4facfe),
                                Color(0xFF00f2fe),
                                Color(0xFF4facfe).copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Inner glow
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Cycle counter
            Text(
                text = "Cycle $cycleCount of 3",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Timer dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < cycleCount) Color(0xFF4facfe)
                                else Color.White.copy(alpha = 0.3f)
                            )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Close button
            TextButton(onClick = onDismiss) {
                Text("Skip", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

/**
 * Breathing exercise trigger button for use in other screens.
 */
@Composable
fun BreathingExerciseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color(0xFF4facfe)
        )
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Breathing Exercise",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Breathing Exercise")
    }
}