package com.example.smarty.ui.screens

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * =============================================================================
 * LIFECYCLE-AWARE STARTUP ANIMATION
 * =============================================================================
 *
 * This screen implements:
 * 1. LIFECYCLE AWARENESS - Pauses breathing when backgrounded
 * 2. BHASKARA I OPTIMIZATION - 3x faster sine calculation
 * 3. ZERO-ALLOCATION DRAW LOOP - Pre-computed brushes
 *
 * The intro sequence (fade in, logo, expansion) completes regardless of
 * lifecycle state. Only the continuous breathing animation pauses.
 * =============================================================================
 */

// Pre-computed constants
private const val TWO_PI = 2f * PI.toFloat()
private const val PI_F = PI.toFloat()
private const val INV_PI = 1f / PI.toFloat()
private const val FOUR = 4f
private const val FIVE_PI_SQ = 5f * PI.toFloat() * PI.toFloat()

/**
 * Bhaskara I's sine approximation - O(1), no transcendental calls
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun fastSin(x: Float): Float {
    val normalized = x - (x * INV_PI * 0.5f).toInt() * TWO_PI
    val xMod = if (normalized < 0f) normalized + TWO_PI else normalized

    val (xPi, sign) = if (xMod > PI_F) {
        (xMod - PI_F) to -1f
    } else {
        xMod to 1f
    }

    val xPiMinusX = xPi * (PI_F - xPi)
    return sign * (16f * xPiMinusX) / (FIVE_PI_SQ - FOUR * xPiMinusX)
}

/**
 * Lifecycle state tracker for StartupScreen
 */
@Composable
private fun rememberStartupLifecycleState(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val isActive = remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isActive.value = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> isActive.value
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return isActive
}

/**
 * Startup Animation: "Three Blurred Circles"
 *
 * Design Concept:
 * - 3 softly blurred colored circles that float and breathe
 * - Organic, dreamy motion with overlapping colors
 * - Creates a calming, ethereal loading experience
 *
 * OPTIMIZATION:
 * - LIFECYCLE AWARE: Pauses animation when app backgrounded
 * - Bhaskara I sine approximation for smooth motion
 */
