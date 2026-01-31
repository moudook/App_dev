package com.example.smarty.ui.components

import androidx.compose.foundation.border
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.InlineChatImage
import com.example.smarty.ui.theme.Alpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.util.Log

/**
 * Inline image preview component for displaying images in chat messages.
 *
 * Features:
 * - Displays first image with count badge (e.g., "1/4")
 * - Swipe left/right to navigate between images
 * - Tap to expand to full-screen viewer
 * - Shimmer loading state
 * - Error state with retry
 */
@Composable
fun InlineImagePreview(
    images: List<InlineChatImage>,
    onExpandImage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // State for current image index (survives config changes)
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }

    // Swipe gesture state
    val swipeOffset = remember { Animatable(0f) }
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var swipeActivated by remember { mutableStateOf(false) }

    // Calculate swipe threshold in pixels
    val swipeThreshold = with(density) { 60.dp.toPx() }
    val swipeActivationThreshold = with(density) { 15.dp.toPx() }

    // Loading and error state
    var isLoading by remember(currentIndex) { mutableStateOf(true) }
    var hasError by remember(currentIndex) { mutableStateOf(false) }
    var retryCount by remember(currentIndex) { mutableIntStateOf(0) }
    val maxRetries = 3

    // Auto-retry mechanism for transient failures
    LaunchedEffect(hasError, retryCount) {
        if (hasError && retryCount < maxRetries) {
            Log.d("InlineImagePreview", "Image load failed, retry $retryCount/$maxRetries in 1s")
            delay(1000)
            hasError = false
            isLoading = true
            retryCount++
        }
    }

    // Spring animation spec for smooth transitions
    val snapBackSpec = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 400f
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(24.dp)) // Increased to 24dp for Soft Tech
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), // Subtle border
                RoundedCornerShape(24.dp)
            )
            .clickable { onExpandImage(currentIndex) }
            .pointerInput(images.size) {
                if (images.size <= 1) return@pointerInput // No swipe for single image

                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedDrag = 0f
                        swipeActivated = false
                    },
                    onDragEnd = {
                        coroutineScope.launch {
                            if (swipeActivated && abs(swipeOffset.value) > swipeThreshold) {
                                // Determine direction and change index
                                if (swipeOffset.value < 0 && currentIndex < images.lastIndex) {
                                    // Swipe left -> next image
                                    currentIndex++
                                    isLoading = true
                                    hasError = false
                                } else if (swipeOffset.value > 0 && currentIndex > 0) {
                                    // Swipe right -> previous image
                                    currentIndex--
                                    isLoading = true
                                    hasError = false
                                }
                            }
                            // Animate back to center
                            swipeOffset.animateTo(0f, snapBackSpec)
                            swipeActivated = false
                            accumulatedDrag = 0f
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f, snapBackSpec)
                        }
                        swipeActivated = false
                        accumulatedDrag = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulatedDrag += dragAmount

                        // Activate swipe after threshold
                        if (!swipeActivated && abs(accumulatedDrag) > swipeActivationThreshold) {
                            swipeActivated = true
                        }

                        // Update offset while swiping
                        if (swipeActivated) {
                            coroutineScope.launch {
                                // Limit the drag at edges
                                val limitedDrag = when {
                                    currentIndex == 0 && accumulatedDrag > 0 ->
                                        accumulatedDrag * 0.3f // Resistance at start
                                    currentIndex == images.lastIndex && accumulatedDrag < 0 ->
                                        accumulatedDrag * 0.3f // Resistance at end
                                    else -> accumulatedDrag
                                }
                                swipeOffset.snapTo(limitedDrag)
                            }
                        }
                    }
                )
            }
    ) {
        val currentImage = images.getOrNull(currentIndex) ?: images.first()

        // Shimmer loading placeholder
        if (isLoading && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .skeletonShimmer()
            )
        }

        // Error state
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        hasError = false
                        isLoading = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = stringResource(R.string.image_load_failed),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        // Image with swipe offset
        // FIX: Improved cache key uniqueness and error handling
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(currentImage.uri)
                .crossfade(200)
                // FIX: Added retry key to force reload on retry
                .memoryCacheKey("preview_${currentImage.uri}_r$retryCount")
                .diskCacheKey("disk_preview_${currentImage.uri}")
                // Enable disk caching for persistence across sessions
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .build(),
            contentDescription = currentImage.fileName,
            contentScale = ContentScale.Crop,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> {
                        isLoading = true
                        hasError = false
                    }
                    is AsyncImagePainter.State.Success -> {
                        isLoading = false
                        hasError = false
                        retryCount = 0  // Reset retry count on success
                        Log.d("InlineImagePreview", "Image loaded successfully: ${currentImage.uri.take(50)}")
                    }
                    is AsyncImagePainter.State.Error -> {
                        isLoading = false
                        hasError = true
                        Log.w("InlineImagePreview", "Image load error for: ${currentImage.uri.take(50)}, retry=$retryCount")
                    }
                    else -> {}
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = swipeOffset.value
                    // Fade slightly when swiping
                    alpha = 1f - (abs(swipeOffset.value) / size.width * 0.3f).coerceIn(0f, 0.3f)
                }
        )

        // Image count badge (top-right)
        if (images.size > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = stringResource(R.string.pagination_format, currentIndex + 1, images.size),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Note title badge (bottom-left)
        if (currentImage.noteTitle.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = currentImage.noteTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        // Swipe indicators (dots at bottom center)
        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                images.forEachIndexed { index, _ ->
                    val isActive = index == currentIndex
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) Color.White
                                else Color.White.copy(alpha = 0.5f)
                            )
                    )
                }
            }
        }
    }
}
