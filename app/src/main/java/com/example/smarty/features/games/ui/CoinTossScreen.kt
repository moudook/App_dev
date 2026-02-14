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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.example.smarty.ui.components.SmartyLogo
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * CoinTossScreen - A calm, centralized decision system.
 * Features a metallic 3D-flipping coin with physical toss animation.
 */
@Composable
fun CoinTossScreen(
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current // For Haptics

    // Animation States
    val rotationY = remember { Animatable(0f) }
    val translationY = remember { Animatable(0f) }
    val shadowScale = remember { Animatable(1f) }
    val contentAlpha = remember { Animatable(0f) }

    // Logic States
    var isTossing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var resultIsHeads by remember { mutableStateOf(true) }
    var showResult by remember { mutableStateOf(false) }

    // Constants for Coin UI
    val coinSize = 220.dp
    val metallicGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFFE0E0E0),
            Color(0xFFFFFFFF),
            Color(0xFFB0B0B0), // Darker shade for depth
            Color(0xFFFFFFFF),
            Color(0xFFE0E0E0)
        ),
        start = Offset(0f, 0f),
        end = Offset(100f, 1000f) // Angled light
    )

    // Effect: Fade in on entry and start first toss
    LaunchedEffect(Unit) {
        // Auto-start first toss
        tossCoin(
            rotationY,
            translationY,
            shadowScale,
            onResultCalculated = { heads ->
                resultIsHeads = heads
            },
            onLand = {
                showResult = true
                resultText = if (resultIsHeads) "HEADS" else "TAILS"
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            },
            onStart = {
                isTossing = true
                showResult = false // Hide previous result
            },
            onEnd = { isTossing = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .alpha(1f) // Ensure fully visible immediately
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isTossing) {
                    scope.launch {
                        tossCoin(
                            rotationY,
                            translationY,
                            shadowScale,
                            onResultCalculated = { heads ->
                                resultIsHeads = heads
                            },
                            onLand = {
                                showResult = true
                                resultText = if (resultIsHeads) "HEADS" else "TAILS"
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            },
                            onStart = {
                                isTossing = true
                                showResult = false
                            },
                            onEnd = { isTossing = false }
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Dynamic "TOSSING..." indicator or Hint
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isTossing && !showResult) {
                Text(
                    text = "Tap anywhere to toss",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // Shadow on the "floor"
        Box(
            modifier = Modifier
                .offset(y = 140.dp) // Positioned below the coin's rest position
                .size(width = 160.dp * shadowScale.value, height = 24.dp * shadowScale.value)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f * shadowScale.value),
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
                    cameraDistance = 16f * density // Increased distance for less distortion
                }
                .shadow(
                    elevation = if (isTossing) 10.dp else 4.dp,
                    shape = CircleShape,
                    spotColor = Color.Black.copy(alpha = 0.5f)
                )
                .clip(CircleShape)
                .background(metallicGradient),
            contentAlignment = Alignment.Center
        ) {
            // Metallic/Etched Rim effect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp) // Outer rim thickness
                    .border(2.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
            )

            // Inner Grooved Ring
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .border(1.dp, Color.Gray.copy(alpha = 0.2f), CircleShape)
            )

            // Content Logic
            val isBackVisible = (abs(rotationY.value) % 360) in 90f..270f

            if (!isBackVisible) {
                // HEADS SIDE
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    GeometricPattern()
                    Text(
                        text = "HEADS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray.copy(alpha = 0.7f),
                            letterSpacing = 2.sp
                        )
                    )
                }
            } else {
                // TAILS SIDE - Rotated 180 within the flipped parent
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.rotationY = 180f },
                    contentAlignment = Alignment.Center
                ) {
                    GeometricPattern()
                    Text(
                        text = "TAILS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray.copy(alpha = 0.7f),
                            letterSpacing = 2.sp
                        )
                    )
                }
            }
        }

        // Result Text Display
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showResult,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut()
            ) {
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 4.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Close Button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun GeometricPattern() {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(140.dp)) {
        val stroke = 1.5.dp.toPx()
        val color = Color.DarkGray.copy(alpha = 0.15f)

        // Concentric circles
        for (i in 1..4) {
            drawCircle(
                color = color,
                radius = (i * 18).dp.toPx(),
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
            targetValue = -500f, // Higher toss
            animationSpec = tween(tossDuration / 2, easing = FastOutSlowInEasing) // Decelerate up
        )

        // DOWN
        translationY.animateTo(
            targetValue = 0f,
            animationSpec = tween(tossDuration / 2, easing = BounceInterpolator) // Accelerate down with bounce? No, BounceInterpolator is not standard compose easing.
            // Using standard easing curve that simulates gravity (Accelerate)
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
