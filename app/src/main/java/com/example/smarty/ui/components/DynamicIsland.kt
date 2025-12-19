package com.example.smarty.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface DynamicIslandState {
    object Contracted : DynamicIslandState
    object Processing : DynamicIslandState
    data class Info(val label: String, val secondaryLabel: String, val icon: ImageVector? = null) : DynamicIslandState
    data class Listening(val rmsDb: Float) : DynamicIslandState
    object Success : DynamicIslandState // New state for completion feedback
}

@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: DynamicIslandState = DynamicIslandState.Contracted
) {
    // ============================================================
    // SEQUENCED ANIMATION USING ANIMATABLE
    // Expansion: Width first (300ms) → Height second (300ms)
    // Contraction: Height first (300ms) → Width second (300ms)
    // ============================================================
    
    // Animation constants
    val contractedHeight = 30f
    val expandedHeight = 44f
    val contractedGap = 20f
    val expandedGap = 36f
    val animDuration = 300
    
    // Animatable values (in float for dp conversion later)
    val heightAnim = remember { Animatable(contractedHeight) }
    val gapAnim = remember { Animatable(contractedGap) }
    val contentAlphaAnim = remember { Animatable(0f) }
    val visibilityAnim = remember { Animatable(0f) }
    
    // Display state for content
    val displayState = remember { mutableStateOf(state) }
    
    // Main animation sequencer
    LaunchedEffect(state) {
        val isExpanding = state !is DynamicIslandState.Contracted
        
        if (isExpanding) {
            // ========== EXPANSION SEQUENCE ==========
            // Step 1: Show content immediately
            displayState.value = state
            
            // Step 2: Fade in visibility
            visibilityAnim.animateTo(1f, animationSpec = tween(200))
            
            // Step 3: Expand WIDTH first (horizontal expansion)
            launch {
                gapAnim.animateTo(expandedGap, animationSpec = tween(animDuration, easing = FastOutSlowInEasing))
            }
            contentAlphaAnim.animateTo(1f, animationSpec = tween(250))
            
            // Step 4: Wait for width to finish, then expand HEIGHT (vertical expansion)
            delay(animDuration.toLong())
            heightAnim.animateTo(expandedHeight, animationSpec = tween(animDuration, easing = FastOutSlowInEasing))
            
        } else {
            // ========== CONTRACTION SEQUENCE ==========
            // Step 1: Fade out content immediately
            contentAlphaAnim.animateTo(0f, animationSpec = tween(150))
            
            // Step 2: Contract HEIGHT first (vertical contraction from bottom)
            heightAnim.animateTo(contractedHeight, animationSpec = tween(animDuration, easing = FastOutSlowInEasing))
            
            // Step 3: After height is done, contract WIDTH (horizontal contraction)
            gapAnim.animateTo(contractedGap, animationSpec = tween(animDuration, easing = FastOutSlowInEasing))
            
            // Step 4: Remove content and fade out visibility
            displayState.value = state
            visibilityAnim.animateTo(0f, animationSpec = tween(200))
        }
    }
    
    // Convert animated floats to Dp
    val height = heightAnim.value.dp
    val gapWidth = gapAnim.value.dp
    val contentAlpha = contentAlphaAnim.value
    val visibility = visibilityAnim.value
    
    // Don't render when fully invisible and contracted
    if (state is DynamicIslandState.Contracted && visibility == 0f && displayState.value is DynamicIslandState.Contracted) {
        return
    }

    // Pulse Animation for processing state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, 
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val cornerRadius = height / 2
    val safeScale = visibility.coerceAtLeast(0.01f)

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = safeScale
                scaleY = safeScale
                alpha = visibility
            }
            .wrapContentSize(Alignment.TopCenter)
            .height(height),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Black,
        shadowElevation = 8.dp
    ) {
        // Use custom symmetrical layout
        SymmetricalIslandLayout(
            gapWidth = gapWidth,
            modifier = Modifier
        ) {
            // LEFT WING
            Box(
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(start = 20.dp, end = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                when (val currentState = displayState.value) {
                    is DynamicIslandState.Processing -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    LocalAccentColor.current.copy(alpha = pulseAlpha),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                    is DynamicIslandState.Info -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentState.icon != null) {
                                Icon(
                                    imageVector = currentState.icon,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = currentState.secondaryLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = LocalAccentColor.current,
                                maxLines = 1
                            )
                        }
                    }
                    is DynamicIslandState.Listening -> {
                        // Voice-reactive orb
                        val db = currentState.rmsDb.coerceIn(-2f, 10f)
                        val targetScale = 1f + ((db + 2f) / 12f) * 0.8f
                        val orbScale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "voiceOrb"
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .scale(orbScale)
                                .background(LocalAccentColor.current, androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                    else -> {}
                }
            }

            // RIGHT WING (Content aligned to start, near gap)
            Box(
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(end = 20.dp, start = 4.dp), // More generous padding
                contentAlignment = Alignment.CenterStart
            ) {
                when (val currentState = displayState.value) {
                    is DynamicIslandState.Processing -> {
                        Text(
                            text = "Thinking...",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                    is DynamicIslandState.Info -> {
                        Text(
                            text = currentState.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is DynamicIslandState.Listening -> {
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

/**
 * A custom layout that places two measurables (Left and Right) separated by a fixed Gap.
 * The layout enforces symmetry: the total width is (MaxWingWidth * 2) + Gap.
 * Left content is aligned to the End of the left wing.
 * Right content is aligned to the Start of the right wing.
 */
@Composable
private fun SymmetricalIslandLayout(
    gapWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        // We expect exactly 2 items: Left Wing, Right Wing.
        // Even if empty, they are added as Box { ... } in the Composable above.
        
        val gapPx = gapWidth.roundToPx()
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        // Measure children
        val placables = measurables.map { it.measure(looseConstraints) }
        val leftPlaceable = placables.getOrNull(0)
        val rightPlaceable = placables.getOrNull(1)

        val leftW = leftPlaceable?.width ?: 0
        val rightW = rightPlaceable?.width ?: 0
        val leftH = leftPlaceable?.height ?: 0
        val rightH = rightPlaceable?.height ?: 0

        // Determine the maximum width of a wing to ensure symmetry
        val maxWingWidth = max(leftW, rightW)
        
        // Calculate total measurements
        // Always enforce sufficient height for the pill shape
        val minHeight = 30.dp.roundToPx()
        val totalHeight = max(minHeight, max(leftH, rightH))
        
        // Ensure minimum total width is at least height for perfect circle in contracted state
        val totalWidth = max(minHeight, (maxWingWidth * 2) + gapPx)

        layout(totalWidth, totalHeight) {
            // Place Left Wing: End aligned -> x = (maxWingWidth - leftWidth)
            // Center vertically
            if (leftPlaceable != null) {
                val leftX = maxWingWidth - leftW
                val leftY = (totalHeight - leftH) / 2
                leftPlaceable.placeRelative(x = leftX, y = leftY)
            }

            // Place Right Wing: Start aligned -> x = maxWingWidth + gap
            // Center vertically
            if (rightPlaceable != null) {
                val rightX = maxWingWidth + gapPx
                val rightY = (totalHeight - rightH) / 2
                rightPlaceable.placeRelative(x = rightX, y = rightY)
            }
        }
    }
}
