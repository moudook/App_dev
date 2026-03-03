package com.example.smarty.features.games.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.features.games.ui.CoinTossConstants.COIN_FALLBACK_COLOR
import com.example.smarty.features.games.ui.CoinTossConstants.COIN_SIZE
import com.example.smarty.features.games.ui.CoinTossConstants.COIN_SPOT_SHADOW
import com.example.smarty.features.games.ui.CoinTossConstants.EDGE_GRADIENT_COLORS
import com.example.smarty.features.games.ui.CoinTossConstants.EDGE_SLICE_COUNT
import com.example.smarty.features.games.ui.CoinTossConstants.FLOOR_SHADOW_HEIGHT
import com.example.smarty.features.games.ui.CoinTossConstants.FLOOR_SHADOW_OFFSET_Y
import com.example.smarty.features.games.ui.CoinTossConstants.FLOOR_SHADOW_WIDTH
import com.example.smarty.features.games.ui.CoinTossConstants.HEADS_IMAGE_URL
import com.example.smarty.features.games.ui.CoinTossConstants.TAILS_IMAGE_URL
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════
//  Reusable, public composables for the Coin Toss feature.
//  Each piece can be tested, previewed, or reused independently.
// ═══════════════════════════════════════════════════════════════════

/**
 * The full 3D coin — edge cylinder + heads/tails face.
 * Reads animation values from [state] to position itself.
 */
@Composable
fun Coin3D(
    state: CoinAnimationState,
    isTossing: Boolean
) {
    val isBackVisible = (abs(state.rotationY.value) % 360) in 90f..270f
    val edgeGradient = Brush.linearGradient(EDGE_GRADIENT_COLORS)

    Box(contentAlignment = Alignment.Center) {
        // Edge — stacked slices for a 3D cylinder effect
        CoinEdge(state = state, edgeGradient = edgeGradient)

        // Face
        if (!isBackVisible) {
            CoinFace(
                state = state,
                imageUrl = HEADS_IMAGE_URL,
                description = "Heads",
                isTossing = isTossing,
                translationXOffset = (EDGE_SLICE_COUNT / 2f + 0.5f)
            )
        } else {
            CoinFace(
                state = state,
                imageUrl = TAILS_IMAGE_URL,
                description = "Tails",
                isTossing = isTossing,
                translationXOffset = -(EDGE_SLICE_COUNT / 2f + 0.5f),
                mirrorContent = true
            )
        }
    }
}

/**
 * Renders the coin's 3D edge as multiple thin slices stacked in X-space.
 */
@Composable
fun CoinEdge(
    state: CoinAnimationState,
    edgeGradient: Brush
) {
    for (i in 0 until EDGE_SLICE_COUNT) {
        Box(
            modifier = Modifier
                .size(COIN_SIZE)
                .graphicsLayer {
                    rotationX = state.rotationX.value
                    rotationY = state.rotationY.value
                    translationY = state.translationY.value
                    translationX = (i - EDGE_SLICE_COUNT / 2f) * 1.5f * density
                    scaleX = state.zoomScale.value
                    scaleY = state.zoomScale.value
                    cameraDistance = state.cameraDist.value * density
                }
                .clip(CircleShape)
                .background(edgeGradient)
        )
    }
}

/**
 * A single coin face (heads or tails) with the 3D transform applied.
 *
 * @param mirrorContent  If true, the image is flipped 180° on Y so the tails
 *                       image reads correctly when the coin's back is showing.
 */
@Composable
fun CoinFace(
    state: CoinAnimationState,
    imageUrl: String,
    description: String,
    isTossing: Boolean,
    translationXOffset: Float,
    mirrorContent: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(COIN_SIZE)
            .graphicsLayer {
                rotationX = state.rotationX.value
                rotationY = state.rotationY.value
                translationY = state.translationY.value
                translationX = translationXOffset * density
                scaleX = state.zoomScale.value
                scaleY = state.zoomScale.value
                cameraDistance = state.cameraDist.value * density
            }
            .shadow(
                elevation = if (isTossing) 16.dp else 8.dp,
                shape = CircleShape,
                spotColor = COIN_SPOT_SHADOW.copy(alpha = 0.8f)
            )
            .clip(CircleShape)
            .background(COIN_FALLBACK_COLOR),
        contentAlignment = Alignment.Center
    ) {
        val imageModifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)

        if (mirrorContent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
            ) {
                CoinImage(url = imageUrl, description = description, modifier = imageModifier)
            }
        } else {
            CoinImage(url = imageUrl, description = description, modifier = imageModifier)
        }
    }
}

/**
 * Loads and displays a coin-face image from a URL using Coil.
 */
@Composable
fun CoinImage(
    url: String,
    description: String,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/**
 * The shadow ellipse that sits on the "floor" beneath the coin.
 */
@Composable
fun CoinFloorShadow(shadowScale: Float) {
    Box(
        modifier = Modifier
            .offset(y = FLOOR_SHADOW_OFFSET_Y)
            .size(
                width = FLOOR_SHADOW_WIDTH * shadowScale,
                height = FLOOR_SHADOW_HEIGHT * shadowScale
            )
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.3f * shadowScale),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            )
    )
}

/**
 * The animated philosophical quote shown while a toss is in progress.
 */
@Composable
fun TossQuoteOverlay(
    isTossing: Boolean,
    showResult: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = isTossing,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(800))
        ) {
            Text(
                text = "\"Choose what you wish,\nnot the outcome.\"",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 40.sp,
                    letterSpacing = 1.5.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
        }

        if (!isTossing && !showResult) {
            Text(
                text = "Tap anywhere to toss",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Animated "HEADS" / "TAILS" result label shown after the coin lands.
 */
@Composable
fun ResultDisplay(
    visible: Boolean,
    resultText: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Text(
            text = resultText,
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                fontFamily = FontFamily.Serif
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * Circular close button with a semi-transparent background.
 */
@Composable
fun CoinTossCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                CircleShape
            )
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
