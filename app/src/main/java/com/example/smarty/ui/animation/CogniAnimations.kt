package com.example.smarty.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.*

/**
 * Apple-inspired animation system using spring physics and mathematical easing.
 *
 * Spring Physics Formula: F = -kx - cv
 * Where: k = stiffness, c = damping, x = displacement, v = velocity
 *
 * Critical damping ratio: ζ = c / (2 * sqrt(k * m))
 * - ζ < 1: Underdamped (bouncy)
 * - ζ = 1: Critically damped (smooth settle)
 * - ζ > 1: Overdamped (sluggish)
 */
object CogniMotion {

    // Spring configurations matching Apple's motion design
    // Based on UIKit's CASpringAnimation defaults

    /** Snappy response for UI feedback - critically damped */
    val snappy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh
    )

    /** Default interactive spring - slight bounce */
    val interactive = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 400f
    )

    /** Gentle spring for larger movements */
    val gentle = spring<Float>(
        dampingRatio = 0.9f,
        stiffness = 200f
    )

    /** Bouncy spring for playful feedback */
    val bouncy = spring<Float>(
        dampingRatio = 0.6f,
        stiffness = 300f
    )

    /** Very bouncy for emphasis */
    val veryBouncy = spring<Float>(
        dampingRatio = 0.5f,
        stiffness = 350f
    )

    /** Smooth settle for subtle animations */
    val smooth = spring<Float>(
        dampingRatio = 1f,  // Critically damped
        stiffness = 150f
    )

    /** Quick response for micro-interactions */
    val quick = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = 600f
    )
}

/**
 * Custom easing curves using cubic Bezier mathematics.
 * Bezier curve: B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃
 */
object CogniEasing {

    /** Apple's ease-out curve - fast start, smooth end */
    val appleEaseOut = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Apple's ease-in-out - smooth acceleration and deceleration */
    val appleEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Material emphasis - dramatic entry */
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Quick start, gradual end - for dismissals */
    val quickOut = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f)

    /** Slow start, quick end - for entries */
    val slowIn = CubicBezierEasing(0.4f, 0.0f, 1f, 1f)

    /**
     * Custom exponential ease-out: f(t) = 1 - 2^(-10t)
     * Creates a very smooth deceleration curve
     */
    val exponentialOut = Easing { fraction ->
        if (fraction == 1f) 1f else 1f - 2f.pow(-10f * fraction)
    }

    /**
     * Elastic ease-out using sine wave decay
     * f(t) = 2^(-10t) * sin((t - 0.075) * (2π / 0.3)) + 1
     */
    val elasticOut = Easing { fraction ->
        if (fraction == 0f || fraction == 1f) fraction
        else {
            val p = 0.3f
            val s = p / 4f
            2f.pow(-10f * fraction) * sin((fraction - s) * (2f * PI.toFloat()) / p) + 1f
        }
    }

    /**
     * Back ease-out - overshoots then settles
     * f(t) = 1 + c₃(t-1)³ + c₁(t-1)²
     */
    val backOut = Easing { fraction ->
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = fraction - 1f
        1f + c3 * t * t * t + c1 * t * t
    }
}

/**
 * Stagger delay calculator using mathematical sequences.
 * Creates natural-feeling cascading animations.
 */
object StaggerCalculator {

    /**
     * Linear stagger: delay(i) = baseDelay * i
     * Simple but effective for short lists
     */
    fun linear(index: Int, baseDelayMs: Int = 50): Int = baseDelayMs * index

    /**
     * Fibonacci-based stagger for organic feel
     * Uses ratio φ = (1 + √5) / 2 ≈ 1.618
     */
    fun fibonacci(index: Int, baseDelayMs: Int = 30): Int {
        val phi = 1.618f
        return (baseDelayMs * phi.pow(index.coerceAtMost(5))).toInt()
    }

    /**
     * Logarithmic stagger - fast start, slowing down
     * delay(i) = baseDelay * ln(i + 1) / ln(2)
     */
    fun logarithmic(index: Int, baseDelayMs: Int = 40): Int {
        if (index == 0) return 0
        return (baseDelayMs * ln((index + 1).toDouble()) / ln(2.0)).toInt()
    }

    /**
     * Quadratic ease stagger - accelerating delays
     * delay(i) = baseDelay * i²
     */
    fun quadratic(index: Int, baseDelayMs: Int = 10): Int = baseDelayMs * index * index

    /**
     * Wave stagger using sine - creates ripple effect
     * delay(i) = baseDelay * |sin(i * π/4)| * i
     */
    fun wave(index: Int, baseDelayMs: Int = 30): Int {
        return (baseDelayMs * abs(sin(index * PI / 4)) * index).toInt()
    }
}

/**
 * Scale animation modifier with spring physics
 */
fun Modifier.animatedScale(
    pressed: Boolean,
    pressedScale: Float = 0.95f,
    spec: SpringSpec<Float> = CogniMotion.quick
): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spec,
        label = "scale"
    )
    this.scale(scale)
}

/**
 * Combined transform animation for cards - scale + slight rotation
 */
