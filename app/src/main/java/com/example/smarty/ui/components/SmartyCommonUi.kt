package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ElectricBlue

/**
 * Standard Primary Button for Smarty App
 */
@Composable
fun SmartyButton(
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
                modifier = Modifier.size(24.dp),
                color = contentColor,
                strokeWidth = 2.dp
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
 * Standard Outlined Text Field for Smarty App
 */
@Composable
fun SmartyOutlinedTextField(
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
fun SmartyGoogleButton(
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
                modifier = Modifier.size(24.dp),
                color = LocalAccentColor.current,
                strokeWidth = 2.dp
            )
        } else {
            // In a real app, use the Google G logo drawable
            Text(
                text = stringResource(R.string.continue_with_google),
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
            // Updated to Monochrome Grayscale for the new aesthetic
            val gradientBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8E8E93), // Top-Left: System Gray
                    Color(0xFFC7C7CC), // Middle: System Gray 3
                    Color(0xFF000000)  // Bottom-Right: True Black
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        shape = shape,
        // Dark overlay in light mode, deep dark in dark mode
        color = if (isDark) Color(0xFF050E1E).copy(alpha = 0.8f) else Color(0xFF1A1C1E).copy(alpha = 0.7f),
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

// -----------------------------------------------------------------------------
// SKELETON LOADING COMPONENTS
// -----------------------------------------------------------------------------

/**
 * Shimmer effect modifier for skeleton loading.
 * Animates a gradient sweep across the component.
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ),
        label = "shimmer"
    )

    background(
        brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surface, // Lighter in middle
                MaterialTheme.colorScheme.surfaceVariant,
            ),
            start = androidx.compose.ui.geometry.Offset(startOffsetX, 0f),
            end = androidx.compose.ui.geometry.Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
        )
    ).onGloballyPositioned {
        size = it.size
    }
}

/**
 * Generic Skeleton List.
 */
@Composable
fun SkeletonList(
    count: Int = 3,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            skeleton()
        }
    }
}

/**
 * Skeleton for Calendar Event.
 */
@Composable
fun EventCardSkeleton(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
            }
        }
    }
}

/**
 * Loading state for Calendar.
 */
@Composable
fun CalendarLoadingState(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) {
             EventCardSkeleton(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * Skeleton placeholder for a ChatMessage while loading history.
 */
@Composable
fun ChatMessageSkeleton(isFromUser: Boolean = false) {
    val alignment = if (isFromUser) Alignment.End else Alignment.Start
    val bubbleShape = if (isFromUser) RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
         Surface(
            shape = bubbleShape,
            color = if (isFromUser) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent,
            modifier = Modifier.widthIn(min = 100.dp, max = 280.dp)
         ) {
             Column(modifier = Modifier.padding(16.dp)) {
                 Box(modifier = Modifier.width(180.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                 Spacer(modifier = Modifier.height(8.dp))
                 Box(modifier = Modifier.width(120.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
             }
         }
    }
}

/**
 * Empty state for Stacks screen.
 */
// Removed StacksEmptyState as it is already defined in ChatEmptyState.kt

/**
 * Empty state for Chat History screen.
 */
// Removed ChatHistoryEmptyState as it is already defined in ChatEmptyState.kt
