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
import androidx.compose.ui.graphics.Color
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
}

@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: DynamicIslandState = DynamicIslandState.Contracted
) {
    // Apple-style Spring Physics
    // Stiff but damped response for "solid" feel
    
    // Animate the "Gap" (Camera Dead Zone)
    // Apple spec: tight when contracted, breathing room when expanded
    val gapWidth by animateDpAsState(
        targetValue = if (state is DynamicIslandState.Contracted) 30.dp else 48.dp, 
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "gapWidth"
    )

    // Animate Corner Radius (Circle -> Pill)
    // Contracted: height/2 for perfect circle, Expanded: height/2 for perfect pill
    val cornerRadius by animateDpAsState(
        targetValue = if (state is DynamicIslandState.Contracted) 15.dp else 19.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "cornerRadius"
    )
    
    // Animate content opacity
    val contentAlpha by animateFloatAsState(
        targetValue = if (state is DynamicIslandState.Contracted) 0f else 1f,
        animationSpec = tween(150, delayMillis = 50),
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

    Surface(
        modifier = modifier
            .animateContentSize(
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                alignment = Alignment.Center // CRITICAL: Expands from center
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Black,
        shadowElevation = 0.dp
    ) {
        // Custom Layout for Center-Anchored Symmetry
        // Custom Layout for Center-Anchored Symmetry
        SymmetricalIslandLayout(
            gapWidth = gapWidth
        ) {
            // LEFT WING (Content Aligned Right, near Gap)
            Box(contentAlignment = Alignment.CenterEnd) {
                 if (state !is DynamicIslandState.Contracted) {
                     Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(contentAlpha)
                     ) {
                         when (state) {
                             is DynamicIslandState.Processing -> {
                                 // Pulse Dot (Apple standard size)
                                 Box(
                                     modifier = Modifier
                                         .size(8.dp)
                                         .background(LocalAccentColor.current.copy(alpha = pulseAlpha), androidx.compose.foundation.shape.CircleShape)
                                 )
                             }
                             is DynamicIslandState.Info -> {
                                 // Icon + Count (Apple 14-15pt text)
                                 if (state.icon != null) {
                                     Icon(
                                         imageVector = state.icon,
                                         contentDescription = null,
                                         tint = LocalAccentColor.current,
                                         modifier = Modifier.size(16.dp)
                                     )
                                     Spacer(Modifier.width(5.dp))
                                 }
                                 Text(
                                     text = state.secondaryLabel,
                                     style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                     color = LocalAccentColor.current,
                                     maxLines = 1
                                 )
                             }
                             else -> {}
                         }
                         Spacer(Modifier.width(8.dp)) // Apple standard gap from camera
                     }
                 }
            }

            // RIGHT WING (Content Aligned Left, near Gap)
            Box(contentAlignment = Alignment.CenterStart) {
                if (state !is DynamicIslandState.Contracted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(contentAlpha)
                    ) {
                        Spacer(Modifier.width(8.dp)) // Apple standard gap from camera
                        when (state) {
                            is DynamicIslandState.Processing -> {
                                Text(
                                    text = "Processing",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                            is DynamicIslandState.Info -> {
                                Text(
                                    text = state.label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            else -> {}
                        }
                    }
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
