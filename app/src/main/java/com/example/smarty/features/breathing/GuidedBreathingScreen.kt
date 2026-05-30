package com.example.smarty.features.breathing

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Breathing phases — 5-phase cycle.
 */
enum class BreathPhase(val text: String, val scale: Float, val durationMs: Int) {
    IDLE("Guided Breathing", 1f, 1000),
    INHALE("Breathe in", 2.2f, 4000),
    HOLD_IN("Hold", 2.2f, 4000),
    EXHALE("Breathe out", 1f, 6000),
    HOLD_OUT("Hold", 1f, 2000),
    COMPLETED("Peace.", 1f, 2000);
}

/**
 * Guided Breathing content — rendered inside a ModalBottomSheet.
 *
 * Follows the same pattern as TicTacToeGameContent / CoinTossGameContent:
 * - Content composable only, sheet handles background/corners/drag handle
 * - Uses MaterialTheme colors for theme awareness
 * - Takes onClose for dismissal
 */
@Composable
fun GuidedBreathingContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()

    // Theme-aware colors from MaterialTheme
    val orbColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant
    val guideRingColor = if (isDark) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val dotActive = MaterialTheme.colorScheme.onSurface
    val dotInactive = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    var phase by remember { mutableStateOf(BreathPhase.IDLE) }
    var cycle by remember { mutableIntStateOf(0) }

    val isRunning = phase != BreathPhase.IDLE && phase != BreathPhase.COMPLETED

    // Orb scale animation
    val orbScale by animateFloatAsState(
        targetValue = phase.scale,
        animationSpec = tween(
            durationMillis = phase.durationMs,
            easing = EaseInOut,
        ),
        label = "orbScale",
    )

    // Phase text alpha
    val textAlpha by animateFloatAsState(
        targetValue = if (phase == BreathPhase.IDLE) 0.5f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "textAlpha",
    )

    // Phase timer
    LaunchedEffect(phase, cycle) {
        if (phase == BreathPhase.IDLE || phase == BreathPhase.COMPLETED) return@LaunchedEffect

        delay(phase.durationMs.toLong())

        phase = when (phase) {
            BreathPhase.INHALE -> BreathPhase.HOLD_IN
            BreathPhase.HOLD_IN -> BreathPhase.EXHALE
            BreathPhase.EXHALE -> BreathPhase.HOLD_OUT
            BreathPhase.HOLD_OUT -> {
                if (cycle >= 3) {
                    BreathPhase.COMPLETED
                } else {
                    cycle++
                    BreathPhase.INHALE
                }
            }
            else -> phase
        }
    }

    // Completion — auto-dismiss
    LaunchedEffect(phase) {
        if (phase == BreathPhase.COMPLETED) {
            delay(2000)
            onClose()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Breathing area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer guide ring
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .drawBehind {
                        drawCircle(
                            color = guideRingColor,
                            radius = size.minDimension / 2,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    },
            )

            // Breathing orb
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(orbScale)
                    .clip(CircleShape)
                    .background(orbColor),
            )

            // Phase text — overlaid on center
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    fadeIn(animationSpec = tween(1500)) togetherWith fadeOut(animationSpec = tween(1500))
                },
                label = "phaseText",
            ) { currentPhase ->
                Text(
                    text = currentPhase.text,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Light,
                        fontStyle = FontStyle.Normal,
                        fontSize = 26.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = textPrimary.copy(alpha = textAlpha),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Cycle dots
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (index < cycle) dotActive else dotInactive),
                        )
                    }
                }
            }

            // Start button
            AnimatedVisibility(
                visible = !isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                TextButton(
                    onClick = {
                        cycle = 1
                        phase = BreathPhase.INHALE
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = textPrimary,
                    ),
                ) {
                    Text(
                        text = if (phase == BreathPhase.COMPLETED) "Begin Again" else "Start",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Legacy wrapper — keeps old API working if referenced elsewhere.
 */
@Composable
fun GuidedBreathingScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GuidedBreathingContent(onClose = onDismiss, modifier = modifier)
}
