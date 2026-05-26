package com.example.smarty.features.games.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import com.example.smarty.ui.theme.SmartyBrushes
import kotlin.random.Random

/**
 * CoinTossScreen - A calm, centralized decision system.
 * Features a metallic 3D-flipping coin with physical toss animation.
 */
@Composable
fun CoinTossScreen(onClose: () -> Unit) {
    CoinTossGameContent(onClose = onClose)
}

@Composable
fun CoinTossGameContent(
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current // For Haptics

    // Animation States
    val rotationY = remember { Animatable(0f) }
    val translationY = remember { Animatable(0f) }
    val shadowScale = remember { Animatable(1f) }

    // Logic States
    var isTossing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var resultIsHeads by remember { mutableStateOf(true) }
    var showResult by remember { mutableStateOf(false) }

    // Constants for Coin UI
    val coinSize = 140.dp
    val accentColor = com.example.smarty.ui.LocalAccentColor.current
    val metallicGradient = SmartyBrushes.metallicSilver

    // Liquid Highlight Pulse
    val infiniteTransition = rememberInfiniteTransition(label = "LiquidPulse")
    val liquidOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "LiquidOffset"
    )

    // Effect: Auto-start first toss
    LaunchedEffect(Unit) {
        tossCoin(
            rotationY, translationY, shadowScale,
            onResultCalculated = { heads -> resultIsHeads = heads },
            onLand = {
                showResult = true
                resultText = if (resultIsHeads) "HEADS" else "TAILS"
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            },
            onStart = { isTossing = true; showResult = false },
            onEnd = { isTossing = false }
        )
    }

    // ── Bounded column: fixed height so it sits properly in the bottom sheet ──
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isTossing) {
                    scope.launch {
                        tossCoin(
                            rotationY, translationY, shadowScale,
                            onResultCalculated = { heads -> resultIsHeads = heads },
                            onLand = {
                                showResult = true
                                resultText = if (resultIsHeads) "HEADS" else "TAILS"
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            },
                            onStart = { isTossing = true; showResult = false },
                            onEnd = { isTossing = false }
                        )
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Top hint ──
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !isTossing && !showResult,
                enter = fadeIn(), exit = fadeOut()
            ) {
                Text(
                    text = "Tap to toss again",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }

        // ── Coin + shadow ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Shadow
            Box(
                modifier = Modifier
                    .offset(y = (coinSize / 2) + 8.dp)
                    .size(width = 80.dp * shadowScale.value, height = 12.dp * shadowScale.value)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f * shadowScale.value),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // The Coin
            Box(
                modifier = Modifier
                    .size(coinSize)
                    .graphicsLayer {
                        this.rotationY = rotationY.value
                        this.translationY = translationY.value
                        this.rotationX = if (isTossing) sin(rotationY.value * 0.05f) * 15f else 0f
                        cameraDistance = 16f * density
                    }
                    .shadow(elevation = if (isTossing) 12.dp else 6.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(metallicGradient),
                contentAlignment = Alignment.Center
            ) {
                // Liquid shine
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            colors = listOf(Color.White.copy(0f), Color.White.copy(0.15f), Color.White.copy(0f)),
                            start = androidx.compose.ui.geometry.Offset(liquidOffset * 500f, 0f),
                            end = androidx.compose.ui.geometry.Offset(liquidOffset * 500f + 200f, 600f)
                        )
                    )
                )
                Box(modifier = Modifier.fillMaxSize().padding(4.dp).border(2.dp, Color.White.copy(0.1f), CircleShape))
                Box(modifier = Modifier.fillMaxSize().padding(16.dp).border(1.dp, Color.Gray.copy(0.2f), CircleShape))

                val isBackVisible = (abs(rotationY.value) % 360) in 90f..270f
                if (!isBackVisible) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GeometricPattern()
                        Text("HEADS", style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                            color = Color.DarkGray.copy(0.7f), letterSpacing = 2.sp
                        ))
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.rotationY = 180f }, contentAlignment = Alignment.Center) {
                        GeometricPattern()
                        Text("TAILS", style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                            color = Color.DarkGray.copy(0.7f), letterSpacing = 2.sp
                        ))
                    }
                }
            }
        }

        // ── Result text ──
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showResult,
                enter = fadeIn() + expandVertically(), exit = fadeOut()
            ) {
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 8.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    color = accentColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun GeometricPattern() {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(90.dp)) {
        val stroke = 1.5.dp.toPx()
        val color = Color.DarkGray.copy(alpha = 0.15f)

        // Concentric circles
        for (i in 1..4) {
            drawCircle(
                color = color,
                radius = (i * 11).dp.toPx(),
                style = Stroke(width = stroke)
            )
        }

        // Star pattern
        val radius = size.minDimension / 2
        for (i in 0 until 8) {
             // Just simple radial lines for a classic coin look
            rotate(degrees = i * 45f) {
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - radius * 0.3f),
                    end = Offset(center.x, center.y - radius * 0.8f),
                    strokeWidth = stroke
                )
            }
        }
    }
}

