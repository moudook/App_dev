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
import com.example.smarty.ui.LocalAccentColor
import kotlin.math.max

sealed interface DynamicIslandState {
    object Contracted : DynamicIslandState
    object Processing : DynamicIslandState
    data class Info(val label: String, val secondaryLabel: String, val icon: ImageVector? = null) : DynamicIslandState
    data class Listening(val rmsDb: Float) : DynamicIslandState
}

@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: DynamicIslandState = DynamicIslandState.Contracted
) {
    // REQUIREMENT 1: In contracted mode, render nothing (hidden by punch hole)
    val isVisible = state !is DynamicIslandState.Contracted
    
    // REQUIREMENT 3: Natural spring animation specs
    val expansionSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val dpSpring = spring<Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
    
    // Visibility animation (0 = invisible, 1 = visible)
    val visibility by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = expansionSpring,
        label = "visibility"
    )

    // Don't render anything when fully contracted to avoid layout issues
    if (visibility < 0.01f && !isVisible) {
        return
    }

    // Content opacity (fades in after expansion starts)
    val contentAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(200, delayMillis = 80),
        label = "contentAlpha"
    )

    // Processing Pulse (Apple-style gentle breathing)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, 
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Gap width (camera dead zone) - animated
    val gapWidth by animateDpAsState(
        targetValue = if (isVisible) 48.dp else 30.dp,
        animationSpec = dpSpring,
        label = "gapWidth"
    )

    // Corner radius - half of height for perfect pill shape
    val cornerRadius = 15.dp  // Half of punchHoleHeight (30dp / 2)
    
    // Fixed height to match punch hole diameter (Infinix Note 10: ~30dp)
    // This ensures the Dynamic Island height matches the camera cutout exactly
    val punchHoleHeight = 30.dp

    // REQUIREMENT 2 & 4: Surface that expands from center in both directions
    // Clamp scale to minimum 0.01 to prevent layout issues with zero-size
    val safeScale = visibility.coerceAtLeast(0.01f)

    Surface(
        modifier = modifier
            .graphicsLayer {
                // Scale from center for symmetric expansion
                scaleX = safeScale
                scaleY = safeScale
                alpha = visibility
            }
            .wrapContentSize(Alignment.Center),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Black,
        shadowElevation = 4.dp
    ) {
        // Use custom symmetrical layout that adapts width to content
        SymmetricalIslandLayout(
            gapWidth = gapWidth,
            modifier = Modifier
                .height(punchHoleHeight)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            // LEFT WING (Content aligned to end, near gap)
            Box(
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                when (state) {
                    is DynamicIslandState.Processing -> {
                        // Pulsing dot
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
                            if (state.icon != null) {
                                Icon(
                                    imageVector = state.icon,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = state.secondaryLabel,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = LocalAccentColor.current,
                                maxLines = 1
                            )
                        }
                    }
                    is DynamicIslandState.Listening -> {
                        // Voice-reactive orb
                        val db = state.rmsDb.coerceIn(-2f, 10f)
                        val targetScale = 1f + ((db + 2f) / 12f) * 0.8f
                        val orbScale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "voiceOrb"
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
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
                    .padding(end = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                when (state) {
                    is DynamicIslandState.Processing -> {
                        Text(
                            text = "Processing",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    is DynamicIslandState.Info -> {
                        Text(
                            text = state.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is DynamicIslandState.Listening -> {
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
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
        
        // Calculate height:
        // Expanded: Content height + vertical padding (for pill shape)
        // Contracted: No content padding (just min height)
        val hasContent = maxWingWidth > 0
        val verticalPadding = if (hasContent) 18.dp.roundToPx() else 0 // ~37-40dp total height
        
        val totalHeight = max(30.dp.roundToPx(), max(leftH, rightH) + verticalPadding)
        
        // CRITICAL: In contracted state (no content), create perfect circle
        // Otherwise gap-only width (28dp) < height (30dp) = vertical capsule
        val totalWidth = if (!hasContent) {
            // Contracted: force width = height for perfect circle
            totalHeight
        } else {
            // Expanded: symmetrical wings + gap
            (maxWingWidth * 2) + gapPx
        }

        layout(totalWidth, totalHeight) {
            // Place Left Wing: End aligned -> x = (maxWingWidth - leftWidth)
            if (leftPlaceable != null) {
                val leftX = maxWingWidth - leftW
                val leftY = (totalHeight - leftH) / 2
                leftPlaceable.placeRelative(x = leftX, y = leftY)
            }

            // Place Right Wing: Start aligned -> x = maxWingWidth + gap
            if (rightPlaceable != null) {
                val rightX = maxWingWidth + gapPx
                val rightY = (totalHeight - rightH) / 2
                rightPlaceable.placeRelative(x = rightX, y = rightY)
            }
        }
    }
}
