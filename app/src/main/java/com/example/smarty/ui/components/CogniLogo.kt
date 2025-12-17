package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.ui.LocalAccentColor

/**
 * Cogni Logo component - renders the hand gesture logo
 * Falls back to a stylized text logo if image not available
 */
@Composable
fun CogniLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    showShimmer: Boolean = false
) {
    val accentColor = LocalAccentColor.current

    // Shimmer animation
    val infiniteTransition = rememberInfiniteTransition(label = "logoShimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center
    ) {
        // Try to load the image
        var imageLoadError by remember { mutableStateOf(false) }

        if (!imageLoadError) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("file:///android_asset/Cogni_Icon.png")
                    .crossfade(true)
                    .build(),
                contentDescription = "Cogni Logo",
                modifier = Modifier.fillMaxSize(),
                onError = { imageLoadError = true }
            )
        }

        // Fallback: Stylized logo representation
        if (imageLoadError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1A1A2E),
                                Color(0xFF16213E)
                            )
                        ),
                        shape = RoundedCornerShape(size / 4)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Draw stylized hand icon
                Canvas(modifier = Modifier.size(size * 0.7f)) {
                    val centerX = this.size.width / 2
                    val centerY = this.size.height / 2
                    val handSize = this.size.minDimension * 0.4f

                    // Glow effect
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.4f),
                                accentColor.copy(alpha = 0f)
                            ),
                            center = Offset(centerX, centerY + handSize * 0.3f),
                            radius = handSize * 1.5f
                        ),
                        radius = handSize * 1.5f,
                        center = Offset(centerX, centerY + handSize * 0.3f)
                    )

                    // Simple hand shape (using paths)
                    val handPath = Path().apply {
                        // Palm base
                        moveTo(centerX - handSize * 0.3f, centerY + handSize * 0.3f)
                        lineTo(centerX + handSize * 0.3f, centerY + handSize * 0.3f)
                        lineTo(centerX + handSize * 0.3f, centerY - handSize * 0.1f)
                        lineTo(centerX - handSize * 0.3f, centerY - handSize * 0.1f)
                        close()
                    }

                    // Draw palm
                    drawPath(
                        path = handPath,
                        color = Color.White.copy(alpha = 0.9f)
                    )

                    // Draw fingers (stylized)
                    val fingerColors = listOf(Color.White.copy(alpha = 0.85f))
                    val fingerPositions = listOf(-0.2f, 0f, 0.2f)

                    fingerPositions.forEachIndexed { index, xOffset ->
                        val fingerHeight = if (index == 1) handSize * 0.5f else handSize * 0.4f
                        drawLine(
                            color = fingerColors[0],
                            start = Offset(centerX + handSize * xOffset, centerY - handSize * 0.1f),
                            end = Offset(centerX + handSize * xOffset, centerY - fingerHeight),
                            strokeWidth = handSize * 0.12f,
                            cap = StrokeCap.Round
                        )
                    }

                    // Thumb
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(centerX - handSize * 0.35f, centerY),
                        end = Offset(centerX - handSize * 0.5f, centerY - handSize * 0.2f),
                        strokeWidth = handSize * 0.1f,
                        cap = StrokeCap.Round
                    )

                    // Pinky (extended for ILY sign)
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(centerX + handSize * 0.35f, centerY),
                        end = Offset(centerX + handSize * 0.5f, centerY - handSize * 0.3f),
                        strokeWidth = handSize * 0.08f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Shimmer overlay if enabled
        if (showShimmer) {
            val shimmerWidth = 200f
            val shimmerStartX = (shimmerOffset * (shimmerWidth * 2)) - shimmerWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            start = Offset(shimmerStartX, 0f),
                            end = Offset(shimmerStartX + shimmerWidth, shimmerWidth)
                        )
                    )
            )
        }
    }
}

/**
 * Compact header with logo + text for main screen
 */
@Composable
fun CogniHeader(
    modifier: Modifier = Modifier,
    showShimmer: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CogniLogo(
            size = 28.dp,
            showShimmer = showShimmer
        )

        Text(
            text = "cogni_",
            style = MaterialTheme.typography.titleLarge,
            color = LocalAccentColor.current
        )
    }
}
