package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.delay

/**
 * Cloud effect overlay that expands from screen edges when shake is triggered.
 * Duration: 0.4 seconds expand/contract as per user preference.
 */
@Composable
fun ShakeCloudEffect(
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    
    // Animation progress: 0f -> 1f -> 0f (expand then contract)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400, // 0.4 seconds as specified
            easing = FastOutSlowInEasing
        ),
        label = "cloudProgress"
    )
    
    // Only render when animation is active
    if (animatedProgress > 0f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // Cloud intensity based on animation progress
            // Peak at 0.5 (middle of animation), then fade
            val intensity = if (animatedProgress <= 0.5f) {
                animatedProgress * 2f // 0 -> 1 during first half
            } else {
                (1f - animatedProgress) * 2f // 1 -> 0 during second half
            }
            
            val cloudAlpha = (intensity * 0.35f).coerceIn(0f, 0.35f)
            val cloudColor = accentColor.copy(alpha = cloudAlpha)
            
            // Draw expanding clouds from all four edges
            val maxSpread = minOf(width, height) * 0.4f * intensity
            
            // Top edge cloud
            drawCloudFromEdge(
                color = cloudColor,
                startY = 0f,
                endY = maxSpread,
                width = width,
                isTopOrLeft = true
            )
            
            // Bottom edge cloud
            drawCloudFromEdge(
                color = cloudColor,
                startY = height,
                endY = height - maxSpread,
                width = width,
                isTopOrLeft = false
            )
            
            // Left edge cloud
            drawCloudFromSide(
                color = cloudColor,
                startX = 0f,
                endX = maxSpread,
                height = height,
                isLeft = true
            )
            
            // Right edge cloud
            drawCloudFromSide(
                color = cloudColor,
                startX = width,
                endX = width - maxSpread,
                height = height,
                isLeft = false
            )
        }
    }
}

private fun DrawScope.drawCloudFromEdge(
    color: Color,
    startY: Float,
    endY: Float,
    width: Float,
    isTopOrLeft: Boolean
) {
    val brush = Brush.verticalGradient(
        colors = if (isTopOrLeft) {
            listOf(color, Color.Transparent)
        } else {
            listOf(Color.Transparent, color)
        },
        startY = if (isTopOrLeft) startY else endY,
        endY = if (isTopOrLeft) endY else startY
    )
    
    drawRect(
        brush = brush,
        topLeft = Offset(0f, minOf(startY, endY)),
        size = androidx.compose.ui.geometry.Size(width, kotlin.math.abs(endY - startY))
    )
}

private fun DrawScope.drawCloudFromSide(
    color: Color,
    startX: Float,
    endX: Float,
    height: Float,
    isLeft: Boolean
) {
    val brush = Brush.horizontalGradient(
        colors = if (isLeft) {
            listOf(color, Color.Transparent)
        } else {
            listOf(Color.Transparent, color)
        },
        startX = if (isLeft) startX else endX,
        endX = if (isLeft) endX else startX
    )
    
    drawRect(
        brush = brush,
        topLeft = Offset(minOf(startX, endX), 0f),
        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(endX - startX), height)
    )
}

/**
 * Red "unread" indicator dot.
 * Shows on the top-right corner of note cards for notes that haven't been viewed.
 * Static (no animation) for a clean, non-distracting look.
 */
@Composable
fun NewNoteIndicatorDot(
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    // Red dot for "Unread" status
    val dotColor = Color(0xFFFF3B30) // Apple-style System Red

    if (isVisible) {
        Canvas(modifier = modifier) {
            drawCircle(
                color = dotColor,
                radius = 5f * density,
                center = center
            )
        }
    }
}

/**
 * Shake gesture tutorial with animated ghost hand grabbing phone.
 * Shows 4 fingers on left side, 1 thumb on right side, with shaking motion.
 * Uses app's accent color for consistent aesthetics.
 */
@Composable
fun ShakeTutorialHand(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    val accentColor = LocalAccentColor.current

    // Shake animation - oscillate left/right rapidly
    val infiniteTransition = rememberInfiniteTransition(label = "shakeTutorial")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeX"
    )

    // Slight rotation during shake for realism
    val rotation by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeRotation"
    )

    // Fade in/out
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 0.6f else 0f,
        animationSpec = tween(300),
        label = "tutorialAlpha"
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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotation
                    }
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
                        size = androidx.compose.ui.geometry.Size(fingerLength, fingerWidth),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(fingerRadius, fingerRadius)
                    )

                    // Fingertip highlight
                    drawCircle(
                        color = ghostColorLight,
                        radius = fingerWidth / 3,
                        center = Offset(fingerX + fingerLength - fingerWidth / 2, fingerY)
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
                    size = androidx.compose.ui.geometry.Size(thumbLength, thumbWidth),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbWidth / 2, thumbWidth / 2)
                )

                // Thumb tip highlight
                drawCircle(
                    color = ghostColorLight,
                    radius = thumbWidth / 3,
                    center = Offset(thumbX + thumbLength - thumbWidth / 2, thumbY)
                )

                // Palm area (connecting fingers on left)
                val palmWidth = 25f * density
                val palmHeight = fingerSpacing * 3.5f
                drawRoundRect(
                    color = ghostColor,
                    topLeft = Offset(
                        centerX - phoneWidth / 2 - palmWidth + 5f * density,
                        fingersStartY - fingerWidth / 2
                    ),
                    size = androidx.compose.ui.geometry.Size(palmWidth, palmHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * density, 10f * density)
                )

                // Phone body (drawn on top of hand)
                drawRoundRect(
                    color = accentColor.copy(alpha = alpha),
                    topLeft = Offset(centerX - phoneWidth / 2, centerY - phoneHeight / 2),
                    size = androidx.compose.ui.geometry.Size(phoneWidth, phoneHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f * density, 14f * density)
                )

                // Phone screen area
                val screenPadding = 6f * density
                drawRoundRect(
                    color = Color.White.copy(alpha = alpha * 0.4f),
                    topLeft = Offset(
                        centerX - phoneWidth / 2 + screenPadding,
                        centerY - phoneHeight / 2 + screenPadding * 2
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        phoneWidth - screenPadding * 2,
                        phoneHeight - screenPadding * 4
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * density, 10f * density)
                )

                // Phone notch/dynamic island (top center)
                val notchWidth = 20f * density
                val notchHeight = 5f * density
                drawRoundRect(
                    color = accentColor.copy(alpha = alpha * 0.7f),
                    topLeft = Offset(
                        centerX - notchWidth / 2,
                        centerY - phoneHeight / 2 + screenPadding * 1.5f
                    ),
                    size = androidx.compose.ui.geometry.Size(notchWidth, notchHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(notchHeight / 2, notchHeight / 2)
                )
            }
        }
    }
}