// Optimized orchestration for the toss
private suspend fun tossCoin(
    rotationY: Animatable<Float, AnimationVector1D>,
    translationY: Animatable<Float, AnimationVector1D>,
    shadowScale: Animatable<Float, AnimationVector1D>,
    onResultCalculated: (Boolean) -> Unit,
    onLand: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    kotlinx.coroutines.coroutineScope {
        onStart()

        val resultIsHeads = Random.nextBoolean()

        // --- Calculate Target Rotation ---
        val currentRot = rotationY.value
        val currentMod = currentRot % 360f

        // Target is 0 (Heads) or 180 (Tails) relative to a full circle
        // We always want to land on a multiple of 180
        val targetMod = if (resultIsHeads) 0f else 180f

        // Calculate forward distance to target
        // If target is "behind" us in the mod cycle, we go around to next cycle
        var diff = targetMod - currentMod
        // Normalize diff to be positive [0, 360) for forward rotation
        if (diff <= 0f) {
             diff += 360f
        }

        // Minimum spins to feel satisfying
        val minSpins = 5
        val rotationDelta = (minSpins * 360f) + diff
        val targetRotation = currentRot + rotationDelta

        onResultCalculated(resultIsHeads)

        val tossDuration = 1200 // Slightly longer for weight

        // 1. Launch Rotation (Independent)
        launch {
            rotationY.animateTo(
                targetValue = targetRotation,
                animationSpec = tween(tossDuration, easing = FastOutSlowInEasing)
            )
        }

        // 2. Launch Shadow (Independent)
        launch {
            // Shrink shadow at peak
            shadowScale.animateTo(
                targetValue = 0.4f,
                animationSpec = tween(tossDuration / 2, easing = FastOutSlowInEasing)
            )
            // Grow shadow at splashdown
            shadowScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(tossDuration / 2, easing = LinearEasing) // Linear/Accelerate for drop
            )
        }

        // 3. Toss Movement (Sequential Up/Down)

        // UP
        translationY.animateTo(
            targetValue = -300f, // Compact toss for bottom sheet
            animationSpec = tween(tossDuration / 2, easing = FastOutSlowInEasing) // Decelerate up
        )

        // DOWN
        translationY.animateTo(
            targetValue = 0f,
            animationSpec = tween(tossDuration / 2, easing = BounceInterpolator)
        )

        // Landed!
        onLand()

        // Subtle bounce/settle
        translationY.animateTo(
             targetValue = -20f,
             animationSpec = tween(150, easing = FastOutSlowInEasing)
        )
        translationY.animateTo(
             targetValue = 0f,
             animationSpec = tween(150, easing = LinearOutSlowInEasing)
        )

        onEnd()
    }
}

// Custom gravity-like easing if needed, or just use built-ins
val BounceInterpolator: Easing = Easing { fraction ->
    // Simple acceleration for falling: y = x^2
    fraction * fraction
}
