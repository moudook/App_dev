package com.example.smarty.features.games.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import com.example.smarty.features.games.ui.CoinTossConstants.APEX_DRIFT
import com.example.smarty.features.games.ui.CoinTossConstants.BOUNCE_DOWN_MS
import com.example.smarty.features.games.ui.CoinTossConstants.BOUNCE_HEIGHT
import com.example.smarty.features.games.ui.CoinTossConstants.BOUNCE_UP_MS
import com.example.smarty.features.games.ui.CoinTossConstants.CLOSE_CAMERA_DIST
import com.example.smarty.features.games.ui.CoinTossConstants.DEFAULT_CAMERA_DIST
import com.example.smarty.features.games.ui.CoinTossConstants.DEFAULT_ZOOM
import com.example.smarty.features.games.ui.CoinTossConstants.FALL_TIME
import com.example.smarty.features.games.ui.CoinTossConstants.LAUNCH_HEIGHT
import com.example.smarty.features.games.ui.CoinTossConstants.LAUNCH_TILT_X
import com.example.smarty.features.games.ui.CoinTossConstants.LAUNCH_TIME
import com.example.smarty.features.games.ui.CoinTossConstants.LAUNCH_ZOOM
import com.example.smarty.features.games.ui.CoinTossConstants.MIN_SPINS
import com.example.smarty.features.games.ui.CoinTossConstants.PEAK_TILT_X
import com.example.smarty.features.games.ui.CoinTossConstants.PEAK_TIME
import com.example.smarty.features.games.ui.CoinTossConstants.PEAK_ZOOM
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Bundles the 6 animatable properties that drive the 3D coin flip.
 * Created once in the composable via [rememberCoinAnimationState] and
 * passed to [performToss] — no more long parameter lists.
 */
data class CoinAnimationState(
    val rotationY: Animatable<Float, AnimationVector1D> = Animatable(0f),
    val rotationX: Animatable<Float, AnimationVector1D> = Animatable(0f),
    val translationY: Animatable<Float, AnimationVector1D> = Animatable(0f),
    val shadowScale: Animatable<Float, AnimationVector1D> = Animatable(1f),
    val zoomScale: Animatable<Float, AnimationVector1D> = Animatable(1f),
    val cameraDist: Animatable<Float, AnimationVector1D> = Animatable(DEFAULT_CAMERA_DIST),
)

/**
 * Orchestrates the full cinematic coin toss sequence.
 *
 * This is the single entry point for triggering a toss —
 * no more duplicated callback blocks across the composable.
 *
 * @param state       The bundled animatable state.
 * @param onResult    Called with `true` for heads, `false` for tails once the result is determined.
 * @param onLand      Called the instant the coin "touches down" (ideal for haptics + showing result).
 * @param onStart     Called when the toss begins (set isTossing = true, hide old result).
 * @param onEnd       Called after the landing bounce completes (set isTossing = false).
 */
suspend fun performToss(
    state: CoinAnimationState,
    onResult: (isHeads: Boolean) -> Unit,
    onLand: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) = coroutineScope {
    onStart()

    val isHeads = Random.nextBoolean()
    onResult(isHeads)

    // Calculate target rotation so the correct face lands up
    val currentRot = state.rotationX.value
    val targetMod = if (isHeads) 0f else 180f
    var diff = targetMod - (currentRot % 360f)
    if (diff <= 0f) diff += 360f
    val targetRotation = currentRot + (MIN_SPINS * 360f) + diff

    // ── 1. Rotation X — the flip itself (horizontal axis) ────────
    launch {
        state.rotationX.animateTo(
            currentRot + (targetRotation - currentRot) * 0.45f,
            tween(LAUNCH_TIME, easing = LinearOutSlowInEasing),
        )
        state.rotationX.animateTo(
            currentRot + (targetRotation - currentRot) * 0.55f,
            tween(PEAK_TIME, easing = LinearEasing),
        )
        state.rotationX.animateTo(
            targetRotation,
            tween(FALL_TIME, easing = FastOutLinearInEasing),
        )
    }

    // ── 2. Rotation Y — cinematic 3D tilt ────────────────────────
    launch {
        state.rotationY.animateTo(LAUNCH_TILT_X, tween(LAUNCH_TIME, easing = LinearOutSlowInEasing))
        state.rotationY.animateTo(PEAK_TILT_X, tween(PEAK_TIME, easing = LinearEasing))
        state.rotationY.animateTo(0f, tween(FALL_TIME, easing = FastOutLinearInEasing))
    }

    // ── 3. Zoom — camera push-in ─────────────────────────────────
    launch {
        state.zoomScale.animateTo(LAUNCH_ZOOM, tween(LAUNCH_TIME, easing = FastOutSlowInEasing))
        state.zoomScale.animateTo(PEAK_ZOOM, tween(PEAK_TIME, easing = LinearEasing))
        state.zoomScale.animateTo(DEFAULT_ZOOM, tween(FALL_TIME, easing = FastOutLinearInEasing))
    }

    // ── 4. Camera Distance — perspective shift ───────────────────
    launch {
        state.cameraDist.animateTo(CLOSE_CAMERA_DIST, tween(LAUNCH_TIME))
        state.cameraDist.animateTo(DEFAULT_CAMERA_DIST, tween(PEAK_TIME + FALL_TIME))
    }

    // ── 5. Shadow Scale ──────────────────────────────────────────
    launch {
        state.shadowScale.animateTo(0.2f, tween(LAUNCH_TIME))
        state.shadowScale.animateTo(0.1f, tween(PEAK_TIME))
        state.shadowScale.animateTo(1.0f, tween(FALL_TIME))
    }

    // ── 6. Translation Y — trajectory + landing bounce ───────────
    launch {
        state.translationY.animateTo(LAUNCH_HEIGHT, tween(LAUNCH_TIME, easing = LinearOutSlowInEasing))
        state.translationY.animateTo(APEX_DRIFT, tween(PEAK_TIME / 2, easing = LinearEasing))
        state.translationY.animateTo(LAUNCH_HEIGHT, tween(PEAK_TIME / 2, easing = LinearEasing))
        state.translationY.animateTo(0f, tween(FALL_TIME, easing = FastOutLinearInEasing))

        onLand()

        // Subtle heavy bounce
        state.translationY.animateTo(BOUNCE_HEIGHT, tween(BOUNCE_UP_MS, easing = FastOutSlowInEasing))
        state.translationY.animateTo(0f, tween(BOUNCE_DOWN_MS, easing = LinearOutSlowInEasing))

        onEnd()
    }
}
