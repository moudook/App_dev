package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import kotlinx.coroutines.delay

/**
 * Red "unread" indicator dot.
 * Shows on the top-right corner of note cards for notes that haven't been viewed.
 * Static (no animation) for a clean, non-distracting look.
 */
@Composable
fun NewNoteIndicatorDot(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    // Using theme error color for consistency with "Calm" aesthetic
    val dotColor = MaterialTheme.colorScheme.error

    if (isVisible) {
        Canvas(modifier = modifier) {
            drawCircle(
                color = dotColor,
                radius = 5f * density,
                center = center,
            )
        }
    }
}

/**
 * OPTIMIZED: Shake gesture tutorial with animated ghost hand grabbing phone.
 * Shows 4 fingers on left side, 1 thumb on right side, with shaking motion.
 *
 * Performance improvements:
 * - Single infinite transition instead of two (50% less animation overhead)
 * - Rotation derived mathematically from offset progress
 * - graphicsLayer lambda for GPU-accelerated transforms
 */
@Composable
fun ShakeTutorialHand(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val accentColor = LocalAccentColor.current

    // OPTIMIZED: Single animation drives both offset and rotation - LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING && isVisible

    val shakeProgress =
        if (shouldAnimate) {
            val infiniteTransition = rememberInfiniteTransition(label = "shakeTutorial")
            val animatedProgress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(100, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "shakeProgress",
            )
            animatedProgress
        } else {
            0.5f // Static mid-point value
        }

    // OPTIMIZED: Derive both values from single progress
    // offsetX: -15 to +15 (linear interpolation)
    // rotation: -3 to +3 (same phase as offset for natural look)
    val offsetX = -15f + shakeProgress * 30f
    val rotation = -3f + shakeProgress * 6f

    // Fade in/out
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 0.6f else 0f,
        animationSpec = tween(300),
        label = "tutorialAlpha",
    )

    // Auto-dismiss after a few shakes
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(3000) // Show for 3 seconds
            onDismiss()
        }
    }

    if (alpha > 0f) {
        Box(modifier = modifier) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = rotation
                        },
            ) {
                val centerX = size.width / 2 + offsetX * density
                val centerY = size.height / 2

                // Phone dimensions
                val phoneWidth = 70f * density
                val phoneHeight = 130f * density

                // Finger dimensions
                val fingerWidth = 12f * density
                val fingerLength = 35f * density
                val fingerSpacing = 16f * density
                val fingerRadius = 6f * density

                // Ghost hand color (accent with transparency)
                val ghostColor = accentColor.copy(alpha = alpha * 0.8f)
                val ghostColorLight = accentColor.copy(alpha = alpha * 0.5f)

                // Draw 4 fingers on LEFT side of phone
                val fingersStartY = centerY - (fingerSpacing * 1.5f)
                for (i in 0 until 4) {
                    val fingerY = fingersStartY + (i * fingerSpacing)
                    val fingerX = centerX - phoneWidth / 2 - fingerLength + 8f * density

                    // Finger body (rounded rectangle)
                    drawRoundRect(
                        color = ghostColor,
                        topLeft = Offset(fingerX, fingerY - fingerWidth / 2),
                        size =
                            androidx.compose.ui.geometry
                                .Size(fingerLength, fingerWidth),
                        cornerRadius =
                            androidx.compose.ui.geometry
                                .CornerRadius(fingerRadius, fingerRadius),
                    )

                    // Fingertip highlight
                    drawCircle(
                        color = ghostColorLight,
                        radius = fingerWidth / 3,
                        center = Offset(fingerX + fingerLength - fingerWidth / 2, fingerY),
                    )
                }

                // Draw THUMB on RIGHT side of phone
                val thumbWidth = 14f * density
                val thumbLength = 40f * density
                val thumbX = centerX + phoneWidth / 2 - 8f * density
                val thumbY = centerY

                // Thumb body (angled slightly)
                drawRoundRect(
                    color = ghostColor,
                    topLeft = Offset(thumbX, thumbY - thumbWidth / 2),
                    size =
                        androidx.compose.ui.geometry
                            .Size(thumbLength, thumbWidth),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(thumbWidth / 2, thumbWidth / 2),
                )

                // Thumb tip highlight
                drawCircle(
                    color = ghostColorLight,
                    radius = thumbWidth / 3,
                    center = Offset(thumbX + thumbLength - thumbWidth / 2, thumbY),
                )

                // Palm area (connecting fingers on left)
                val palmWidth = 25f * density
                val palmHeight = fingerSpacing * 3.5f
                drawRoundRect(
                    color = ghostColor,
                    topLeft =
                        Offset(
                            centerX - phoneWidth / 2 - palmWidth + 5f * density,
                            fingersStartY - fingerWidth / 2,
                        ),
                    size =
                        androidx.compose.ui.geometry
                            .Size(palmWidth, palmHeight),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(10f * density, 10f * density),
                )

                // Phone body (drawn on top of hand)
                drawRoundRect(
                    color = accentColor.copy(alpha = alpha),
                    topLeft = Offset(centerX - phoneWidth / 2, centerY - phoneHeight / 2),
                    size =
                        androidx.compose.ui.geometry
                            .Size(phoneWidth, phoneHeight),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(14f * density, 14f * density),
                )

                // Phone screen area
                val screenPadding = 6f * density
                drawRoundRect(
                    color = Color.White.copy(alpha = alpha * 0.4f),
                    topLeft =
                        Offset(
                            centerX - phoneWidth / 2 + screenPadding,
                            centerY - phoneHeight / 2 + screenPadding * 2,
                        ),
                    size =
                        androidx.compose.ui.geometry.Size(
                            phoneWidth - screenPadding * 2,
                            phoneHeight - screenPadding * 4,
                        ),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(10f * density, 10f * density),
                )

                // Phone notch/dynamic island (top center)
                val notchWidth = 20f * density
                val notchHeight = 5f * density
                drawRoundRect(
                    color = accentColor.copy(alpha = alpha * 0.7f),
                    topLeft =
                        Offset(
                            centerX - notchWidth / 2,
                            centerY - phoneHeight / 2 + screenPadding * 1.5f,
                        ),
                    size =
                        androidx.compose.ui.geometry
                            .Size(notchWidth, notchHeight),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(notchHeight / 2, notchHeight / 2),
                )
            }
        }
    }
}
