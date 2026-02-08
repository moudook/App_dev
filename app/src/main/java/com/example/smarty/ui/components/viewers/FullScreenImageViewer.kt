package com.example.smarty.ui.components.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.smarty.ui.components.OrganicThinkingIndicator
import com.example.smarty.R

/**
 * Full-screen image viewer with pinch-to-zoom and pan gestures.
 *
 * OPTIMIZATION: This composable is completely dormant until shown.
 * - No state allocations until Dialog is opened
 * - No animations running in background
 * - No remember{} blocks evaluated until composed
 * - Immediate cleanup on dismiss
 *
 * Features:
 * - Pinch to zoom (1x to 5x)
 * - Double-tap to toggle zoom
 * - Pan when zoomed
 * - Tap to dismiss
 */
@Composable
fun FullScreenImageViewer(
    imageUri: String,
    onDismiss: () -> Unit,
    contentDescription: String? = null
) {
    // Dialog only renders when this composable is in the tree
    // All state inside is created fresh each time
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        ImageViewerContent(
            imageUri = imageUri,
            contentDescription = contentDescription,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ImageViewerContent(
    imageUri: String,
    contentDescription: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // State only exists while viewer is open - uses rememberSaveable for config changes only
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var containerSize by mutableStateOf(IntSize.Zero)

    // Constants - no allocations
    val minScale = 1f
    val maxScale = 5f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        // Image with gestures - no animations, direct state updates
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUri)
                .crossfade(200)
                .build(),
            loading = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OrganicThinkingIndicator(baseColor = Color.White)
                }
            },
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Calculate new scale
                        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                        scale = newScale

                        // Calculate pan boundaries
                        val maxX = (containerSize.width * (newScale - 1) / 2f).coerceAtLeast(0f)
                        val maxY = (containerSize.height * (newScale - 1) / 2f).coerceAtLeast(0f)

                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)

                        // Reset when fully zoomed out
                        if (newScale <= minScale) {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Toggle zoom without animation
                            if (scale > 1.1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = {
                            if (scale <= 1.1f) {
                                onDismiss()
                            }
                        }
                    )
                }
        )

        // Close button - static, no animations
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
        }

        // Zoom indicator - only rendered when zoomed, no animations
        if (scale > 1.1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.scale_factor, scale),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
