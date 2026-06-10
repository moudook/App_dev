package com.example.smarty.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.example.smarty.ui.theme.appleShapes
import com.example.smarty.ui.theme.SmartyBrushes

/**
 * UNIFIED COMPONENT: GEOMETRIC GRADIENT BACKGROUND
 *
 * Centralized background used across Auth, Home, and specialized screens.
 * Replicates the "Scientific Premium" aesthetic with noise and grid.
 */
@Composable
fun GeometricGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Noise bitmap cached for performance
    val noiseBitmap =
        remember {
            val width = 512
            val height = 512
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ALPHA_8)
            val pixels = IntArray(width * height)
            val random = java.util.Random()

            for (i in pixels.indices) {
                val alpha = (random.nextFloat() * 40).toInt()
                pixels[i] = android.graphics.Color.argb(alpha, 255, 255, 255)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.asImageBitmap()
        }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. Precise Gradient (Linear diagonal) - Reference Token
            drawRect(brush = SmartyBrushes.technicalBackground)

            // 2. FILM GRAIN OVERLAY
            val paint =
                android.graphics.Paint().apply {
                    alpha = 80
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY)
                }

            val textureWidth = noiseBitmap.width.toFloat()
            val textureHeight = noiseBitmap.height.toFloat()

            var y = 0f
            while (y < height) {
                var x = 0f
                while (x < width) {
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        noiseBitmap.asAndroidBitmap(),
                        x,
                        y,
                        paint,
                    )
                    x += textureWidth
                }
                y += textureHeight
            }

            // 3. Technical Grid Lines
            val gridPaint =
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    alpha = 20
                    strokeWidth = 2f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }

            val vSpacing = width / 4
            for (i in 1 until 4) {
                drawContext.canvas.nativeCanvas.drawLine(vSpacing * i, 0f, vSpacing * i, height, gridPaint)
            }

            val hSpacing = height / 5
            for (i in 1 until 5) {
                drawContext.canvas.nativeCanvas.drawLine(0f, hSpacing * i, width, hSpacing * i, gridPaint)
            }

            // 4. Large Circles
            val circlePaint =
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    alpha = 30
                    strokeWidth = 2f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }

            val circleRadius = width * 0.4f
            drawContext.canvas.nativeCanvas.drawCircle(width / 2, height * 0.35f, circleRadius, circlePaint)
            drawContext.canvas.nativeCanvas.drawCircle(width / 2, height * 0.65f, circleRadius, circlePaint)
        }

        content()
    }
}

/**
 * UNIFIED COMPONENT: TECHNICAL SURFACE
 *
 * Standard high-contrast semi-transparent surface used in overlays.
 * Managed through ComponentColors and LocalShapes.
 */
@Composable
fun TechnicalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.appleShapes.medium,
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        content = content,
    )
}
