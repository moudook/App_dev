package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ElectricBlue

/**
 * Standard Primary Button for Jarvis App
 */
@Composable
fun JarvisButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    containerColor: Color = LocalAccentColor.current,
    contentColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp), // Standard height
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(16.dp), // Consistent rounding
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.7f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 2.dp
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp).fillMaxWidth(), // Constrained height
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * Standard Outlined Text Field for Jarvis App
 */
@Composable
fun JarvisOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    singleLine: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Animate border color on focus/error
    val borderColor = if (isError) MaterialTheme.colorScheme.error else LocalAccentColor.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(label) },
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = borderColor,
                errorBorderColor = MaterialTheme.colorScheme.error,
                errorLabelColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            interactionSource = interactionSource
        )
        
        if (!errorMessage.isNullOrBlank() && isError) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Google Sign-In Button
 * (Simplified placeholder for visual consistency)
 */
@Composable
fun JarvisGoogleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        enabled = !isLoading
    ) {
        if (isLoading) {
             CircularProgressIndicator(
                modifier = Modifier.height(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            // In a real app, use the Google G logo drawable
            Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * GEOMETRIC GRADIENT BACKGROUND WITH SHOW-ACCURATE GRAIN
 * 
 * Replicates the reference art:
 * - Specific Gradient: Silver-Blue (Top) -> Royal Blue (Center) -> Deep Navy (Bottom)
 * - Film Grain/Noise Texture overlay for the "Scientific" feel
 * - Precise Grid and Circle overlays
 */
@Composable
fun GeometricGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    // Generate noise bitmap once to avoid re-allocation
    val density = androidx.compose.ui.platform.LocalDensity.current
    val noiseBitmap = remember {
        val width = 512
        val height = 512
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ALPHA_8)
        val pixels = IntArray(width * height)
        val random = java.util.Random()
        
        for (i in pixels.indices) {
            // Generate random alpha for noise (0-255)
            // We want subtle noise, so we map random to a localized alpha
            val alpha = (random.nextFloat() * 40).toInt() // 0-40 alpha
            pixels[i] = android.graphics.Color.argb(alpha, 255, 255, 255)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.asImageBitmap()
    }

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            
            // 1. Precise Gradient (Linear diagonal)
            // Matching the provided image: Lighter top-left, Deep blue diagonal, Dark bottom
            val gradientBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8E9EBC), // Top-Left: Silvery Blue (Reference match)
                    Color(0xFF1E488F), // Middle: Vibrant Royal Blue
                    Color(0xFF0A1A3F)  // Bottom-Right: Deep Navy
                ),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(width, height)
            )
            drawRect(brush = gradientBrush)
            
            // 2. FILM GRAIN OVERLAY (Tiled)
            // This adds the "texture" the user requested
            val paint = android.graphics.Paint().apply {
                alpha = 80 // Adjust intensity of noise
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
                        x, y, paint
                    )
                    x += textureWidth
                }
                y += textureHeight
            }

            // 3. Technical Grid Lines
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                alpha = 20 // Very faint 
                strokeWidth = 2f // Hairline
                style = android.graphics.Paint.Style.STROKE
                isAntiAlias = true
            }
            
            // 4 vertical lines (approx spacing based on image)
            val vSpacing = width / 4
            for (i in 1 until 4) {
                 drawContext.canvas.nativeCanvas.drawLine(vSpacing * i, 0f, vSpacing * i, height, gridPaint)
            }
            
             // 5 horizontal lines
            val hSpacing = height / 5
            for (i in 1 until 5) {
                 drawContext.canvas.nativeCanvas.drawLine(0f, hSpacing * i, width, hSpacing * i, gridPaint)
            }
            
            // 4. Large Circles
            val circlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                alpha = 30 // Slightly more visible than grid
                strokeWidth = 2f
                style = android.graphics.Paint.Style.STROKE
                isAntiAlias = true
            }
            
            val circleRadius = width * 0.4f
            
            // Top Circle
             drawContext.canvas.nativeCanvas.drawCircle(
                width / 2,
                height * 0.35f,
                circleRadius,
                circlePaint
            )

            // Bottom Circle (Intersecting)
             drawContext.canvas.nativeCanvas.drawCircle(
                width / 2,
                height * 0.65f,
                circleRadius,
                circlePaint
            )
        }
        
        content()
    }
}

/**
 * TECHNICAL SURFACE (Replaces GlassySurface)
 * 
 * - No Blur (High Visibility)
 * - Dark Semi-transparent background for contrast 
 * - Sharp, technical feel
 */
@Composable
fun TechnicalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        // Dark overlay to ensure white text pops against any background
        color = Color(0xFF050E1E).copy(alpha = 0.6f), 
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        content = content
    )
}

// Deprecated GlassySurface - redirecting to TechnicalSurface for now to maintain ABI if used elsewhere
@Composable
fun GlassySurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit
) {
    TechnicalSurface(modifier, shape, content)
}
