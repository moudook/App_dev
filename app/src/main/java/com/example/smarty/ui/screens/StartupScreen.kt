package com.example.smarty.ui.screens

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Startup Animation: "Three Blurred Circles"
 *
 * Design Concept:
 * - 3 softly blurred colored circles that float and breathe
 * - Organic, dreamy motion with overlapping colors
 * - Creates a calming, ethereal loading experience
 */

private const val TWO_PI = 2f * PI.toFloat()
private const val PI_F = PI.toFloat()

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return isActive
}

@Composable
fun StartupScreen(
    onComplete: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    val backgroundColor = MaterialTheme.colorScheme.background

    // LIFECYCLE AWARENESS: Track if animation should be active
    val isActive by rememberStartupLifecycleState()

    // --- ANIMATION DRIVERS ---
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

    // --- ANIMATION SEQUENCE ---
    LaunchedEffect(Unit) {
        // Phase 1: Fade In (faster)
        introAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(800, easing = EaseInOutSine)
        )

        delay(1000)

        // Phase 2: Show Logo (parallel - faster)
        launch {
            logoAlpha.animateTo(1f, tween(400))
        }

        // Phase 3: Expansion (faster)
        expansionScale.animateTo(
            targetValue = 40f,
            animationSpec = tween(600, easing = EaseInExpo)
        )

        onComplete()
    }

    val density = LocalDensity.current
    val baseSizePx = remember(density) { with(density) { 80.dp.toPx() } }
    val blurRadiusPx = remember(density) { with(density) { 60.dp.toPx() } }

    // All 3 circles use the app's accent color
    val circleColor = remember(accentColor) { accentColor.copy(alpha = 0.65f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val globalExpand = expansionScale.value
            val globalAlpha = introAlpha.value

            if (globalAlpha > 0f) {
                val phase = if (isActive) breathPhase else 0f

                // Circle 1
                val c1Angle = phase
                val c1Distance = baseSizePx * 0.8f * (1f + sin(phase * 0.5f).toFloat() * 0.2f)
                val c1X = cos(c1Angle) * c1Distance
                val c1Y = sin(c1Angle) * c1Distance - baseSizePx * 0.3f
                val c1Radius = baseSizePx * (1.2f + sin(phase).toFloat() * 0.15f) * globalExpand

                // Circle 2
                val c2Angle = phase + PI_F * 0.66f
                val c2Distance = baseSizePx * 0.7f * (1f + sin(phase * 0.7f + 1f).toFloat() * 0.25f)
                val c2X = cos(c2Angle) * c2Distance
                val c2Y = sin(c2Angle) * c2Distance + baseSizePx * 0.2f
                val c2Radius = baseSizePx * (1.0f + sin(phase + PI_F * 0.5f).toFloat() * 0.2f) * globalExpand

                // Circle 3
                val c3Angle = phase + PI_F * 1.33f
                val c3Distance = baseSizePx * 0.5f * (1f + sin(phase * 0.3f + 2f).toFloat() * 0.15f)
                val c3X = cos(c3Angle) * c3Distance
                val c3Y = sin(c3Angle) * c3Distance + sin(phase * 0.8f).toFloat() * baseSizePx * 0.15f
                val c3Radius = baseSizePx * (0.9f + sin(phase + PI_F).toFloat() * 0.1f) * globalExpand

                val blurRadius = blurRadiusPx * globalExpand.coerceAtMost(3f)

                // Create blur paint
                val blurPaint = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(
                        blurRadius.coerceAtLeast(1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                    color = circleColor.copy(alpha = circleColor.alpha * globalAlpha).toArgb()
                }

                // Draw 3 blurred circles
                drawContext.canvas.nativeCanvas.apply {
                    drawCircle(center.x + c1X, center.y + c1Y, c1Radius, blurPaint)
                    drawCircle(center.x + c2X, center.y + c2Y, c2Radius, blurPaint)
                    drawCircle(center.x + c3X, center.y + c3Y, c3Radius, blurPaint)
                }
            }
        }

        // --- TEXT RENDERING WITH GPU LAYER ---
        val currentScale = expansionScale.value
        if (currentScale < 10f) {
            val fadeAlpha = if (currentScale > 5f) {
                val fadeProgress = (currentScale - 5f) / 5f
                (1f - fadeProgress).coerceIn(0f, 1f)
            } else 1f

            val textAlpha = logoAlpha.value * fadeAlpha

            if (textAlpha > 0.01f) {
                coil.compose.AsyncImage(
                    model = androidx.compose.ui.platform.LocalContext.current.let { context ->
                        coil.request.ImageRequest.Builder(context)
                            .data(com.example.smarty.R.drawable.startup_logo)
                            .size(256) // Downsample to ~256px, sufficient for 64dp
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = textAlpha
                            scaleX = currentScale
                            scaleY = currentScale
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                renderEffect = null
                            }
                        }
                )
            }
        }
    }
}