@Composable
fun animatedCardTransform(
    pressed: Boolean,
    index: Int = 0
): Pair<Float, Float> {
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = CogniMotion.quick,
        label = "cardScale"
    )

    // Slight rotation based on index for variety - max ±1.5 degrees
    val targetRotation = if (pressed) {
        ((index % 3) - 1) * 0.5f // -0.5, 0, 0.5 degrees
    } else 0f

    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = CogniMotion.bouncy,
        label = "cardRotation"
    )

    return scale to rotation
}

/**
 * Staggered entry animation state
 */
@Composable
fun rememberStaggeredAnimationState(
    itemCount: Int,
    delayCalculator: (Int) -> Int = StaggerCalculator::logarithmic
): List<Animatable<Float, AnimationVector1D>> {
    return remember(itemCount) {
        List(itemCount) { Animatable(0f) }
    }
}

/**
 * Infinite rotation animation with smooth easing
 */
@Composable
fun rememberInfiniteRotation(
    durationMs: Int = 1000,
    easing: Easing = LinearEasing
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = easing),
            repeatMode = RepeatMode.Restart
        ),
        label = "infiniteRotation"
    )
    return rotation
}

/**
 * Pulsing scale animation for loading states
 */
@Composable
fun rememberPulsingScale(
    minScale: Float = 0.95f,
    maxScale: Float = 1.05f,
    durationMs: Int = 800
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = CogniEasing.appleEaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsingScale"
    )
    return scale
}

/**
 * Shimmer offset for loading placeholders
 * Uses linear interpolation with wrap-around
 */
@Composable
fun rememberShimmerOffset(
    durationMs: Int = 1200
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    return offset
}

/**
 * Breathing animation - smooth expansion/contraction
 * Uses sine wave: scale = base + amplitude * sin(2πt/period)
 */
@Composable
fun rememberBreathingScale(
    baseScale: Float = 1f,
    amplitude: Float = 0.03f,
    periodMs: Int = 2000
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathingProgress"
    )
    return baseScale + amplitude * sin(2f * PI.toFloat() * progress)
}

/**
 * 3D card tilt effect based on touch position
 */
data class CardTilt(
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val scale: Float = 1f,
    val elevation: Float = 0f
)

@Composable
fun animateCardTilt(
    pressed: Boolean,
    touchX: Float = 0.5f, // 0-1 normalized
    touchY: Float = 0.5f, // 0-1 normalized
    maxTilt: Float = 5f,
    pressedScale: Float = 0.98f,
    pressedElevation: Float = 8f
): CardTilt {
    // Calculate tilt based on touch position relative to center
    val targetRotationY = if (pressed) (touchX - 0.5f) * 2f * maxTilt else 0f
    val targetRotationX = if (pressed) -(touchY - 0.5f) * 2f * maxTilt else 0f

    val rotationX by animateFloatAsState(
        targetValue = targetRotationX,
        animationSpec = CogniMotion.interactive,
        label = "tiltX"
    )
    val rotationY by animateFloatAsState(
        targetValue = targetRotationY,
        animationSpec = CogniMotion.interactive,
        label = "tiltY"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = CogniMotion.quick,
        label = "tiltScale"
    )
    val elevation by animateFloatAsState(
        targetValue = if (pressed) pressedElevation else 0f,
        animationSpec = CogniMotion.smooth,
        label = "tiltElevation"
    )

    return CardTilt(rotationX, rotationY, scale, elevation)
}

/**
 * Modifier for 3D perspective transform
 */
fun Modifier.cardTilt3D(
    tilt: CardTilt,
    cameraDistance: Float = 12f
): Modifier = graphicsLayer {
    this.cameraDistance = cameraDistance * density
    rotationX = tilt.rotationX
    rotationY = tilt.rotationY
    scaleX = tilt.scale
    scaleY = tilt.scale
    shadowElevation = tilt.elevation
}

/**
 * Shake animation for error states
 * Uses damped harmonic oscillation: x(t) = A * e^(-γt) * cos(ωt)
 */
@Composable
fun animateShake(
    trigger: Boolean,
    amplitude: Float = 10f,
    frequency: Float = 4f, // oscillations
    dampingRatio: Float = 0.5f
): Float {
    var shakeOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(trigger) {
        if (trigger) {
            val animatable = Animatable(amplitude)
            animatable.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = dampingRatio,
                    stiffness = Spring.StiffnessMedium
                )
            ) {
                // Apply oscillation during animation
                val progress = 1f - (value / amplitude)
                val decay = exp(-3f * progress)
                shakeOffset = value * decay * cos(frequency * 2f * PI.toFloat() * progress)
            }
            shakeOffset = 0f
        }
    }

    return shakeOffset
}

/**
 * Counter animation - smoothly interpolates between numbers
 */
@Composable
fun animateIntAsState(
    targetValue: Int,
    animationSpec: AnimationSpec<Float> = tween(300, easing = CogniEasing.appleEaseOut)
): Int {
    val animatedFloat by animateFloatAsState(
        targetValue = targetValue.toFloat(),
        animationSpec = animationSpec,
        label = "counter"
    )
    return animatedFloat.roundToInt()
}

/**
 * Progress arc animation for circular indicators
 * Returns sweep angle in degrees
 */
@Composable
fun animateProgressArc(
    progress: Float, // 0-1
    durationMs: Int = 500
): Float {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMs, easing = CogniEasing.appleEaseOut),
        label = "progressArc"
    )
    return animatedProgress * 360f
}