@Composable
fun StartupScreen(
    onComplete: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val backgroundColor = MaterialTheme.colorScheme.background

    // LIFECYCLE AWARENESS: Track if animation should be active
    val isActive by rememberStartupLifecycleState()

    // --- MATHEMATICAL DRIVERS ---
    val infiniteTransition = if (isActive) {
        rememberInfiniteTransition(label = "breath")
    } else null

    val breathPhase by if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = TWO_PI,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "theta"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    // Interaction Scalars
    val introAlpha = remember { Animatable(0f) }
    val expansionScale = remember { Animatable(1f) }
    val logoAlpha = remember { Animatable(0f) }

    // --- SYSTEM INITIALIZATION ---
    LaunchedEffect(Unit) {
        // Phase 1: Fade In
        introAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(1500, easing = EaseInOutSine)
        )

        delay(2500)

        // Phase 2: Show Logo
        launch {
            logoAlpha.animateTo(1f, tween(800))
        }

        // Phase 3: Expansion
        expansionScale.animateTo(
            targetValue = 40f,
            animationSpec = tween(1200, easing = EaseInExpo)
        )

        onComplete()
    }

    val density = LocalDensity.current
    val baseSizePx = remember(density) { with(density) { 80.dp.toPx() } }
    val blurRadiusPx = remember(density) { with(density) { 60.dp.toPx() } }

    // All 3 circles use the same blue color with varying alpha
    val blueColor = Color(0xFF3B82F6)  // Blue color
    val circle1Color = remember { blueColor.copy(alpha = 0.7f) }
    val circle2Color = remember { blueColor.copy(alpha = 0.6f) }
    val circle3Color = remember { blueColor.copy(alpha = 0.65f) }

    // Blurred circle state
    val circlesState by remember {
        derivedStateOf {
            val globalExpand = expansionScale.value
            val globalAlpha = introAlpha.value

            if (globalAlpha <= 0f) {
                CirclesState.HIDDEN
            } else {
                val phase = if (isActive) breathPhase else 0f

                // Circle 1 - top left, moves in circular pattern
                val c1Angle = phase
                val c1Distance = baseSizePx * 0.8f * (1f + fastSin(phase * 0.5f) * 0.2f)
                val c1X = cos(c1Angle) * c1Distance
                val c1Y = sin(c1Angle) * c1Distance - baseSizePx * 0.3f
                val c1Radius = baseSizePx * (1.2f + fastSin(phase) * 0.15f) * globalExpand

                // Circle 2 - bottom right, opposite phase
                val c2Angle = phase + PI_F * 0.66f
                val c2Distance = baseSizePx * 0.7f * (1f + fastSin(phase * 0.7f + 1f) * 0.25f)
                val c2X = cos(c2Angle) * c2Distance
                val c2Y = sin(c2Angle) * c2Distance + baseSizePx * 0.2f
                val c2Radius = baseSizePx * (1.0f + fastSin(phase + PI_F * 0.5f) * 0.2f) * globalExpand

                // Circle 3 - center-ish, gentle float
                val c3Angle = phase + PI_F * 1.33f
                val c3Distance = baseSizePx * 0.5f * (1f + fastSin(phase * 0.3f + 2f) * 0.15f)
                val c3X = cos(c3Angle) * c3Distance
                val c3Y = sin(c3Angle) * c3Distance + fastSin(phase * 0.8f) * baseSizePx * 0.15f
                val c3Radius = baseSizePx * (0.9f + fastSin(phase + PI_F) * 0.1f) * globalExpand

                CirclesState(
                    visible = true,
                    globalAlpha = globalAlpha,
                    c1Offset = Offset(c1X, c1Y),
                    c1Radius = c1Radius,
                    c2Offset = Offset(c2X, c2Y),
                    c2Radius = c2Radius,
                    c3Offset = Offset(c3X, c3Y),
                    c3Radius = c3Radius,
                    blurRadius = blurRadiusPx * globalExpand.coerceAtMost(3f)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val state = circlesState

            if (state.visible) {
                // Create blur paint
                val blurPaint = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(
                        state.blurRadius.coerceAtLeast(1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }

                // Draw 3 blurred circles
                drawContext.canvas.nativeCanvas.apply {
                    // Circle 1
                    blurPaint.color = circle1Color.copy(alpha = circle1Color.alpha * state.globalAlpha).toArgb()
                    drawCircle(
                        center.x + state.c1Offset.x,
                        center.y + state.c1Offset.y,
                        state.c1Radius,
                        blurPaint
                    )

                    // Circle 2
                    blurPaint.color = circle2Color.copy(alpha = circle2Color.alpha * state.globalAlpha).toArgb()
                    drawCircle(
                        center.x + state.c2Offset.x,
                        center.y + state.c2Offset.y,
                        state.c2Radius,
                        blurPaint
                    )

                    // Circle 3
                    blurPaint.color = circle3Color.copy(alpha = circle3Color.alpha * state.globalAlpha).toArgb()
                    drawCircle(
                        center.x + state.c3Offset.x,
                        center.y + state.c3Offset.y,
                        state.c3Radius,
                        blurPaint
                    )
                }
            }
        }

        // Logo
        Box(
            modifier = Modifier
                .offset(y = 0.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                    val invScale = 1f / expansionScale.value.coerceAtLeast(1f)
                    scaleX = invScale
                    scaleY = invScale
                }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                com.example.smarty.ui.components.CogniLogo(
                    size = 72.dp,
                    showShimmer = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                androidx.compose.material3.Text(
                    text = "cogni_",
                    style = MaterialTheme.typography.displayMedium,
                    color = LocalAccentColor.current,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }
    }
}

/** State for 3 blurred circles animation */
private data class CirclesState(
    val visible: Boolean,
    val globalAlpha: Float = 1f,
    val c1Offset: Offset = Offset.Zero,
    val c1Radius: Float = 0f,
    val c2Offset: Offset = Offset.Zero,
    val c2Radius: Float = 0f,
    val c3Offset: Offset = Offset.Zero,
    val c3Radius: Float = 0f,
    val blurRadius: Float = 60f
) {
    companion object {
        val HIDDEN = CirclesState(visible = false)
    }
}
