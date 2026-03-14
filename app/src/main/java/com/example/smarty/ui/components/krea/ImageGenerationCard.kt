package com.example.smarty.ui.components.krea

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.delay

enum class ImageGenState {
    Thinking, Completed, Error
}

enum class ImageGenMode {
    Agent, Direct
}

/**
 * A highly polished, isolated UI component for displaying Krea AI image generations.
 * Required by Blueprint Level 5: Jetpack Compose UX & The Dual UI.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageGenerationCard(
    state: ImageGenState,
    mode: ImageGenMode,
    imageUrl: String?,
    prompt: String?,
    onRemix: () -> Unit = {},
    onRetry: () -> Unit = {},
    onExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dominantColor by remember { mutableStateOf<Color?>(null) }
    
    // Parallax logic placeholder (will implement ScrollState observing if necessary)
    val parallaxOffset = 0f 
    
    // Container animation
    val transition = updateTransition(targetState = state, label = "ImageGenState")
    
    val shadowElevation by animateDpAsState(
        targetValue = if (state == ImageGenState.Completed && dominantColor != null) 16.dp else 4.dp,
        label = "shadowAnim"
    )
    
    val cardBackground by animateColorAsState(
        targetValue = dominantColor?.copy(alpha = 0.1f) ?: MaterialTheme.colorScheme.surfaceVariant,
        label = "bgAnim"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp)
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(24.dp),
                ambientColor = dominantColor ?: Color.Black,
                spotColor = dominantColor ?: Color.Black
            )
            .clip(RoundedCornerShape(24.dp)), // Blueprint constraint: Fluid geometry, avoiding harsh 90 degree edges
        color = cardBackground,
        border = BorderStroke(1.dp, dominantColor?.copy(alpha = 0.3f) ?: MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            
            transition.AnimatedContent { targetState ->
                when (targetState) {
                    ImageGenState.Thinking -> {
                        ThinkingStateInner(mode)
                    }
                    ImageGenState.Completed -> {
                        CompletedStateInner(
                            imageUrl = imageUrl ?: "",
                            parallaxOffset = parallaxOffset,
                            onColorExtracted = { dominantColor = it },
                            onExpand = onExpand,
                            onRemix = onRemix
                        )
                    }
                    ImageGenState.Error -> {
                        ErrorStateInner(onRetry)
                    }
                }
            }
            
            // Appears slightly beneath the completed image if a prompt exists
            if (state == ImageGenState.Completed && !prompt.isNullOrBlank()) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingStateInner(mode: ImageGenMode) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val translation by infiniteTransition.animateFloat(
        initialValue = -500f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslation"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .drawWithContent {
                drawContent()
                // Shimmer sweep
                val brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.0f),
                        Color.White.copy(alpha = 0.2f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    start = androidx.compose.ui.geometry.Offset(translation, 0f),
                    end = androidx.compose.ui.geometry.Offset(translation + 200f, 200f)
                )
                drawRect(brush)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            val text = if (mode == ImageGenMode.Agent) "Agent is sketching..." else "Krea is generating..."
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompletedStateInner(
    imageUrl: String,
    parallaxOffset: Float,
    onColorExtracted: (Color) -> Unit,
    onExpand: () -> Unit,
    onRemix: () -> Unit
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 500.dp)
            .combinedClickable(
                onClick = onExpand,
                onLongClick = onRemix
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(800) // Cinematic fade-in constraint
                .allowHardware(false) // Required for Palette extraction
                .build(),
            contentDescription = "AI Generated Artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = parallaxOffset
                },
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    val bitmap = (state.result as? SuccessResult)?.drawable?.toBitmap()
                    bitmap?.let {
                        Palette.from(it).generate { palette ->
                            val color = palette?.dominantSwatch?.rgb?.let { Color(it) } 
                                ?: palette?.vibrantSwatch?.rgb?.let { Color(it) }
                            color?.let { onColorExtracted(it) }
                        }
                    }
                }
            }
        )
        
        // Remix overlay button
        IconButton(
            onClick = onRemix,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Remix this image",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ErrorStateInner(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Prompt rejected or generation failed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text("Try again", color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
