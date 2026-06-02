package com.example.smarty.features.breathing

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Breathing phases — 5-phase cycle.
 */
enum class BreathPhase(
    val text: String,
    val scale: Float,
    val durationMs: Int,
) {
    IDLE("Guided Breathing", 1f, 1000),
    INHALE("Breathe in", 2.2f, 4000),
    HOLD_IN("Hold", 2.2f, 4000),
    EXHALE("Breathe out", 1f, 6000),
    HOLD_OUT("Hold", 1f, 2000),
    COMPLETED("Peace.", 1f, 2000),
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

    // Theme-aware colors using pure MaterialTheme constraints
    val orbColor = MaterialTheme.colorScheme.primaryContainer
    val guideRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val dotActive = orbColor // Matches the orb color as requested
    val dotInactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    var phase by remember { mutableStateOf(BreathPhase.IDLE) }
    var cycle by remember { mutableIntStateOf(0) }

    val isRunning = phase != BreathPhase.IDLE && phase != BreathPhase.COMPLETED

    // Orb scale animation
    val orbScale by animateFloatAsState(
        targetValue = phase.scale,
        animationSpec =
            tween(
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

        phase =
            when (phase) {
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

    // Removed auto-dismissal to let user rest in the Peace phase

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        // Reduced bottom padding since button is removed
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Breathing area
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isRunning,
                    ) {
                        cycle = 1
                        phase = BreathPhase.INHALE
                    },
            contentAlignment = Alignment.Center,
        ) {
            // Outer guide ring
            Box(
                modifier =
                    Modifier
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
                modifier =
                    Modifier
                        .size(120.dp)
                        .scale(orbScale)
                        .clip(CircleShape)
                        .background(orbColor),
            )

            // Phase text — overlaid on center
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    // Incoming text slides up from below while fading in
                    val enter =
                        slideInVertically(
                            initialOffsetY = { height -> height },
                            animationSpec = tween(1000, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(1000))

                    // Outgoing text slides up and out while fading out
                    val exit =
                        slideOutVertically(
                            targetOffsetY = { height -> -height },
                            animationSpec = tween(1000, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(1000))

                    enter togetherWith exit
                },
                contentAlignment = Alignment.Center,
                label = "phaseText",
            ) { currentPhase ->
                Text(
                    text = currentPhase.text,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = CursiveFont,
                            fontWeight = FontWeight.Normal,
                            fontStyle = FontStyle.Italic,
                            fontSize = 30.sp,
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
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (index < cycle) dotActive else dotInactive),
                        )
                    }
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
